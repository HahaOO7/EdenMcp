package at.haha007.edenmcp.core.config;

import at.haha007.edenmcp.api.CapabilityLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

public record CommandClassification(List<Rule> rules) {
    public CommandClassification {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Rules are always anchored to the whole normalized command; first match wins. */
    public record Rule(CapabilityLevel level, Pattern pattern, String rawPattern) {
        // The group is required to anchor alternation in user-supplied patterns.
        @SuppressWarnings("RegExpUnnecessaryNonCapturingGroup")
        public static Rule of(CapabilityLevel level, @Nullable String rawPattern) {
            String raw = rawPattern == null ? "" : rawPattern;
            String effective = (raw.startsWith("^") && raw.endsWith("$")) ? raw : "^(?:" + raw + ")$";
            return new Rule(level, Pattern.compile(effective), raw);
        }

        public boolean isAnchored() {
            return rawPattern.startsWith("^") && rawPattern.endsWith("$");
        }
    }

    /**
     * Normalizes a command for classification and execution. Returns null for commands that
     * contain control characters or command-separator sequences, which must never be classified
     * as safe because a prefix could otherwise hide a chained dangerous command.
     */
    public static @Nullable String normalize(@Nullable String command) {
        if (command == null) return null;
        String cmd = command.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).stripLeading();
        if (cmd.isEmpty()) return null;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
            if (c == ';' || c == '$' || c == '`') return null;
            if (i + 1 < cmd.length() && ((c == '&' && cmd.charAt(i + 1) == '&') || (c == '|' && cmd.charAt(i + 1) == '|'))) {
                return null;
            }
        }
        return cmd.replaceAll("\\s+", " ");
    }

    public CapabilityLevel classify(@Nullable String command) {
        String cmd = normalize(command);
        if (cmd == null) return CapabilityLevel.UNSAFE_ACTION;
        for (Rule rule : rules) {
            try {
                if (rule.pattern().matcher(cmd).matches()) return rule.level();
            } catch (Exception ignored) {
                // A bad rule must never downgrade a command.
            }
        }
        return CapabilityLevel.UNSAFE_ACTION;
    }

    public static CommandClassification empty() { return new CommandClassification(List.of()); }
}
