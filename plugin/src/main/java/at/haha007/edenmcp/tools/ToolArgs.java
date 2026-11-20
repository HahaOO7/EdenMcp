package at.haha007.edenmcp.tools;

import java.math.BigDecimal;
import java.util.Map;

/** Strict parsing of tool arguments; the transport preserves JSON types, so mismatches are errors. */
final class ToolArgs {
    private ToolArgs() {
    }

    static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key);
        if (value == null) return fallback;
        long parsed;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        try {
            // Exact conversion rejects fractional/overflowing numbers; JSON strings are never accepted.
            parsed = new BigDecimal(number.toString()).toBigIntegerExact().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("'" + key + "' must be between " + min + " and " + max);
        }
        return (int) parsed;
    }

    static boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException("Invalid '" + key + "'");
    }

    static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid '" + key + "'");
        return text;
    }

    static String requiredString(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing or invalid '" + key + "'");
        return value;
    }
}
