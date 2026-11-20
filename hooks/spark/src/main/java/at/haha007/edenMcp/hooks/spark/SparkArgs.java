package at.haha007.edenMcp.hooks.spark;

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

    static String string(Map<String, Object> args, String key, Pattern pattern) {
        Object value = args.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (!pattern.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        return text;
    }

    static String optionalText(Map<String, Object> args) {
        return string(args, "comment", TEXT);
    }

    static String optionalThread(Map<String, Object> args) {
        return string(args, "thread", THREAD);
    }

    static Integer integer(Map<String, Object> args, String key, Integer fallback, int min, int max) {
        Object value = args.get(key);
        if (value == null) return fallback;
        long parsed;
        try {
            parsed = Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid '" + key + "'");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("'" + key + "' must be between " + min + " and " + max);
        }
        return (int) parsed;
    }

    static long offset(Map<String, Object> args) {
        Object value = args.get("offset");
        if (value == null) return 0L;
        long parsed;
        try {
            parsed = Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid 'offset'");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("'offset' must be >= 0");
        }
        return parsed;
    }

    static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("yes") || text.equals("1")) return true;
        if (text.equals("false") || text.equals("no") || text.equals("0")) return false;
        throw new IllegalArgumentException("Invalid '" + key + "'");
    }

    static String enumeration(Map<String, Object> args, String key, String fallback, Set<String> allowed) {
        Object value = args.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(text)) {
            throw new IllegalArgumentException("Invalid '" + key + "'; allowed: " + String.join(", ", allowed));
        }
        return text;
    }

    /** Quotes a pre-validated value so Bukkit's dispatcher cannot split it into extra arguments. */
    static String quote(String value) {
        return "\"" + value.replace("\"", "") + "\"";
    }
}
