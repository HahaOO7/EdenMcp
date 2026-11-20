package at.haha007.edenmcp.hooks.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.track.Track;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Shared helpers for the LuckPerms tools. */
final class LpSupport {
    static final int MAX_NODES = 200;
    static final int MAX_AUDIT = 200;
    static final int MAX_LIST = 200;
    static final int LUCKPERMS_TIMEOUT_SECONDS = 10;
    static final int MAX_CONTEXT_PAIRS = 32;
    static final int MAX_CONTEXT_KEY_LENGTH = 64;
    static final int MAX_CONTEXT_VALUE_LENGTH = 256;
    static final int MAX_DISPLAY_VALUE_LENGTH = 256;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final UUID AUDIT_SOURCE = UUID.nameUUIDFromBytes("EdenMcp".getBytes(StandardCharsets.UTF_8));

    private LpSupport() {
    }

    static LuckPerms api() {
        return LuckPermsProvider.get();
    }

    /** An input problem that should be reported to the caller without a "LuckPerms error" prefix. */
    static final class InvalidArgument extends Exception {
        InvalidArgument(String message) {
            super(message);
        }
    }

    /** A LuckPerms future did not complete within the bounded wait. Details stay server-side. */
    static final class LuckPermsTimeoutException extends RuntimeException {
        LuckPermsTimeoutException(String operation, Throwable cause) {
            super("LuckPerms operation timed out: " + operation, cause);
        }
    }

    /** Waits for a LuckPerms future with a fixed bound so a slow backend cannot block a worker. */
    static <T> T await(CompletableFuture<T> future, String operation) {
        return await(future, operation, LUCKPERMS_TIMEOUT_SECONDS);
    }

    /** Same bounded wait with an explicit timeout, primarily for fast tests. */
    static <T> T await(CompletableFuture<T> future, String operation, long timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LuckPermsTimeoutException(operation, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new LuckPermsTimeoutException(operation, e);
        }
    }

    /** Reads a textual argument; numbers, booleans and objects are rejected. */
    static String stringArg(Map<String, Object> args, String key) throws InvalidArgument {
        Object value = args == null ? null : args.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new InvalidArgument("Invalid " + key);
        return text.trim();
    }

    static String requiredArg(Map<String, Object> args, String key) throws InvalidArgument {
        String value = stringArg(args, key);
        if (value == null || value.isEmpty()) {
            throw new InvalidArgument("Provide " + key);
        }
        return value;
    }

    static String nameArg(Map<String, Object> args, String key) throws InvalidArgument {
        String value = requiredArg(args, key);
        if (!NAME.matcher(value).matches()) {
            throw new InvalidArgument("Invalid " + key);
        }
        return value;
    }

    static String keyArg(Map<String, Object> args, String key) throws InvalidArgument {
        String value = requiredArg(args, key);
        if (value.length() > 128 || hasControlCharacters(value)) {
            throw new InvalidArgument("Invalid " + key);
        }
        return value;
    }

    /** For prefixes/suffixes: longer than a permission key, but still free of control characters. */
    static String displayValueArg(Map<String, Object> args) throws InvalidArgument {
        String value = requiredArg(args, "value");
        if (value.length() > MAX_DISPLAY_VALUE_LENGTH || hasControlCharacters(value)) {
            throw new InvalidArgument("Invalid value");
        }
        return value;
    }

    static int intArg(Map<String, Object> args, String key, int defaultValue, int min, int max)
            throws InvalidArgument {
        Object value = args == null ? null : args.get(key);
        if (value == null) return defaultValue;
        Integer parsed = integerValue(value);
        if (parsed == null) throw new InvalidArgument("Invalid " + key);
        if (parsed < min || parsed > max) {
            throw new InvalidArgument(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    /** Accepts JSON numbers (including integral decimals), but never numeric strings. */
    private static Integer integerValue(Object value) {
        if (!(value instanceof Number number)) return null;
        try {
            return new BigDecimal(number.toString()).toBigIntegerExact().intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            // Reported as an invalid argument by the caller.
            return null;
        }
    }

    static int boundedLimitArg(Map<String, Object> args, int defaultValue, int max) throws InvalidArgument {
        return intArg(args, "limit", defaultValue, 1, max);
    }

    static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) throws InvalidArgument {
        Object value = args == null ? null : args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean bool) return bool;
        throw new InvalidArgument("Invalid " + key);
    }

    static Instant expiryArg(Map<String, Object> args) throws InvalidArgument {
        String value = stringArg(args, "expiry");
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidArgument("Invalid expiry timestamp");
        }
    }

    private static boolean hasControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    /** Resolves a user from {@code username} or {@code uuid} arguments. */
    static User requireUser(LuckPerms api, Map<String, Object> args) throws InvalidArgument {
        String uuidStr = stringArg(args, "uuid");
        String username = stringArg(args, "username");
        UUID uuid = null;
        if (uuidStr != null && !uuidStr.isEmpty()) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                throw new InvalidArgument("Invalid uuid");
            }
        }
        if (uuid == null && username != null && !username.isEmpty()) {
            uuid = await(api.getUserManager().lookupUniqueId(username), "lookupUniqueId");
        }
        if (uuid == null) {
            throw new InvalidArgument("Provide username or uuid");
        }
        User user = await(api.getUserManager().loadUser(uuid), "loadUser");
        if (user == null) {
            throw new InvalidArgument("User not found");
        }
        return user;
    }

    static Group requireGroup(LuckPerms api, String name) throws InvalidArgument {
        return await(api.getGroupManager().loadGroup(name), "loadGroup")
                .orElseThrow(() -> new InvalidArgument("Group not found"));
    }

    static Track requireTrack(LuckPerms api, String name) throws InvalidArgument {
        return await(api.getTrackManager().loadTrack(name), "loadTrack")
                .orElseThrow(() -> new InvalidArgument("Track not found"));
    }

    static void saveHolder(LuckPerms api, PermissionHolder holder) {
        if (holder instanceof User user) {
            await(api.getUserManager().saveUser(user), "saveUser");
        } else {
            await(api.getGroupManager().saveGroup((Group) holder), "saveGroup");
        }
    }

    static QueryOptions queryOptions(LuckPerms api, User user, Map<String, Object> args) throws InvalidArgument {
        Object raw = args == null ? null : args.get("contexts");
        if (raw == null) {
            return api.getContextManager().getQueryOptions(user).orElse(QueryOptions.nonContextual());
        }
        return contextualOptions(raw);
    }

    static QueryOptions groupQueryOptions(LuckPerms api, Map<String, Object> args) throws InvalidArgument {
        Object raw = args == null ? null : args.get("contexts");
        if (raw == null) {
            return api.getContextManager().getStaticQueryOptions();
        }
        return contextualOptions(raw);
    }

    private static QueryOptions contextualOptions(Object raw) throws InvalidArgument {
        ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
        contextPairs(raw).forEach(builder::add);
        return QueryOptions.contextual(builder.build());
    }

    static ImmutableContextSet contextSet(Object raw) throws InvalidArgument {
        ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
        contextPairs(raw).forEach(builder::add);
        return builder.build();
    }

    /** Normalizes the optional {@code contexts} argument without needing a LuckPerms instance. */
    static Map<String, String> contextPairs(Object raw) throws InvalidArgument {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidArgument("contexts must be an object");
        }
        if (map.size() > MAX_CONTEXT_PAIRS) {
            throw new InvalidArgument("Too many contexts");
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String rawKey) || !(entry.getValue() instanceof String rawValue)) {
                throw new InvalidArgument("contexts must map strings to strings");
            }
            String key = rawKey.trim();
            String value = rawValue.trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            if (key.length() > MAX_CONTEXT_KEY_LENGTH || value.length() > MAX_CONTEXT_VALUE_LENGTH
                    || hasControlCharacters(key) || hasControlCharacters(value)) {
                throw new InvalidArgument("Invalid context");
            }
            pairs.put(key, value);
        }
        return pairs;
    }

    static PermissionNode permissionNode(Map<String, Object> args) throws InvalidArgument {
        var builder = PermissionNode.builder()
                .permission(keyArg(args, "permission"))
                .value(boolArg(args, "value", true))
                .context(contextSet(args.get("contexts")));
        Instant expiry = expiryArg(args);
        if (expiry != null) builder.expiry(expiry);
        return builder.build();
    }

    static InheritanceNode inheritanceNode(String groupName, Map<String, Object> args) throws InvalidArgument {
        var builder = InheritanceNode.builder()
                .group(groupName)
                .value(true)
                .context(contextSet(args.get("contexts")));
        Instant expiry = expiryArg(args);
        if (expiry != null) builder.expiry(expiry);
        return builder.build();
    }

    static MetaNode metaNode(String key, String value, Map<String, Object> args) throws InvalidArgument {
        return MetaNode.builder()
                .key(key)
                .value(value)
                .context(contextSet(args.get("contexts")))
                .build();
    }

    static PrefixNode prefixNode(String value, int priority, Map<String, Object> args) throws InvalidArgument {
        return PrefixNode.builder(value, priority)
                .context(contextSet(args.get("contexts")))
                .build();
    }

    static SuffixNode suffixNode(String value, int priority, Map<String, Object> args) throws InvalidArgument {
        return SuffixNode.builder(value, priority)
                .context(contextSet(args.get("contexts")))
                .build();
    }

    /** Removes all nodes with the given permission in exactly the supplied contexts. */
    static List<Node> clearPermissionNodes(PermissionHolder holder, String permission, ImmutableContextSet contexts) {
        return clearNodes(holder, node -> node instanceof PermissionNode p
                && p.getPermission().equalsIgnoreCase(permission) && node.getContexts().equals(contexts));
    }

    static List<Node> clearInheritanceNodes(PermissionHolder holder, String group, ImmutableContextSet contexts) {
        return clearNodes(holder, node -> node instanceof InheritanceNode i
                && i.getGroupName().equalsIgnoreCase(group) && node.getContexts().equals(contexts));
    }

    /** Removes matching nodes and returns them, so a failed mutation can restore the original state. */
    static List<Node> clearNodes(PermissionHolder holder, Predicate<Node> predicate) {
        List<Node> matching = holder.data().toCollection().stream().filter(predicate).toList();
        holder.data().clear(predicate);
        return matching;
    }

    /** Undoes a clear after a failed add; the cleared nodes can always be re-added to the same holder. */
    static void restoreNodes(PermissionHolder holder, List<Node> nodes) {
        for (Node node : nodes) {
            holder.data().add(node);
        }
    }

    static Map<String, Object> holderMap(PermissionHolder holder) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", holder instanceof User ? "user" : "group");
        out.put("name", holder.getFriendlyName());
        if (holder instanceof User user) {
            out.put("uuid", user.getUniqueId().toString());
        }
        return out;
    }

    static Map<String, Object> nodeMap(Node node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", node.getKey());
        out.put("type", node.getType().name());
        out.put("value", node.getValue());
        if (node.hasExpiry() && node.getExpiry() != null) {
            out.put("expiry", node.getExpiry().toString());
        }
        out.put("contexts", node.getContexts().toMap());
        return out;
    }

    /** Truncates a list to the response bound without mutating the original. */
    static <T> List<T> limited(List<T> values, int limit) {
        return values.size() > limit ? List.copyOf(values.subList(0, limit)) : values;
    }

    static boolean hasMore(Collection<?> values, int limit) {
        return values.size() > limit;
    }

    /** A bounded view of a larger stream, together with the number of elements seen before truncation. */
    record Bounded<T>(List<T> values, int total) {
        boolean hasMore() {
            return total > values.size();
        }
    }

    /** Keeps the greatest {@code limit} elements in descending order, counting every element seen. */
    static <T> Bounded<T> topN(Stream<T> values, Comparator<? super T> descending, int limit) {
        PriorityQueue<T> smallestSelected = new PriorityQueue<>(descending.reversed());
        int[] total = {0};
        values.forEach(value -> {
            total[0]++;
            if (smallestSelected.size() < limit) {
                smallestSelected.add(value);
            } else if (descending.compare(value, smallestSelected.peek()) < 0) {
                // Descending order: a negative comparison ranks the new value above the current head.
                smallestSelected.poll();
                smallestSelected.add(value);
            }
        });
        List<T> selected = new ArrayList<>(smallestSelected);
        selected.sort(descending);
        return new Bounded<>(List.copyOf(selected), total[0]);
    }

    /** Keeps the smallest {@code limit} comparable elements in natural order, counting every element seen. */
    static <T extends Comparable<? super T>> Bounded<T> boundedSorted(Stream<T> values, int limit) {
        TreeSet<T> selected = new TreeSet<>();
        int[] total = {0};
        values.forEach(value -> {
            total[0]++;
            if (selected.size() < limit || value.compareTo(selected.last()) < 0) {
                selected.add(value);
                if (selected.size() > limit) selected.pollLast();
            }
        });
        return new Bounded<>(List.copyOf(selected), total[0]);
    }

    /** Keeps the first {@code limit} elements in encounter order, counting every element seen. */
    static <T> Bounded<T> boundedFirst(Stream<T> values, int limit) {
        List<T> selected = new ArrayList<>();
        int[] total = {0};
        values.forEach(value -> {
            total[0]++;
            if (selected.size() < limit) selected.add(value);
        });
        return new Bounded<>(List.copyOf(selected), total[0]);
    }

    static List<Map<String, Object>> nodeMaps(Collection<? extends Node> nodes, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Node node : nodes) {
            if (out.size() >= limit) {
                break;
            }
            out.add(nodeMap(node));
        }
        return out;
    }

    static Map<String, Object> actionMap(Action action) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("uuid", action.getSource().getUniqueId().toString());
        source.put("name", action.getSource().getName());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("uuid", action.getTarget().getUniqueId().map(UUID::toString).orElse(null));
        target.put("name", action.getTarget().getName());
        target.put("type", action.getTarget().getType().name());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", action.getTimestamp().toString());
        out.put("source", source);
        out.put("target", target);
        out.put("description", action.getDescription());
        return out;
    }

    static void audit(LuckPerms api, String callerId, String description, PermissionHolder holder) {
        audit(api, callerId, description,
                holder instanceof User ? Action.Target.Type.USER : Action.Target.Type.GROUP,
                holder.getFriendlyName(),
                holder instanceof User user ? user.getUniqueId() : null);
    }

    static void audit(LuckPerms api, String callerId, String description,
                      Action.Target.Type type, String targetName, UUID targetUuid) {
        Action action = api.getActionLogger().actionBuilder()
                .source(AUDIT_SOURCE)
                .sourceName("EdenMcp")
                .targetType(type)
                .target(targetUuid)
                .targetName(targetName)
                .description("[" + callerId + "] " + description)
                .build();
        await(api.getActionLogger().submit(action), "submitAction");
    }
}
