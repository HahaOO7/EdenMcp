package at.haha007.edenMcp.core.config;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandClassificationTest {

    @Test
    void unanchoredPatternsAreAnchoredToWholeCommand() {
        CommandClassification classification = new CommandClassification(List.of(
                CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "say")));

        assertEquals(CapabilityLevel.SAFE_READ, classification.classify("say"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("safe say"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say hello"));
    }

    @Test
    void anchoredPatternWithAlternationWorks() {
        CommandClassification classification = new CommandClassification(List.of(
                CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "^(say|list)$")));

        assertEquals(CapabilityLevel.SAFE_READ, classification.classify("say"));
        assertEquals(CapabilityLevel.SAFE_READ, classification.classify("list"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say list"));
    }

    @Test
    void commandSeparatorsAreNeverClassifiedAsSafe() {
        CommandClassification classification = new CommandClassification(List.of(
                CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "^say.*$")));

        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say hi; op Player"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say hi && op Player"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say hi\nop Player"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("say $(op Player)"));
    }

    @Test
    void normalizationCollapsesWhitespaceAndStripsSlash() {
        assertEquals("say hello", CommandClassification.normalize("  /say   hello  "));
        assertNull(CommandClassification.normalize("  "));
        assertNull(CommandClassification.normalize("/"));

        CommandClassification classification = new CommandClassification(List.of(
                CommandClassification.Rule.of(CapabilityLevel.MODERATE_ACTION, "^say hello$")));
        assertEquals(CapabilityLevel.MODERATE_ACTION, classification.classify("/say  hello"));
    }

    @Test
    void firstMatchWinsAndDefaultIsUnsafe() {
        CommandClassification classification = new CommandClassification(List.of(
                CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "^say.*$"),
                CommandClassification.Rule.of(CapabilityLevel.MODERATE_ACTION, "^say secret.*$")));

        assertEquals(CapabilityLevel.SAFE_READ, classification.classify("say secret"));
        assertEquals(CapabilityLevel.UNSAFE_ACTION, classification.classify("op Player"));
        assertTrue(CommandClassification.empty().rules().isEmpty());
    }
}
