package at.haha007.edenmcp.hooks.spark;

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
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalText(Map.of("comment", 123)));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalText(Map.of("comment", true)));
    }

    @Test
    void threadNamesAllowWildcardsButNotShellSyntax() {
        assertEquals("Server thread", SparkArgs.optionalThread(Map.of("thread", "Server thread")));
        assertEquals("me.lucko.*", SparkArgs.optionalThread(Map.of("thread", "me.lucko.*")));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalThread(Map.of("thread", "bad{thread}")));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.optionalThread(Map.of("thread", 42)));
    }

    @Test
    void integersMustBeRealNumbersWithIntegralValuesAndWithinBounds() {
        assertEquals(15, SparkArgs.integer(Map.of("timeoutSeconds", 15), "timeoutSeconds", null, 11, 3600));
        assertEquals(11, SparkArgs.integer(Map.of(), "timeoutSeconds", 11, 11, 3600));
        assertEquals(30, SparkArgs.integer(Map.of("timeoutSeconds", 30L), "timeoutSeconds", null, 11, 3600));
        assertEquals(30, SparkArgs.integer(Map.of("timeoutSeconds", 30.0), "timeoutSeconds", null, 11, 3600));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", 10), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", 3601), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", "15"), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", 15.5), "timeoutSeconds", null, 11, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.integer(Map.of("timeoutSeconds", true), "timeoutSeconds", null, 11, 3600));
    }

    @Test
    void offsetsMustBeNonNegativeIntegralNumbers() {
        assertEquals(0L, SparkArgs.offset(Map.of()));
        assertEquals(7L, SparkArgs.offset(Map.of("offset", 7)));
        assertEquals(7L, SparkArgs.offset(Map.of("offset", 7.0)));

        assertThrows(IllegalArgumentException.class, () -> SparkArgs.offset(Map.of("offset", -1)));
        assertThrows(IllegalArgumentException.class, () -> SparkArgs.offset(Map.of("offset", "7")));
        assertThrows(IllegalArgumentException.class, () -> SparkArgs.offset(Map.of("offset", 1.25)));
        assertThrows(IllegalArgumentException.class, () -> SparkArgs.offset(Map.of("offset", false)));
    }

    @Test
    void booleansMustBeActualBooleans() {
        assertTrue(SparkArgs.bool(Map.of("flag", true), "flag", false));
        assertFalse(SparkArgs.bool(Map.of("flag", false), "flag", true));
        assertFalse(SparkArgs.bool(Map.of(), "flag", false));

        for (Object coerced : new Object[]{"yes", "1", "no", "0", 1, 0}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SparkArgs.bool(Map.of("flag", coerced), "flag", false));
        }
    }

    @Test
    void enumerationsAreCaseInsensitiveStringsAndAllowlisted() {
        assertEquals("cpu", SparkArgs.enumeration(Map.of("mode", "CPU"), "mode", "cpu", Set.of("cpu", "alloc")));
        assertEquals("cpu", SparkArgs.enumeration(Map.of(), "mode", "cpu", Set.of("cpu", "alloc")));

        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.enumeration(Map.of("mode", "upload"), "mode", "cpu", Set.of("cpu", "alloc")));
        assertThrows(IllegalArgumentException.class,
                () -> SparkArgs.enumeration(Map.of("mode", 1), "mode", "cpu", Set.of("cpu", "alloc")));
    }

    @Test
    void quoteRemovesDoubleQuotesFromAlreadyValidatedText() {
        assertEquals("\"hello world\"", SparkArgs.quote("hello world"));
        assertEquals("\"ab\"", SparkArgs.quote("a\"b"));
    }
}
