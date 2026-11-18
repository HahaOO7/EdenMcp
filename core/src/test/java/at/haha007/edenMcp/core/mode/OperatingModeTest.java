package at.haha007.edenMcp.core.mode;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperatingModeTest {

    @Test
    void safeModeOnlyAllowsReads() {
        assertTrue(OperatingMode.SAFE.allows(CapabilityLevel.SAFE_READ));
        assertFalse(OperatingMode.SAFE.allows(CapabilityLevel.MODERATE_ACTION));
        assertFalse(OperatingMode.SAFE.allows(CapabilityLevel.UNSAFE_ACTION));
    }

    @Test
    void moderateModeAllowsReadsAndModerateActionsOnly() {
        assertTrue(OperatingMode.MODERATE.allows(CapabilityLevel.SAFE_READ));
        assertTrue(OperatingMode.MODERATE.allows(CapabilityLevel.MODERATE_ACTION));
        assertFalse(OperatingMode.MODERATE.allows(CapabilityLevel.UNSAFE_ACTION));
    }

    @Test
    void unsafeModeAllowsEveryCapabilityLevel() {
        for (CapabilityLevel level : CapabilityLevel.values()) {
            assertTrue(OperatingMode.UNSAFE.allows(level), "UNSAFE must allow " + level);
        }
    }

    @Test
    void invalidConfigurationFallsBackWithoutEscalating() {
        assertEquals(OperatingMode.SAFE, OperatingMode.parse(null, OperatingMode.SAFE));
        assertEquals(OperatingMode.SAFE, OperatingMode.parse("", OperatingMode.SAFE));
        assertEquals(OperatingMode.SAFE, OperatingMode.parse("garbage", OperatingMode.SAFE));
        assertEquals(OperatingMode.SAFE, OperatingMode.parse("safe-but-not-really", OperatingMode.SAFE));
        assertEquals(OperatingMode.UNSAFE, OperatingMode.parse("garbage", OperatingMode.UNSAFE),
                "an invalid value resolves to the configured fallback, never a guessed mode");

        assertEquals(OperatingMode.SAFE, OperatingMode.parse("safe", OperatingMode.UNSAFE));
        assertEquals(OperatingMode.MODERATE, OperatingMode.parse(" moderate ", OperatingMode.SAFE));
        assertEquals(OperatingMode.UNSAFE, OperatingMode.parse("UNSAFE", OperatingMode.SAFE));
    }
}
