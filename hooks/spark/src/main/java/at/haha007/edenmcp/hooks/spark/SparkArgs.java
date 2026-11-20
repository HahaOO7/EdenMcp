package at.haha007.edenmcp.hooks.spark;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict argument parsing for Spark tools: commands are built from allowlisted values only. */
final class SparkArgs {
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9 _.,;:!?/#+='()\\[\\]-]{1,200}");
    private static final Pattern THREAD = Pattern.compile("[A-Za-z0-9_*.$|()\\[\\]+?\\\\ -]{1,120}");

    private SparkArgs() {
    }

    static @Nullable String string(Map<String, Object> args, String key, Pattern pattern) {
        Object value = args.get(key);
        if (value == null) return null;
        // JSON strings must actually be strings; never turn another JSON type into command text.
        if (!(value instanceof String raw)) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        String text = raw.trim();
        if (!pattern.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        return text;
    }

    static @Nullable String optionalText(Map<String, Object> args) {
        return string(args, "comment", TEXT);
    }

    static @Nullable String optionalThread(Map<String, Object> args) {
        return string(args, "thread", THREAD);
    }

    @Contract("_, _, !null, _, _ -> !null")
    static @Nullable Integer integer(Map<String, Object> args, String key, @Nullable Integer fallback,
                                     int min, int max) {
        Object value = args.get(key);
        if (value == null) return fallback;
        long parsed = integral(value, key);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("'" + key + "' must be between " + min + " and " + max);
        }
        return (int) parsed;
    }

    static long offset(Map<String, Object> args) {
        Object value = args.get("offset");
        if (value == null) return 0L;
        long parsed = integral(value, "offset");
        if (parsed < 0) {
            throw new IllegalArgumentException("'offset' must be >= 0");
        }
        return parsed;
    }

    static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException("Invalid '" + key + "'");
    }

    @Contract("_, _, !null, _ -> !null")
    static @Nullable String enumeration(Map<String, Object> args, String key, @Nullable String fallback,
                                        Set<String> allowed) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String raw)) {
            throw new IllegalArgumentException("Invalid '" + key + "'; allowed: " + String.join(", ", allowed));
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(text)) {
            throw new IllegalArgumentException("Invalid '" + key + "'; allowed: " + String.join(", ", allowed));
        }
        return text;
    }

    /** Requires a real JSON number with an integral value; strings and booleans are rejected. */
    private static long integral(Object value, String key) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
    }

    /** Quotes a pre-validated value so Bukkit's dispatcher cannot split it into extra arguments. */
    static String quote(String value) {
        return "\"" + value.replace("\"", "") + "\"";
    }
}
