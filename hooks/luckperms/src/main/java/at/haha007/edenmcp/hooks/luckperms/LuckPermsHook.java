package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Server;

import java.util.*;

public final class LuckPermsHook implements Hook {
    @Override
    public String id() {
        return "luckperms";
    }

    @Override
    public boolean isAvailable(Server server) {
        if (server.getPluginManager().getPlugin("LuckPerms") == null) return false;
        try {
            LuckPermsProvider.get();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public HookContribution contribute(HookContext ctx) {
        List<McpTool> tools = new ArrayList<>();
        tools.add(new LpUserLookupTool());
        tools.add(new LpGroupsListTool());
        // Mutations intentionally not exposed as executable tools beyond unsafe command dispatch patterns for now;
        // design states mutations are UNSAFE_ACTION if exposed. Placeholder validated.
        List<McpResource> resources = List.of(McpResource.text(
                "edenmcp://luckperms/groups", "LuckPerms groups",
                "Loaded LuckPerms groups (JSON, read-only).", "application/json",
                () -> {
                    List<String> groups = lp().getGroupManager().getLoadedGroups().stream()
                            .map(Group::getName).sorted().toList();
                    return Map.of("groups", groups, "count", groups.size());
                }));
        return HookContribution.of(tools, resources);
    }

    private static LuckPerms lp() { return LuckPermsProvider.get(); }

    private static final class LpUserLookupTool implements McpTool {
        @Override public String name() { return "edenmcp.luckperms.user.lookup"; }
        @Override public String description() { return "Lookup LuckPerms user by name/UUID (read-only)."; }
        @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
        @Override public SideEffects sideEffects() { return SideEffects.NONE; }
        @Override public List<String> tags() { return List.of("luckperms", "permissions"); }
        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "username", Map.of("type", "string"),
                    "uuid", Map.of("type", "string")
            ));
        }
        @Override
        public ToolResult execute(ToolContext context) {
            try {
                LuckPerms api = lp();
                String uuidStr = context.arguments().get("uuid") == null ? null : String.valueOf(context.arguments().get("uuid"));
                String username = context.arguments().get("username") == null ? null : String.valueOf(context.arguments().get("username"));
                UUID uuid = null;
                if (uuidStr != null && !uuidStr.isBlank()) {
                    try { uuid = UUID.fromString(uuidStr); } catch (Exception e) { return ToolResult.error("Invalid uuid"); }
                }
                if (uuid == null && username != null && !username.isBlank()) {
                    uuid = api.getUserManager().lookupUniqueId(username).join();
                }
                if (uuid == null) return ToolResult.error("Provide username or uuid");
                User user = api.getUserManager().loadUser(uuid).join();
                if (user == null) return ToolResult.error("User not found");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("uuid", user.getUniqueId().toString());
                out.put("username", user.getUsername());
                out.put("primaryGroup", user.getPrimaryGroup());
                out.put("nodes", user.getNodes().size());
                return ToolResult.ok(out);
            } catch (Throwable t) {
                return ToolResult.error("LuckPerms error: " + t.getMessage());
            }
        }
    }

    private static final class LpGroupsListTool implements McpTool {
        @Override public String name() { return "edenmcp.luckperms.groups.list"; }
        @Override public String description() { return "List LuckPerms groups (read-only)."; }
        @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
        @Override public SideEffects sideEffects() { return SideEffects.NONE; }
        @Override public List<String> tags() { return List.of("luckperms", "permissions"); }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
        @Override
        public ToolResult execute(ToolContext context) {
            try {
                LuckPerms api = lp();
                List<String> groups = api.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().toList();
                return ToolResult.ok(Map.of("groups", groups, "count", groups.size()));
            } catch (Throwable t) {
                return ToolResult.error("LuckPerms error: " + t.getMessage());
            }
        }
    }
}
