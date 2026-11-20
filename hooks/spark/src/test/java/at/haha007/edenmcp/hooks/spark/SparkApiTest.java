package at.haha007.edenmcp.hooks.spark;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SparkApiTest {
    private static final Map<String, String> ALIASES = Map.of("10s", "SECONDS_10");
    private static final Set<String> ALLOWED = Set.of("SECONDS_10", "MINUTES_1");

    @Test
    void windowResolutionAcceptsStringsOnly() {
        assertEquals("SECONDS_10", SparkApi.Windows.resolve(Map.of("window", "10s"), ALIASES, ALLOWED));
        assertEquals("MINUTES_1", SparkApi.Windows.resolve(Map.of("window", "minutes-1"), ALIASES, ALLOWED));
        assertNull(SparkApi.Windows.resolve(Map.of(), ALIASES, ALLOWED));
    }

    @Test
    void windowResolutionRejectsCoercedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> SparkApi.Windows.resolve(Map.of("window", 10), ALIASES, ALLOWED));
        assertThrows(IllegalArgumentException.class,
                () -> SparkApi.Windows.resolve(Map.of("window", true), ALIASES, ALLOWED));
        assertThrows(IllegalArgumentException.class,
                () -> SparkApi.Windows.resolve(Map.of("window", "HOURS_1"), ALIASES, ALLOWED));
    }
}
