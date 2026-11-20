package at.haha007.edenmcp.core.config;

import at.haha007.edenmcp.api.*;

import at.haha007.edenmcp.api.CapabilityLevel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void nestedQuantifierAndQuantifiedAlternationRulesAreRejected() {
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> CommandClassification.Rule.of(CapabilityLevel.MODERATE_ACTION, "^(a+)+$"));
        assertTrue(nested.getMessage().contains("nested quantifier"), nested.getMessage());
        assertTrue(nested.getMessage().contains("^(a+)+$"), nested.getMessage());
        assertTrue(nested.getMessage().contains("MODERATE_ACTION"), nested.getMessage());

        IllegalArgumentException alternation = assertThrows(IllegalArgumentException.class,
                () -> CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "^(a|aa)+$"));
        assertTrue(alternation.getMessage().contains("quantified alternation"), alternation.getMessage());
        assertTrue(alternation.getMessage().contains("^(a|aa)+$"), alternation.getMessage());

        // Escaped quantifiers and character classes are literals, not complexity hazards.
        assertDoesNotThrow(() -> CommandClassification.Rule.of(CapabilityLevel.SAFE_READ, "^a\\+[+*]+$"));
    }

    @Test
    void configParseRejectsBacktrackingRuleAndNamesIt() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("commands.classification",
                List.of(Map.of("level", "moderate", "pattern", "^(a+)+$")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EdenConfig.from(cfg, null));
        assertTrue(error.getMessage().contains("^(a+)+$"), error.getMessage());
        assertTrue(error.getMessage().contains("nested quantifier"), error.getMessage());
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
