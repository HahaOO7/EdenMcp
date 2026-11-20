package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PluginsListTool implements McpTool {
    private final Plugin plugin;
    private final int maxEntries;
    private final int syncTimeoutSeconds;

    public PluginsListTool(Plugin plugin, int maxEntries, int syncTimeoutSeconds) {
        this.plugin = plugin;
        this.maxEntries = Math.max(1, maxEntries);
        this.syncTimeoutSeconds = syncTimeoutSeconds;
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
        try {
            Map<String, Object> out = runSync(() -> {
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
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("count", list.size());
                snapshot.put("total", plugins.length);
                snapshot.put("hasMore", plugins.length > list.size());
                snapshot.put("plugins", list);
                return snapshot;
            });
            return ToolResult.ok(out);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResult.error("Plugins list timed out");
        } catch (Exception e) {
            return ToolResult.error("Plugins list failed");
        }
    }

    private <T> T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    }
}
