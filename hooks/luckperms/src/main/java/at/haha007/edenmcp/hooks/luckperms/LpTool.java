package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Base class for the LuckPerms tools, with shared classification and error handling. */
abstract class LpTool implements McpTool {
    private static final Logger LOG = Logger.getLogger(LpTool.class.getName());
    static final String TIMEOUT_MESSAGE = "LuckPerms operation timed out";
    static final String GENERIC_MESSAGE = "LuckPerms error";

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
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    /** Maps failures to safe caller-facing messages; details are only logged server-side. */
    static ToolResult errorResult(Exception e) {
        if (e instanceof LpSupport.InvalidArgument invalid) {
            return ToolResult.error(invalid.getMessage());
        }
        if (e instanceof LpSupport.LuckPermsTimeoutException) {
            LOG.log(Level.WARNING, "LuckPerms operation timed out", e);
            return ToolResult.error(TIMEOUT_MESSAGE);
        }
        LOG.log(Level.WARNING, "Unexpected LuckPerms tool failure", e);
        return ToolResult.error(GENERIC_MESSAGE);
    }

    protected abstract ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception;
}
