package at.haha007.edenmcp.hooks.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.query.QueryOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for the LuckPerms tools. */
final class LpSupport {
    static final int MAX_NODES = 200;
    static final int MAX_AUDIT = 200;

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

    static String stringArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    static String requiredArg(Map<String, Object> args, String key) throws InvalidArgument {
        String value = stringArg(args, key);
        if (value == null || value.isEmpty()) {
            throw new InvalidArgument("Provide " + key);
        }
        return value;
    }

    static int boundedLimitArg(Map<String, Object> args, int defaultValue, int max) throws InvalidArgument {
        String value = stringArg(args, "limit");
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidArgument("Invalid limit");
        }
        if (parsed < 1 || parsed > max) {
            throw new InvalidArgument("limit must be between 1 and " + max);
        }
        return parsed;
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
            uuid = api.getUserManager().lookupUniqueId(username).join();
        }
        if (uuid == null) {
            throw new InvalidArgument("Provide username or uuid");
        }
        User user = api.getUserManager().loadUser(uuid).join();
        if (user == null) {
            throw new InvalidArgument("User not found");
        }
        return user;
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

    /** Normalizes the optional {@code contexts} argument without needing a LuckPerms instance. */
    static Map<String, String> contextPairs(Object raw) throws InvalidArgument {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidArgument("contexts must be an object");
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                pairs.put(key, value);
            }
        }
        return pairs;
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
}
