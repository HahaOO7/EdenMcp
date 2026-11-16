package at.haha007.edenMcp.core.tool;

import at.haha007.edenMcp.core.mode.OperatingMode;

import java.util.Map;

public record ToolContext(
        OperatingMode activeMode,
        String callerId,
        Map<String, Object> arguments
) {
}
