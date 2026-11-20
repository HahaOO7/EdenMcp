package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.config.EdenConfig;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class LogsReadTool implements McpTool {
    // The first character class excludes the quantifier characters, so the group
    // cannot be matched at multiple positions - avoids super-linear backtracking.
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("(\\([^)+*]*[+*][^)]*\\))[+*{]");
    // Alternation inside a quantified group (e.g. (a|aa)+, (a|a?)*) also backtracks
    // exponentially, but is not covered by NESTED_QUANTIFIER. Reject it as well;
    // the nanoTime budget below is the real safety net for anything else.
    private static final Pattern QUANTIFIED_ALTERNATION = Pattern.compile("\\([^)]*\\|[^)]*\\)[+*{]");
    private static final long REGEX_TOTAL_BUDGET_NANOS = 50L * 1_000_000; // 50 ms per call
    private static final long REGEX_LINE_BUDGET_NANOS = 5L * 1_000_000;   // 5 ms per line
    private static final int REGEX_MAX_LINE_CHARS = 8192;
    private final LogFileAccess logs;
    private final boolean requireModerate;
    private final int defaultLimit;
    private final int maxLimit;
    private final int maxScanBytes;
    private final int maxPatternLength;

    public LogsReadTool(Path logsDir, boolean requireModerate, EdenConfig.ToolConfig tools) {
        this.logs = new LogFileAccess(logsDir, tools.logsMaxDecompressedBytes());
        this.requireModerate = requireModerate;
        this.defaultLimit = tools.logsDefaultLimit();
        this.maxLimit = tools.logsMaxLimit();
        this.maxScanBytes = tools.logsMaxScanBytes();
        this.maxPatternLength = tools.logsMaxPatternLength();
    }

    @Override public String name() { return "edenmcp.logs.read"; }
    @Override public String description() {
        return "Read recent server log lines (plain, .gz or .zip) with optional filter/limit (bounded).";
    }
    @Override public CapabilityLevel level() { return requireModerate ? CapabilityLevel.MODERATE_ACTION : CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("logs"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file", Map.of("type", "string", "description",
                                "File name inside logs/ (default: latest.log); .gz and .zip are decompressed"),
                        "limit", Map.of("type", "integer", "description",
                                "Max lines to return (default " + defaultLimit + ", max " + maxLimit + ")"),
                        "filter", Map.of("type", "string", "description", "Optional substring or regex filter"),
                        "regex", Map.of("type", "boolean", "description", "Treat filter as regex (default false)")
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        String filter;
        boolean regex;
        String fileName;
        try {
            fileName = ToolArgs.string(context.arguments(), "file");
            limit = ToolArgs.integer(context.arguments(), "limit", defaultLimit, 1, maxLimit);
            filter = ToolArgs.string(context.arguments(), "filter");
            regex = ToolArgs.bool(context.arguments(), "regex");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (fileName == null || fileName.isBlank()) fileName = "latest.log";
        if (filter != null && filter.isBlank()) filter = null;
        if (filter != null && filter.length() > maxPatternLength) return ToolResult.error("Filter is too long");

        Pattern pattern = null;
        if (filter != null && regex) {
            if (NESTED_QUANTIFIER.matcher(filter).find()) return ToolResult.error("Regex filter rejected: nested quantifier");
            if (QUANTIFIED_ALTERNATION.matcher(filter).find()) return ToolResult.error("Regex filter rejected: quantified alternation");
            try {
                pattern = Pattern.compile(filter);
            } catch (Exception e) {
                return ToolResult.error("Invalid regex");
            }
        }
        try {
            Path path = logs.resolve(fileName);
            Tail tail = readTail(path, fileName, limit, filter, pattern);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", fileName);
            out.put("compression", LogFileAccess.compressionOf(fileName).name().toLowerCase());
            out.put("lines", tail.lines());
            out.put("count", tail.lines().size());
            out.put("truncated", tail.truncated());
            return ToolResult.ok(out);
        } catch (RegexTimeoutException e) {
            return ToolResult.error("Regex filter timed out");
        } catch (IOException e) {
            return ToolResult.error("Failed to read logs: " + e.getMessage());
        }
    }

    /** Reads the tail from a single open handle for plain files, or a bounded tail window for archives. */
    private Tail readTail(Path path, String fileName, int limit,
                          @Nullable String filter, @Nullable Pattern pattern) throws IOException {
        LogFileAccess.Compression compression = LogFileAccess.compressionOf(fileName);
        TailBytes data;
        if (compression == LogFileAccess.Compression.PLAIN) {
            data = readPlainTail(path);
        } else {
            LogFileAccess.Decompressed decoded = logs.readCompressed(path, compression, maxScanBytes);
            data = new TailBytes(decoded.bytes(), decoded.truncated());
        }
        return processLines(data.bytes(), data.truncated(), limit, filter, pattern);
    }

    private record TailBytes(byte[] bytes, boolean truncated) {}

    private TailBytes readPlainTail(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Log file is not a regular file");
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long length = raf.length();
            if (length <= 0) return new TailBytes(new byte[0], false);
            long start = Math.max(0, length - maxScanBytes);
            raf.seek(start);
            if (start > 0) {
                // Drop the partial first line at byte level so a cut multi-byte
                // sequence can never make the strict UTF-8 decode fail.
                int b;
                do {
                    b = raf.read();
                } while (b >= 0 && b != '\n');
                start = raf.getFilePointer();
            }
            int toRead = (int) Math.clamp(length - start, 0L, maxScanBytes);
            byte[] buf = new byte[toRead];
            int total = 0;
            while (total < toRead) {
                int n = raf.read(buf, total, toRead - total);
                if (n < 0) break;
                total += n;
            }
            byte[] exact = new byte[total];
            System.arraycopy(buf, 0, exact, 0, total);
            return new TailBytes(exact, start > 0);
        }
    }

    private Tail processLines(byte[] bytes, boolean truncated, int limit,
                              @Nullable String filter, @Nullable Pattern pattern) throws IOException {
        if (bytes.length == 0) return new Tail(List.of(), truncated);
        for (byte b : bytes) {
            if (b == 0) throw new IOException("Log file contains binary data");
        }
        String text = decodeUtf8(bytes, bytes.length);
        String[] parts = text.split("\\R", -1);
        Deque<String> out = new ArrayDeque<>();
        long totalDeadline = System.nanoTime() + REGEX_TOTAL_BUDGET_NANOS;
        try {
            for (int i = 0; i < parts.length; i++) {
                String line = parts[i];
                if (i == parts.length - 1 && line.isEmpty()) continue;
                if (filter != null && !filter.isBlank()) {
                    if (pattern != null) {
                        if (!matchesWithinBudget(pattern, line, totalDeadline)) continue;
                    } else if (!line.contains(filter)) {
                        continue;
                    }
                }
                out.addLast(line);
                while (out.size() > limit) out.removeFirst();
            }
        } catch (RegexBudgetExceeded e) {
            throw new RegexTimeoutException();
        }
        return new Tail(new ArrayList<>(out), truncated);
    }

    /** Applies the regex to at most REGEX_MAX_LINE_CHARS characters under a per-line and a per-call budget. */
    static boolean matchesWithinBudget(Pattern pattern, String line, long totalDeadline) {
        long lineDeadline = Math.min(totalDeadline, System.nanoTime() + REGEX_LINE_BUDGET_NANOS);
        CharSequence input = line.length() <= REGEX_MAX_LINE_CHARS
                ? line
                : line.subSequence(0, REGEX_MAX_LINE_CHARS);
        return pattern.matcher(new BudgetedCharSequence(input, lineDeadline)).find();
    }

    /** Thrown by the budgeted CharSequence whenever a regex reads past its deadline. */
    static final class RegexBudgetExceeded extends RuntimeException {
        RegexBudgetExceeded() { super("Regex budget exceeded"); }
    }

    /** Signals to execute() that a regex filter exceeded its CPU budget. */
    static final class RegexTimeoutException extends IOException {
        RegexTimeoutException() { super("Regex filter timed out"); }
    }

    /**
     * Matcher reads its input exclusively through charAt()/length()/subSequence()
     * while backtracking, so checking the clock in those methods bounds regex CPU
     * time even for patterns with exponential backtracking.
     */
    static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;

        BudgetedCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        private void check() {
            if (System.nanoTime() - deadlineNanos >= 0) throw new RegexBudgetExceeded();
        }

        @Override public int length() { check(); return delegate.length(); }
        @Override public char charAt(int index) { check(); return delegate.charAt(index); }
        @Override public @NotNull CharSequence subSequence(int start, int end) {
            check();
            return new BudgetedCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }
        @Override public @NotNull String toString() { check(); return delegate.toString(); }
    }

    private static String decodeUtf8(byte[] bytes, int length) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Log file is not valid UTF-8 text", e);
        }
    }

    private record Tail(List<String> lines, boolean truncated) {}
}
