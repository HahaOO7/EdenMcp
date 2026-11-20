package at.haha007.edenmcp.core.config;

import at.haha007.edenmcp.api.CapabilityLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

public record CommandClassification(List<Rule> rules) {
    private static final Pattern BRACE_QUANTIFIER = Pattern.compile("\\{\\d+(?:,\\d*)?}");

    public CommandClassification {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Rules are always anchored to the whole normalized command; first match wins. */
    public record Rule(CapabilityLevel level, Pattern pattern, String rawPattern) {
        // The group is required to anchor alternation in user-supplied patterns.
        @SuppressWarnings("RegExpUnnecessaryNonCapturingGroup")
        public static Rule of(CapabilityLevel level, @Nullable String rawPattern) {
            String raw = rawPattern == null ? "" : rawPattern;
            validateComplexity(level, raw);
            String effective = (raw.startsWith("^") && raw.endsWith("$")) ? raw : "^(?:" + raw + ")$";
            return new Rule(level, Pattern.compile(effective), raw);
        }

        public boolean isAnchored() {
            return rawPattern.startsWith("^") && rawPattern.endsWith("$");
        }
    }

    /**
     * Rejects patterns with constructs that can backtrack catastrophically on every dispatch.
     * This is a deliberately lightweight, linear scan of the raw rule text: it does not try to
     * be a full regex analyzer, it only catches the shapes that are both common in admin rules
     * and known to be pathological (quantified groups that themselves contain a quantifier or
     * an alternation, e.g. {@code ^(a+)+$} or {@code ^(a|aa)+$}).
     */
    private static void validateComplexity(CapabilityLevel level, String raw) {
        Deque<GroupState> groups = new ArrayDeque<>();
        GroupState lastClosed = null;
        boolean lastWasGroupClose = false;
        boolean escaped = false;
        boolean inClass = false;
        int lastOpenIndex = -1;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                lastWasGroupClose = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '[') {
                inClass = true;
                lastWasGroupClose = false;
                continue;
            }
            if (c == '(') {
                groups.push(new GroupState());
                lastOpenIndex = i;
                lastWasGroupClose = false;
                continue;
            }
            if (c == ')' && !groups.isEmpty()) {
                lastClosed = groups.pop();
                lastWasGroupClose = true;
                continue;
            }
            if (c == '|') {
                if (!groups.isEmpty()) groups.peek().hasAlternation = true;
                lastWasGroupClose = false;
                continue;
            }
            boolean quantifier = c == '+' || c == '*'
                    || (c == '?' && lastOpenIndex != i - 1) // (?:, (?=, (?<= ... are group syntax
                    || (c == '{' && isBraceQuantifier(raw, i));
            if (!quantifier) {
                lastWasGroupClose = false;
                continue;
            }
            if (lastWasGroupClose && lastClosed != null) {
                if (lastClosed.hasQuantifier) {
                    rejectRule(level, raw, "nested quantifier on a repeated group");
                }
                if (lastClosed.hasAlternation) {
                    rejectRule(level, raw, "quantified alternation");
                }
            }
            if (!groups.isEmpty()) groups.peek().hasQuantifier = true;
            lastWasGroupClose = false;
        }
    }

    private static boolean isBraceQuantifier(String raw, int index) {
        return BRACE_QUANTIFIER.matcher(raw).region(index, raw.length()).lookingAt();
    }

    private static void rejectRule(CapabilityLevel level, String raw, String reason) {
        throw new IllegalArgumentException("Command classification rule [level=" + level
                + ", pattern='" + raw + "'] rejected: " + reason
                + " (catastrophic backtracking risk)");
    }

    private static final class GroupState {
        private boolean hasQuantifier;
        private boolean hasAlternation;
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
