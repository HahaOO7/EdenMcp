package at.haha007.edenmcp.api;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A tool contributed by a hook.
 *
 * <p>Tool names must be namespaced as {@code edenmcp.<hookId>.<name>}. EdenMcp enforces
 * this, along with capability levels and mode gating, before a tool is advertised.
 *
 * <p>{@link #execute(ToolContext)} is invoked on an HTTP worker thread. Use
 * {@link Sync} (or your plugin's scheduler) for any Bukkit API access.
 */
public interface McpTool {
    String name();

    String description();

    CapabilityLevel level();

    SideEffects sideEffects();

    List<String> tags();

    /** JSON-schema-like map describing arguments for MCP advertisement. */
    @Nullable Map<String, Object> inputSchema();

    ToolResult execute(ToolContext context) throws Exception;

    default boolean exposed() {
        return true;
    }
}
