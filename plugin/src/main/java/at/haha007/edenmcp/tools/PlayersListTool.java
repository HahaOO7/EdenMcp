package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class PlayersListTool implements McpTool {
    private final int maxEntries;

    public PlayersListTool(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override public String name() { return "edenmcp.players.list"; }
    @Override public String description() { return "List online players and available metadata."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("players"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("limit",
                Map.of("type", "integer", "minimum", 1, "maximum", maxEntries,
                        "description", "Maximum number of players to return (default " + maxEntries + ")")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        try {
            limit = ToolArgs.integer(context.arguments(), "limit", maxEntries, 1, maxEntries);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : players) {
            if (list.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            m.put("world", p.getWorld().getName());
            m.put("health", p.getHealth());
            m.put("ping", tryPing(p));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("total", players.size());
        out.put("hasMore", players.size() > list.size());
        out.put("players", list);
        return ToolResult.ok(out);
    }

    private @Nullable Object tryPing(Player p) {
        try { return p.getPing(); } catch (Throwable t) { return null; }
    }
}
