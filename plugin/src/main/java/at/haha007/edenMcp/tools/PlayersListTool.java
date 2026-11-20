package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class PlayersListTool implements McpTool {
    @Override public String name() { return "eden.players.list"; }
    @Override public String description() { return "List online players and available metadata."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("players"); }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override
    public ToolResult execute(ToolContext context) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            m.put("world", p.getWorld().getName());
            m.put("health", p.getHealth());
            m.put("ping", tryPing(p));
            list.add(m);
        }
        return ToolResult.ok(Map.of("count", list.size(), "players", list));
    }

    private @Nullable Object tryPing(Player p) {
        try { return p.getPing(); } catch (Throwable t) { return null; }
    }
}
