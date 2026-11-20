package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;

import java.util.List;
import java.util.Map;

public final class HooksListTool implements McpTool {
    private final HookManager hookManager;

    public HooksListTool(HookManager hookManager) {
        this.hookManager = hookManager;
    }

    @Override public String name() { return "edenmcp.hooks.list"; }
    @Override public String description() { return "List server-provided hooks and their availability/enabled state."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("hooks"); }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override
    public ToolResult execute(ToolContext context) {
        return ToolResult.ok(Map.of("hooks", hookManager.describeHooks()));
    }
}
