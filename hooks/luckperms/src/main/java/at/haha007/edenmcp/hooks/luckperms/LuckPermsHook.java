package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.track.Track;
import org.bukkit.Server;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

public final class LuckPermsHook implements Hook {
    @Override
    public String id() {
        return "luckperms";
    }

    @Override
    public boolean isAvailable(Server server) {
        if (server.getPluginManager().getPlugin("LuckPerms") == null) return false;
        try {
            LpSupport.api();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public HookContribution contribute(HookContext ctx) {
        List<McpTool> tools = List.of(
                new LpUserLookupTool(),
                new LpGroupsListTool(),
                new LpUserPermissionCheckTool(),
                new LpGroupGetTool(),
                new LpTracksListTool(),
                new LpTrackGetTool(),
                new LpUserGroupsTool(),
                new LpAuditReadTool()
        );
        // Mutations deliberately remain unexposed; if added later they must be UNSAFE_ACTION.
        List<McpResource> resources = List.of(McpResource.text(
                "edenmcp://luckperms/groups", "LuckPerms groups",
                "Loaded LuckPerms groups (JSON, read-only).", "application/json",
                () -> {
                    List<String> groups = LpSupport.api().getGroupManager().getLoadedGroups().stream()
                            .map(Group::getName).sorted().toList();
                    return Map.of("groups", groups, "count", groups.size());
                }));
        return HookContribution.of(tools, resources);
    }

    private static final class LpUserLookupTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.user.lookup";
        }

        @Override
        public String description() {
            return "Lookup LuckPerms user by name/UUID (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "username", Map.of("type", "string"),
                    "uuid", Map.of("type", "string")
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            User user = LpSupport.requireUser(api, args);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", user.getUniqueId().toString());
            out.put("username", user.getUsername());
            out.put("primaryGroup", user.getPrimaryGroup());
            out.put("nodes", user.getNodes().size());
            return ToolResult.ok(out);
        }
    }

    private static final class LpGroupsListTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.groups.list";
        }

        @Override
        public String description() {
            return "List LuckPerms groups (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) {
            List<String> groups = api.getGroupManager().getLoadedGroups().stream()
                    .map(Group::getName).sorted().toList();
            return ToolResult.ok(Map.of("groups", groups, "count", groups.size()));
        }
    }

    private static final class LpUserPermissionCheckTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.user.permission.check";
        }

        @Override
        public String description() {
            return "Check a user's effective permission with optional contexts (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "username", Map.of("type", "string"),
                    "uuid", Map.of("type", "string"),
                    "permission", Map.of("type", "string"),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            String permission = LpSupport.requiredArg(args, "permission");
            if (permission.length() > 256) {
                throw new LpSupport.InvalidArgument("permission is too long");
            }
            User user = LpSupport.requireUser(api, args);
            QueryOptions query = LpSupport.queryOptions(api, user, args);
            var result = user.getCachedData().getPermissionData(query).queryPermission(permission);
            Node source = result.node();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", user.getUniqueId().toString());
            out.put("username", user.getUsername());
            out.put("permission", permission);
            out.put("value", result.result().name());
            out.put("source", source == null ? null : LpSupport.nodeMap(source));
            out.put("queryMode", query.mode().name());
            out.put("contexts", query.mode() == QueryMode.CONTEXTUAL ? query.context().toMap() : Map.of());
            return ToolResult.ok(out);
        }
    }

    private static final class LpGroupGetTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.group.get";
        }

        @Override
        public String description() {
            return "Read a LuckPerms group's weight, nodes, parents and meta (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 500),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            String name = LpSupport.requiredArg(args, "name");
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_NODES, 500);
            Group group = api.getGroupManager().loadGroup(name).join()
                    .orElseThrow(() -> new LpSupport.InvalidArgument("Group not found"));
            QueryOptions query = LpSupport.groupQueryOptions(api, args);
            var meta = group.getCachedData().getMetaData(query);
            OptionalInt weight = group.getWeight();
            Collection<Node> nodes = group.getNodes();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", group.getName());
            out.put("displayName", group.getDisplayName(query));
            out.put("weight", weight.isPresent() ? weight.getAsInt() : null);
            out.put("nodeCount", nodes.size());
            out.put("nodes", LpSupport.nodeMaps(nodes, limit));
            out.put("inheritedGroups", group.getInheritedGroups(query).stream()
                    .map(Group::getName).sorted().distinct().toList());
            out.put("meta", meta.getMeta());
            out.put("prefix", meta.getPrefix());
            out.put("suffix", meta.getSuffix());
            return ToolResult.ok(out);
        }
    }

    private static final class LpTracksListTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.tracks.list";
        }

        @Override
        public String description() {
            return "List loaded LuckPerms tracks (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) {
            List<String> tracks = api.getTrackManager().getLoadedTracks().stream()
                    .map(Track::getName).sorted().toList();
            return ToolResult.ok(Map.of("tracks", tracks, "count", tracks.size()));
        }
    }

    private static final class LpTrackGetTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.track.get";
        }

        @Override
        public String description() {
            return "Read a LuckPerms track's ordered groups (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "group", Map.of("type", "string")
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            String name = LpSupport.requiredArg(args, "name");
            Track track = api.getTrackManager().loadTrack(name).join()
                    .orElseThrow(() -> new LpSupport.InvalidArgument("Track not found"));
            List<String> groups = List.copyOf(track.getGroups());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", track.getName());
            out.put("groups", groups);
            out.put("count", groups.size());

            String group = LpSupport.stringArg(args, "group");
            if (group != null && !group.isEmpty()) {
                int index = groups.indexOf(group);
                if (index < 0) {
                    throw new LpSupport.InvalidArgument("Group not on track");
                }
                out.put("index", index);
                out.put("next", index + 1 < groups.size() ? groups.get(index + 1) : null);
                out.put("previous", index > 0 ? groups.get(index - 1) : null);
            }
            return ToolResult.ok(out);
        }
    }

    private static final class LpUserGroupsTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.user.groups";
        }

        @Override
        public String description() {
            return "List a user's direct and effective LuckPerms groups (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "username", Map.of("type", "string"),
                    "uuid", Map.of("type", "string"),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            User user = LpSupport.requireUser(api, args);
            QueryOptions query = LpSupport.queryOptions(api, user, args);
            List<String> direct = user.getNodes(NodeType.INHERITANCE).stream()
                    .map(InheritanceNode::getGroupName).sorted().distinct().toList();
            List<String> effective = user.getInheritedGroups(query).stream()
                    .map(Group::getName).sorted().distinct().toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", user.getUniqueId().toString());
            out.put("username", user.getUsername());
            out.put("primaryGroup", user.getPrimaryGroup());
            out.put("directGroups", direct);
            out.put("groups", effective);
            out.put("count", effective.size());
            return ToolResult.ok(out);
        }
    }

    private static final class LpAuditReadTool extends LpTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.audit.read";
        }

        @Override
        public String description() {
            return "Read recent LuckPerms audit log entries (read-only).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200),
                    "target", Map.of("type", "string"),
                    "type", Map.of("type", "string", "enum", List.of("USER", "GROUP", "TRACK")),
                    "since", Map.of("type", "string", "description", "ISO-8601 instant")
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, Map<String, Object> args) throws Exception {
            int limit = LpSupport.boundedLimitArg(args, 50, LpSupport.MAX_AUDIT);
            String target = LpSupport.stringArg(args, "target");
            Action.Target.Type type = null;
            String typeStr = LpSupport.stringArg(args, "type");
            if (typeStr != null && !typeStr.isEmpty()) {
                try {
                    type = Action.Target.Type.valueOf(typeStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new LpSupport.InvalidArgument("Invalid type");
                }
            }
            Instant since = null;
            String sinceStr = LpSupport.stringArg(args, "since");
            if (sinceStr != null && !sinceStr.isEmpty()) {
                try {
                    since = Instant.parse(sinceStr);
                } catch (DateTimeParseException e) {
                    throw new LpSupport.InvalidArgument("Invalid since timestamp");
                }
            }

            List<Action> actions = new ArrayList<>(api.getActionLogger().getLog().join().getContent());
            actions.sort(Comparator.comparing(Action::getTimestamp).reversed());

            List<Map<String, Object>> entries = new ArrayList<>();
            for (Action action : actions) {
                if (type != null && action.getTarget().getType() != type) continue;
                if (since != null && action.getTimestamp().isBefore(since)) continue;
                if (target != null && !target.isEmpty()) {
                    String targetName = action.getTarget().getName();
                    String targetUuid = action.getTarget().getUniqueId().map(UUID::toString).orElse("");
                    if (!target.equalsIgnoreCase(targetName) && !target.equalsIgnoreCase(targetUuid)) continue;
                }
                entries.add(LpSupport.actionMap(action));
                if (entries.size() >= limit) break;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("actions", entries);
            out.put("count", entries.size());
            out.put("limit", limit);
            return ToolResult.ok(out);
        }
    }
}
