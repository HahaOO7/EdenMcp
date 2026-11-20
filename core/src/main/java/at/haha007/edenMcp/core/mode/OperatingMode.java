package at.haha007.edenMcp.core.mode;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import org.jetbrains.annotations.Nullable;

public enum OperatingMode {
    SAFE,
    MODERATE,
    UNSAFE;

    public static @Nullable OperatingMode parse(@Nullable String value, @Nullable OperatingMode fallback) {
        if (value == null) return fallback;
        try {
            return OperatingMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public boolean allows(CapabilityLevel level) {
        return switch (this) {
            case SAFE -> level == CapabilityLevel.SAFE_READ;
            case MODERATE -> level == CapabilityLevel.SAFE_READ || level == CapabilityLevel.MODERATE_ACTION;
            case UNSAFE -> true;
        };
    }
}
