package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.List;
import java.util.Map;

public final class HooksListTool implements McpTool {
    private final HookManager hookManager;

    public HooksListTool(HookManager hookManager) {
        this.hookManager = hookManager;
    }

    @Override public String name() { return "eden.hooks.list"; }
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
