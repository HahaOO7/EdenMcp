package at.haha007.edenMcp.core.tool;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public interface McpTool {
    String name();

    String description();

    CapabilityLevel level();

    SideEffects sideEffects();

    List<String> tags();

    /**
     * JSON-schema-like map describing arguments for MCP advertisement.
     */
    @Nullable Map<String, Object> inputSchema();

    ToolResult execute(ToolContext context) throws Exception;

    default boolean exposed() {
        return true;
    }
}
