package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.management.ManagementFactory;
import java.util.*;

public final class StatusTool implements McpTool {
    private final int maxWorlds;

    public StatusTool(int maxWorlds) {
        this.maxWorlds = Math.max(1, maxWorlds);
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", Bukkit.getVersion());
        out.put("bukkitVersion", Bukkit.getBukkitVersion());
        out.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        out.put("maxPlayers", Bukkit.getMaxPlayers());
        out.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        try {
            double[] tps = Bukkit.getTPS();
            out.put("tps", Arrays.asList(tps.length > 0 ? tps[0] : null, tps.length > 1 ? tps[1] : null, tps.length > 2 ? tps[2] : null));
        } catch (Throwable t) {
            out.put("tps", null);
        }
        try {
            out.put("mspt", Bukkit.getAverageTickTime());
        } catch (Throwable t) {
            out.put("mspt", null);
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
        out.put("worlds", listed);
        out.put("worldCount", worlds.size());
        out.put("worldsTruncated", worlds.size() > listed.size());
        return ToolResult.ok(out);
    }
}
