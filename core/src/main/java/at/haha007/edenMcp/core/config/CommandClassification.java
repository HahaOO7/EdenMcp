package at.haha007.edenMcp.core.config;

import at.haha007.edenMcp.core.capability.CapabilityLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CommandClassification {
    public record Rule(CapabilityLevel level, Pattern pattern, String rawPattern) {}

    private final List<Rule> rules;

    public CommandClassification(List<Rule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public CapabilityLevel classify(String command) {
        if (command == null) return CapabilityLevel.UNSAFE_ACTION;
        String cmd = command.stripLeading();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        // Ordered rules, first match wins. Prefer full-string match (anchored patterns recommended).
        for (Rule rule : rules) {
            try {
                if (rule.pattern().matcher(cmd).matches()) {
                    return rule.level();
                }
            } catch (Exception ignored) {
            }
        }
        // Lenient fallback for non-anchored patterns: first find() wins in order.
        for (Rule rule : rules) {
            try {
                if (rule.pattern().matcher(cmd).find()) {
                    return rule.level();
                }
            } catch (Exception ignored) {
            }
        }
        return CapabilityLevel.UNSAFE_ACTION;
    }

    public List<Rule> rules() { return rules; }

    public static CommandClassification empty() { return new CommandClassification(List.of()); }
}
