package at.haha007.edenmcp.tools;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolArgsTest {
    private static Map<String, Object> args(Object value) {
        Map<String, Object> args = new HashMap<>();
        args.put("value", value);
        return args;
    }

    @Test
    void integerAcceptsOnlyIntegralNumbers() {
        assertEquals(7, ToolArgs.integer(args(7), "value", 0, 0, 10));
        assertEquals(7, ToolArgs.integer(args(7L), "value", 0, 0, 10));
        assertEquals(7, ToolArgs.integer(args(7.0d), "value", 0, 0, 10));

        for (Object invalid : new Object[]{"7", " 7 ", true, 7.5d, Double.NaN, Double.POSITIVE_INFINITY}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ToolArgs.integer(args(invalid), "value", 0, 0, 10), String.valueOf(invalid));
            assertEquals("Invalid 'value'", e.getMessage());
        }
    }

    @Test
    void integerKeepsFallbackAndBoundsChecking() {
        assertEquals(3, ToolArgs.integer(Map.of(), "value", 3, 0, 10));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolArgs.integer(args(11), "value", 0, 0, 10));
        assertEquals("'value' must be between 0 and 10", e.getMessage());
    }

    @Test
    void boolAcceptsOnlyBooleans() {
        assertTrue(ToolArgs.bool(args(true), "value"));
        assertFalse(ToolArgs.bool(args(false), "value"));
        assertFalse(ToolArgs.bool(Map.of(), "value"));

        for (Object invalid : new Object[]{"true", "yes", "1", "false", "no", "0", 1, 0}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ToolArgs.bool(args(invalid), "value"), String.valueOf(invalid));
            assertEquals("Invalid 'value'", e.getMessage());
        }
    }

    @Test
    void stringsRemainStrict() {
        assertEquals("x", ToolArgs.string(args("x"), "value"));
        assertThrows(IllegalArgumentException.class, () -> ToolArgs.string(args(1), "value"));

        assertEquals("x", ToolArgs.requiredString(args("x"), "value"));
        assertThrows(IllegalArgumentException.class, () -> ToolArgs.requiredString(args("  "), "value"));
        assertThrows(IllegalArgumentException.class, () -> ToolArgs.requiredString(args(7), "value"));
    }
}
