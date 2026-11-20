package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class StatusTool implements McpTool {
    private final Plugin plugin;
    private final int maxWorlds;
    private final int syncTimeoutSeconds;

    public StatusTool(Plugin plugin, int maxWorlds, int syncTimeoutSeconds) {
        this.plugin = plugin;
        this.maxWorlds = Math.max(1, maxWorlds);
        this.syncTimeoutSeconds = syncTimeoutSeconds;
    }

    @Override public String name() { return "edenmcp.server.status"; }
    @Override public String description() { return "Server status: version, TPS/MSPT, players, uptime, worlds."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("status"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("worldLimit",
                Map.of("type", "integer", "minimum", 1, "maximum", maxWorlds,
                        "description", "Maximum number of worlds to return (default " + maxWorlds + ")")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        try {
            limit = ToolArgs.integer(context.arguments(), "worldLimit", maxWorlds, 1, maxWorlds);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        try {
            Map<String, Object> out = runSync(() -> {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("version", Bukkit.getVersion());
                snapshot.put("bukkitVersion", Bukkit.getBukkitVersion());
                snapshot.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
                snapshot.put("maxPlayers", Bukkit.getMaxPlayers());
                snapshot.put("uptimeMs", uptimeMs);
                try {
                    double[] tps = Bukkit.getTPS();
                    snapshot.put("tps", Arrays.asList(tps.length > 0 ? tps[0] : null, tps.length > 1 ? tps[1] : null, tps.length > 2 ? tps[2] : null));
                } catch (Throwable t) {
                    snapshot.put("tps", null);
                }
                try {
                    snapshot.put("mspt", Bukkit.getAverageTickTime());
                } catch (Throwable t) {
                    snapshot.put("mspt", null);
                }
                List<World> worlds = Bukkit.getWorlds();
                List<Map<String, Object>> listed = new ArrayList<>();
                for (World w : worlds) {
                    if (listed.size() >= limit) break;
                    Map<String, Object> wm = new LinkedHashMap<>();
                    wm.put("name", w.getName());
                    wm.put("players", w.getPlayers().size());
                    wm.put("loadedChunks", w.getLoadedChunks().length);
                    listed.add(wm);
                }
                snapshot.put("worlds", listed);
                snapshot.put("worldCount", worlds.size());
                snapshot.put("worldsTruncated", worlds.size() > listed.size());
                return snapshot;
            });
            return ToolResult.ok(out);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResult.error("Server status timed out");
        } catch (Exception e) {
            return ToolResult.error("Server status failed");
        }
    }

    private <T> T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    }
}
