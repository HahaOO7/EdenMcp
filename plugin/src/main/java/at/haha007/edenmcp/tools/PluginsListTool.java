package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;

public final class PluginsListTool implements McpTool {
    private final int maxEntries;

    public PluginsListTool(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override public String name() { return "edenmcp.plugins.list"; }
    @Override public String description() { return "List installed plugins and enabled state."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("plugins"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("limit",
                Map.of("type", "integer", "minimum", 1, "maximum", maxEntries,
                        "description", "Maximum number of plugins to return (default " + maxEntries + ")")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        try {
            limit = ToolArgs.integer(context.arguments(), "limit", maxEntries, 1, maxEntries);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Plugin p : plugins) {
            if (list.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("enabled", p.isEnabled());
            m.put("version", p.getPluginMeta().getVersion());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("total", plugins.length);
        out.put("hasMore", plugins.length > list.size());
        out.put("plugins", list);
        return ToolResult.ok(out);
    }
}
