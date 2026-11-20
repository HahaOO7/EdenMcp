package at.haha007.edenMcp.core.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class ConfigRedactor {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "key", "token", "secret", "password", "credential", "auth", "private", "keystore", "passphrase"
    );
    private static final Set<String> DENYLIST_PATHS = Set.of(
            "mcp.auth", "mcp.auth.key", "auth", "auth.key"
    );
    private static final String REDACTED = "[REDACTED]";
    private static final String PRESENCE_ONLY = "[PRESENT]";

    private ConfigRedactor() {}

    @Contract("!null, _ -> !null")
    public static @Nullable Object redact(@Nullable Object value, @Nullable String path) {
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (isDeniedPath(lowerPath)) {
            return redactDeniedValue(value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                String childPath = path == null || path.isEmpty() ? k : path + "." + k;
                if (isSensitiveKeyName(k)) {
                    out.put(k, redactSensitiveLeaf(e.getValue()));
                } else {
                    out.put(k, redact(e.getValue(), childPath));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(redact(list.get(i), path + "[" + i + "]"));
            }
            return out;
        }
        // heuristic on key name already handled at map level; if path itself sensitive
        String leaf = leafName(lowerPath);
        if (isSensitiveKeyName(leaf)) {
            return redactSensitiveLeaf(value);
        }
        return value;
    }

    private static boolean isDeniedPath(String path) {
        for (String d : DENYLIST_PATHS) {
            if (path.equals(d) || path.startsWith(d + ".")) return true;
        }
        return false;
    }

    private static Object redactDeniedValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Map.of("status", PRESENCE_ONLY);
        }
        if (value == null) return null;
        return PRESENCE_ONLY;
    }

    private static boolean isSensitiveKeyName(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_KEYS) {
            if (k.equals(s) || k.contains(s)) return true;
        }
        return false;
    }

    private static Object redactSensitiveLeaf(Object value) {
        if (value == null) return null;
        if (value instanceof String s && s.isBlank()) return "";
        // report presence/status only for secret stores; mask simple values
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Map.of("status", PRESENCE_ONLY);
        }
        return REDACTED;
    }

    private static String leafName(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
