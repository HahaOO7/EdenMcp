package at.haha007.edenmcp.hooks.spark;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Schema-free decoder for spark's saved protobuf files.
 *
 * <p>Spark's protobuf classes are shaded inside the spark plugin and cannot be linked against
 * safely. This decoder exposes the wire structure (field numbers, wire types and decoded
 * values) so tool callers can inspect and analyse the raw sampler/heap data without uploading
 * it anywhere.
 */
final class ProtobufInspector {
    private static final int MAX_STRING_CHARS = 4096;
    private static final int DEFAULT_MAX_TREE_CHARS = 1_000_000;
    private static final Object OMITTED = new Object();
    // Conservative per-node allowances so the emitted tree stays below the serialized-size cap.
    private static final int MAP_OVERHEAD = 64;
    private static final int LIST_OVERHEAD = 4;
    private static final int NUMBER_COST = 24;

    private ProtobufInspector() {
    }

    record Result(Map<String, Object> tree, Map<String, Object> summary, boolean truncated,
                  int bytesInspected, int totalBytes, int omittedNodes) {
    }

    static Result inspect(byte[] data, int maxDepth, int maxNodes, int maxBytes) {
        return inspect(data, maxDepth, maxNodes, maxBytes, DEFAULT_MAX_TREE_CHARS);
    }

    /** Inspects with an additional cap on the approximate serialized size of the returned tree. */
    static Result inspect(byte[] data, int maxDepth, int maxNodes, int maxBytes, int maxTreeChars) {
        // Nested messages are counted again as they are decoded, so the budget must not be
        // clamped to the raw file length or shallow structures would be marked truncated.
        Budget budget = new Budget(maxDepth, maxNodes, maxBytes);
        ParseAll parsed = parseAll(data, 0, budget);

        TreeBudget treeBudget = new TreeBudget(Math.max(1, maxTreeChars));
        treeBudget.consume(32); // Outer {"fields": [...]} wrapper.
        List<Object> fields = boundFields(parsed.fields(), treeBudget);
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("fields", fields);
        boolean truncated = budget.exhausted() || !parsed.complete() || treeBudget.omitted() > 0;
        return new Result(tree, summarize(parsed.fields()), truncated,
                Math.min(budget.bytes(), data.length), data.length, treeBudget.omitted());
    }

    /** Copies decoded fields while dropping nodes once the output budget is spent. */
    private static List<Object> boundFields(List<Object> fields, TreeBudget budget) {
        List<Object> bounded = new ArrayList<>();
        for (Object field : fields) {
            Object copy = boundNode(field, budget);
            if (copy != OMITTED) bounded.add(copy);
        }
        return bounded;
    }

    private static Object boundNode(Object node, TreeBudget budget) {
        boolean fieldNode = isFieldNode(node);
        Object copy = copyBounded(node, budget);
        if (copy == OMITTED && fieldNode) budget.omitField();
        return copy;
    }

    private static boolean isFieldNode(Object node) {
        return node instanceof Map<?, ?> map && map.containsKey("field");
    }

    private static Object copyBounded(Object node, TreeBudget budget) {
        if (node instanceof List<?> list) {
            if (!budget.consume(LIST_OVERHEAD)) return OMITTED;
            List<Object> copy = new ArrayList<>();
            for (Object child : list) {
                Object bounded = boundNode(child, budget);
                if (bounded != OMITTED) copy.add(bounded);
            }
            return copy;
        }
        if (node instanceof Map<?, ?> map) {
            if (!budget.consume(MAP_OVERHEAD)) return OMITTED;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object bounded = boundNode(entry.getValue(), budget);
                if (bounded == OMITTED) return OMITTED;
                copy.put(String.valueOf(entry.getKey()), bounded);
            }
            return copy;
        }
        int cost = node instanceof String text ? text.length() * 2 + 4 : NUMBER_COST;
        return budget.consume(cost) ? node : OMITTED;
    }

    private static ParseAll parseAll(byte[] data, int depth, Budget budget) {
        List<Object> fields = new ArrayList<>();
        int position = 0;
        while (position < data.length && !budget.exhausted()) {
            Parsed field = parseField(data, position, depth, budget);
            if (field == null) break;
            fields.add(field.value());
            position = field.end();
        }
        return new ParseAll(fields, position, position == data.length);
    }

    private static @Nullable Parsed parseField(byte[] data, int position, int depth, Budget budget) {
        Varint tag = readVarint(data, position);
        if (tag == null || tag.value() == 0) return null;
        int fieldNumber = (int) (tag.value() >>> 3);
        int wireType = (int) (tag.value() & 7);
        if (fieldNumber == 0 || wireType == 3 || wireType == 4 || wireType > 5) return null;
        int cursor = tag.end();
        if (!budget.consume(1)) return null;

        Object value;
        int end;
        switch (wireType) {
            case 0 -> {
                Varint number = readVarint(data, cursor);
                if (number == null) return null;
                value = number.value();
                end = number.end();
                if (!budget.consume(end - cursor)) return null;
            }
            case 1 -> {
                if (cursor + 8 > data.length) return null;
                value = ByteBuffer.wrap(data, cursor, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                end = cursor + 8;
                if (!budget.consume(8)) return null;
            }
            case 5 -> {
                if (cursor + 4 > data.length) return null;
                value = ByteBuffer.wrap(data, cursor, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                end = cursor + 4;
                if (!budget.consume(4)) return null;
            }
            case 2 -> {
                Varint length = readVarint(data, cursor);
                if (length == null || length.value() < 0 || length.value() > data.length - length.end()) return null;
                int size = (int) length.value();
                int sliceStart = length.end();
                end = sliceStart + size;
                if (!budget.consume(end - cursor)) return null;
                byte[] slice = new byte[size];
                System.arraycopy(data, sliceStart, slice, 0, size);
                value = decodeLengthDelimited(slice, depth, budget);
            }
            default -> {
                return null;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("field", fieldNumber);
        result.put("wireType", wireType);
        result.put("value", value);
        return new Parsed(result, end);
    }

    private static Object decodeLengthDelimited(byte[] slice, int depth, Budget budget) {
        String text = printableUtf8(slice);
        if (text != null) return text;

        if (depth < budget.maxDepth() && slice.length >= 2) {
            Budget.Snapshot before = budget.snapshot();
            ParseAll nested = parseAll(slice, depth + 1, budget);
            if (nested.complete() && !nested.fields().isEmpty()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("message", nested.fields());
                return map;
            }
            budget.restore(before);
        }
        return abbreviatedBase64(slice);
    }

    private static @Nullable String printableUtf8(byte[] bytes) {
        if (bytes.length == 0) return "";
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            int printable = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t') printable++;
            }
            if (printable != text.length()) return null;
            return text.length() <= MAX_STRING_CHARS
                    ? text
                    : text.substring(0, MAX_STRING_CHARS) + "... (truncated)";
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String abbreviatedBase64(byte[] bytes) {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return encoded.length() <= 4096 ? encoded : encoded.substring(0, 4096) + "... (truncated)";
    }

    private static @Nullable Varint readVarint(byte[] data, int position) {
        long value = 0;
        int shift = 0;
        int cursor = position;
        while (cursor < data.length && shift <= 63) {
            int b = data[cursor++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new Varint(value, cursor);
            shift += 7;
        }
        return null;
    }

    private static Map<String, Object> summarize(List<Object> fields) {
        Map<String, Integer> histogram = new TreeMap<>();
        List<String> strings = new ArrayList<>();
        int[] depth = {0};
        walk(fields, 0, histogram, strings, depth);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topLevelFields", fields.size());
        summary.put("fieldHistogram", histogram);
        summary.put("maxDepth", depth[0]);
        summary.put("sampleStrings", strings.stream().limit(40).toList());
        return summary;
    }

    private static void walk(Object value, int depth, Map<String, Integer> histogram,
                             List<String> strings, int[] maxDepth) {
        maxDepth[0] = Math.max(maxDepth[0], depth);
        if (value instanceof List<?> list) {
            for (Object child : list) walk(child, depth + 1, histogram, strings, maxDepth);
        } else if (value instanceof Map<?, ?> map) {
            Object field = map.get("field");
            if (field instanceof Number number) {
                String key = String.valueOf(number.intValue());
                histogram.merge(key, 1, Integer::sum);
            }
            for (Object child : map.values()) {
                walk(child, depth + 1, histogram, strings, maxDepth);
            }
        } else if (value instanceof String text && strings.size() < 200) {
            if (!text.isBlank() && text.length() <= 300) strings.add(text);
        }
    }

    private record Varint(long value, int end) {
    }

    private record Parsed(Object value, int end) {
    }

    private record ParseAll(List<Object> fields, int end, boolean complete) {
    }

    /** Approximate serialized-size budget for the response tree (not the parse budget). */
    private static final class TreeBudget {
        private final int maxChars;
        private long chars;
        private int omitted;

        TreeBudget(int maxChars) {
            this.maxChars = maxChars;
        }

        boolean consume(int cost) {
            if (chars + cost > maxChars) return false;
            chars += cost;
            return true;
        }

        void omitField() {
            omitted++;
        }

        int omitted() {
            return omitted;
        }
    }

    private static final class Budget {
        private final int maxDepth;
        private final int maxNodes;
        private final int maxBytes;
        private int nodes;
        private int bytes;

        Budget(int maxDepth, int maxNodes, int maxBytes) {
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxBytes = maxBytes;
        }

        int maxDepth() {
            return maxDepth;
        }

        boolean exhausted() {
            return nodes >= maxNodes || bytes >= maxBytes;
        }

        boolean consume(int amount) {
            if (bytes + amount > maxBytes || nodes + 1 > maxNodes) return false;
            bytes += amount;
            nodes++;
            return true;
        }

        int bytes() {
            return bytes;
        }

        Snapshot snapshot() {
            return new Snapshot(nodes, bytes);
        }

        void restore(Snapshot snapshot) {
            this.nodes = snapshot.nodes();
            this.bytes = snapshot.bytes();
        }

        record Snapshot(int nodes, int bytes) {
        }
    }
}
