package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.management.ManagementFactory;
import java.util.*;

public final class StatusTool implements McpTool {
    @Override public String name() { return "eden.server.status"; }
    @Override public String description() { return "Server status: version, TPS/MSPT, players, uptime, worlds."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("status"); }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", Bukkit.getVersion());
        out.put("bukkitVersion", Bukkit.getBukkitVersion());
        out.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        out.put("maxPlayers", Bukkit.getMaxPlayers());
        out.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        try {
            double[] tps = Bukkit.getTPS();
            out.put("tps", List.of(tps.length > 0 ? tps[0] : null, tps.length > 1 ? tps[1] : null, tps.length > 2 ? tps[2] : null));
        } catch (Throwable t) {
            out.put("tps", null);
        }
        try {
            out.put("mspt", Bukkit.getAverageTickTime());
        } catch (Throwable t) {
            out.put("mspt", null);
        }
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("name", w.getName());
            wm.put("players", w.getPlayers().size());
            wm.put("loadedChunks", w.getLoadedChunks().length);
            worlds.add(wm);
        }
        out.put("worlds", worlds);
        return ToolResult.ok(out);
    }
}
