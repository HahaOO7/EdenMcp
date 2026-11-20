package at.haha007.edenmcp.api;

import java.util.Map;

/** Per-call context passed to {@link McpTool#execute(ToolContext)}. */
public record ToolContext(
        OperatingMode activeMode,
        String callerId,
        Map<String, Object> arguments
) {
}
