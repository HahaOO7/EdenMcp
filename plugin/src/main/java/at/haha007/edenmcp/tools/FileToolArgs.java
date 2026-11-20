package at.haha007.edenmcp.tools;

import java.util.Locale;
import java.util.Map;

/** Strict parsing of optional file-tool arguments. */
final class FileToolArgs {
    private FileToolArgs() {
    }

    static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
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

    static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("yes") || text.equals("1")) return true;
        if (text.equals("false") || text.equals("no") || text.equals("0")) return false;
        throw new IllegalArgumentException("Invalid '" + key + "'");
    }
}
