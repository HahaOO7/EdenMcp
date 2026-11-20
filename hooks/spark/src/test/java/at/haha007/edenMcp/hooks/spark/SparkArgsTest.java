package at.haha007.edenMcp.hooks.spark;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkArgsTest {
    @Test
    void textIsTrimmedAndRestrictedToSafeCharacters() {
        assertEquals("hello world", SparkArgs.optionalText(Map.of("comment", "  hello world  ")));
        assertNull(SparkArgs.optionalText(Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalText(Map.of("comment", "x$(id)")));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalText(Map.of("comment", "x\"y")));
    }

    @Test
    void threadNamesAllowWildcardsButNotShellSyntax() {
        assertEquals("Server thread", SparkArgs.optionalThread(Map.of("thread", "Server thread")));
        assertEquals("me.lucko.*", SparkArgs.optionalThread(Map.of("thread", "me.lucko.*")));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalThread(Map.of("thread", "bad{thread}")));
    }

    @Test
    void integersMustBeNumericAndWithinBounds() {
        assertEquals(15, SparkArgs.integer(Map.of("timeoutSeconds", "15"), "timeoutSeconds", null, 11, 3600));
        assertEquals(11, SparkArgs.integer(Map.of(), "timeoutSeconds", 11, 11, 3600));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", 10), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", 3601), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", "abc"), "timeoutSeconds", null, 11, 3600));
    }

    @Test
    void booleansAcceptCommonRepresentationsOnly() {
        assertTrue(SparkArgs.bool(Map.of("flag", "yes"), "flag", false));
        assertTrue(SparkArgs.bool(Map.of("flag", "1"), "flag", false));
        assertTrue(SparkArgs.bool(Map.of("flag", true), "flag", false));
        assertFalse(SparkArgs.bool(Map.of("flag", "no"), "flag", true));
        assertFalse(SparkArgs.bool(Map.of(), "flag", false));

        assertThrows(IllegalArgumentException.class, () -> SparkArgs.bool(Map.of("flag", "maybe"), "flag", false));
    }

    @Test
    void enumerationsAreCaseInsensitiveAndAllowlisted() {
        assertEquals("cpu", SparkArgs.enumeration(Map.of("mode", "CPU"), "mode", "cpu", Set.of("cpu", "alloc")));
        assertEquals("cpu", SparkArgs.enumeration(Map.of(), "mode", "cpu", Set.of("cpu", "alloc")));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.enumeration(Map.of("mode", "upload"), "mode", "cpu", Set.of("cpu", "alloc")));
    }

    @Test
    void quoteRemovesDoubleQuotesFromAlreadyValidatedText() {
        assertEquals("\"hello world\"", SparkArgs.quote("hello world"));
        assertEquals("\"ab\"", SparkArgs.quote("a\"b"));
    }
}
