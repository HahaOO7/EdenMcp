package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;

import java.util.List;
import java.util.Map;

/** Base class for the read-only LuckPerms tools. */
abstract class LpTool implements McpTool {
    @Override
    public final CapabilityLevel level() {
        return CapabilityLevel.SAFE_READ;
    }

    @Override
    public final SideEffects sideEffects() {
        return SideEffects.NONE;
    }

    @Override
    public final List<String> tags() {
        return List.of("luckperms", "permissions");
    }

    @Override
    public final ToolResult execute(ToolContext context) {
        try {
            return run(LpSupport.api(), context.arguments() == null ? Map.of() : context.arguments());
        } catch (LpSupport.InvalidArgument e) {
            return ToolResult.error(e.getMessage());
        } catch (Throwable t) {
            String message = t.getMessage();
            return ToolResult.error("LuckPerms error: " + (message == null ? t.getClass().getSimpleName() : message));
        }
    }

    protected abstract ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception;
}
