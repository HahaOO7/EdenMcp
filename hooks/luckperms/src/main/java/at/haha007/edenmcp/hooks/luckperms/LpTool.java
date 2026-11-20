package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;

import java.util.List;
import java.util.Map;

/** Base class for the LuckPerms tools, with shared classification and error handling. */
abstract class LpTool implements McpTool {
    private final CapabilityLevel level;
    private final SideEffects sideEffects;

    protected LpTool() {
        this(CapabilityLevel.SAFE_READ, SideEffects.NONE);
    }

    protected LpTool(CapabilityLevel level, SideEffects sideEffects) {
        this.level = level;
        this.sideEffects = sideEffects;
    }

    @Override
    public final CapabilityLevel level() {
        return level;
    }

    @Override
    public final SideEffects sideEffects() {
        return sideEffects;
    }

    @Override
    public final List<String> tags() {
        return List.of("luckperms", "permissions");
    }

    @Override
    public final ToolResult execute(ToolContext context) {
        Map<String, Object> args = context.arguments() == null ? Map.of() : context.arguments();
        try {
            return run(LpSupport.api(), context, args);
        } catch (LpSupport.InvalidArgument e) {
            return ToolResult.error(e.getMessage());
        } catch (Throwable t) {
            String message = t.getMessage();
            return ToolResult.error("LuckPerms error: " + (message == null ? t.getClass().getSimpleName() : message));
        }
    }

    protected abstract ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception;
}
