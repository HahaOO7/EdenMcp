package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HooksListTool implements McpTool {
    private final HookManager hookManager;
    private final int maxEntries;

    public HooksListTool(HookManager hookManager, int maxEntries) {
        this.hookManager = hookManager;
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override public String name() { return "edenmcp.hooks.list"; }
    @Override public String description() { return "List server-provided hooks and their availability/enabled state."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("hooks"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("limit",
                Map.of("type", "integer", "minimum", 1, "maximum", maxEntries,
                        "description", "Maximum number of hooks to return (default " + maxEntries + ")")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        try {
            limit = ToolArgs.integer(context.arguments(), "limit", maxEntries, 1, maxEntries);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        List<Map<String, Object>> all = hookManager.describeHooks();
        List<Map<String, Object>> hooks = all.size() > limit ? all.subList(0, limit) : all;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", hooks.size());
        out.put("total", all.size());
        out.put("hasMore", all.size() > hooks.size());
        out.put("hooks", hooks);
        return ToolResult.ok(out);
    }
}
