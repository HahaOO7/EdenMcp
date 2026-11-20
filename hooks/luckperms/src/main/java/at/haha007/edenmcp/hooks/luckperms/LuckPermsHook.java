package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.actionlog.ActionLog;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.track.Track;
import org.bukkit.Server;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

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
                new LpAuditReadTool(),
                new LpUserPermissionSetTool(),
                new LpGroupPermissionSetTool(),
                new LpGroupCreateTool(),
                new LpUserGroupAddTool(),
                new LpUserGroupRemoveTool(),
                new LpGroupMetaSetTool(),
                new LpUserTrackPromoteTool(),
                new LpUserTrackDemoteTool()
        );
        // Permission mutations are UNSAFE_ACTION and are therefore only available in unsafe mode.
        List<McpResource> resources = List.of(McpResource.text(
                "edenmcp://luckperms/groups", "LuckPerms groups",
                "Loaded LuckPerms groups (JSON, read-only).", "application/json",
                () -> {
                    List<String> all = LpSupport.api().getGroupManager().getLoadedGroups().stream()
                            .map(Group::getName).sorted().toList();
                    List<String> groups = LpSupport.limited(all, LpSupport.MAX_LIST);
                    return Map.of("groups", groups, "count", groups.size(), "total", all.size(),
                            "hasMore", LpSupport.hasMore(all, LpSupport.MAX_LIST));
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
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
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
            return Map.of("type", "object", "properties", Map.of("limit",
                    Map.of("type", "integer", "minimum", 1, "maximum", LpSupport.MAX_LIST,
                            "description", "Maximum number of groups to return (default " + LpSupport.MAX_LIST + ")")));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_LIST, LpSupport.MAX_LIST);
            List<String> all = api.getGroupManager().getLoadedGroups().stream()
                    .map(Group::getName).sorted().toList();
            List<String> groups = LpSupport.limited(all, limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groups", groups);
            out.put("count", groups.size());
            out.put("total", all.size());
            out.put("hasMore", LpSupport.hasMore(all, limit));
            return ToolResult.ok(out);
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
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String permission = LpSupport.keyArg(args, "permission");
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
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String name = LpSupport.nameArg(args, "name");
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_NODES, 500);
            Group group = LpSupport.await(api.getGroupManager().loadGroup(name), "loadGroup")
                    .orElseThrow(() -> new LpSupport.InvalidArgument("Group not found"));
            QueryOptions query = LpSupport.groupQueryOptions(api, args);
            var meta = group.getCachedData().getMetaData(query);
            OptionalInt weight = group.getWeight();
            Collection<Node> nodes = group.getNodes();
            LpSupport.Bounded<String> inherited = LpSupport.boundedSorted(
                    group.getInheritedGroups(query).stream().map(Group::getName), limit);
            LpSupport.Bounded<Map.Entry<String, List<String>>> metaEntries = LpSupport.boundedFirst(
                    meta.getMeta().entrySet().stream(), limit);
            Map<String, List<String>> metaOut = new LinkedHashMap<>();
            metaEntries.values().forEach(entry -> metaOut.put(entry.getKey(), entry.getValue()));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", group.getName());
            out.put("displayName", group.getDisplayName(query));
            out.put("weight", weight.isPresent() ? weight.getAsInt() : null);
            out.put("nodeCount", nodes.size());
            out.put("nodes", LpSupport.nodeMaps(nodes, limit));
            out.put("nodesTruncated", LpSupport.hasMore(nodes, limit));
            out.put("inheritedGroups", inherited.values());
            out.put("inheritedTruncated", inherited.hasMore());
            out.put("inheritedTotal", inherited.total());
            out.put("meta", metaOut);
            out.put("metaTruncated", metaEntries.hasMore());
            out.put("metaTotal", metaEntries.total());
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
            return Map.of("type", "object", "properties", Map.of("limit",
                    Map.of("type", "integer", "minimum", 1, "maximum", LpSupport.MAX_LIST,
                            "description", "Maximum number of tracks to return (default " + LpSupport.MAX_LIST + ")")));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_LIST, LpSupport.MAX_LIST);
            List<String> all = api.getTrackManager().getLoadedTracks().stream()
                    .map(Track::getName).sorted().toList();
            List<String> tracks = LpSupport.limited(all, limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tracks", tracks);
            out.put("count", tracks.size());
            out.put("total", all.size());
            out.put("hasMore", LpSupport.hasMore(all, limit));
            return ToolResult.ok(out);
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
                    "group", Map.of("type", "string"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", LpSupport.MAX_LIST,
                            "description", "Maximum number of groups to return (default " + LpSupport.MAX_LIST + ")")
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String name = LpSupport.nameArg(args, "name");
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_LIST, LpSupport.MAX_LIST);
            Track track = LpSupport.await(api.getTrackManager().loadTrack(name), "loadTrack")
                    .orElseThrow(() -> new LpSupport.InvalidArgument("Track not found"));
            List<String> allGroups = List.copyOf(track.getGroups());
            List<String> groups = LpSupport.limited(allGroups, limit);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", track.getName());
            out.put("groups", groups);
            out.put("count", groups.size());
            out.put("total", allGroups.size());
            out.put("hasMore", LpSupport.hasMore(allGroups, limit));

            String group = LpSupport.stringArg(args, "group");
            if (group != null && !group.isEmpty()) {
                int index = allGroups.indexOf(group);
                if (index < 0) {
                    throw new LpSupport.InvalidArgument("Group not on track");
                }
                out.put("index", index);
                out.put("next", index + 1 < allGroups.size() ? allGroups.get(index + 1) : null);
                out.put("previous", index > 0 ? allGroups.get(index - 1) : null);
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
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", LpSupport.MAX_LIST,
                            "description", "Maximum number of groups to return (default " + LpSupport.MAX_LIST + ")"),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            int limit = LpSupport.boundedLimitArg(args, LpSupport.MAX_LIST, LpSupport.MAX_LIST);
            User user = LpSupport.requireUser(api, args);
            QueryOptions query = LpSupport.queryOptions(api, user, args);
            List<String> allDirect = user.getNodes(NodeType.INHERITANCE).stream()
                    .map(InheritanceNode::getGroupName).sorted().distinct().toList();
            List<String> allEffective = user.getInheritedGroups(query).stream()
                    .map(Group::getName).sorted().distinct().toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", user.getUniqueId().toString());
            out.put("username", user.getUsername());
            out.put("primaryGroup", user.getPrimaryGroup());
            out.put("directGroups", LpSupport.limited(allDirect, limit));
            out.put("directTotal", allDirect.size());
            out.put("directHasMore", LpSupport.hasMore(allDirect, limit));
            out.put("groups", LpSupport.limited(allEffective, limit));
            out.put("count", Math.min(allEffective.size(), limit));
            out.put("total", allEffective.size());
            out.put("effectiveTotal", allEffective.size());
            out.put("effectiveHasMore", LpSupport.hasMore(allEffective, limit));
            out.put("hasMore", LpSupport.hasMore(allEffective, limit) || LpSupport.hasMore(allDirect, limit));
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
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
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

            Action.Target.Type targetType = type;
            Instant sinceInstant = since;
            ActionLog log = LpSupport.await(api.getActionLogger().getLog(), "getAuditLog");
            Comparator<Action> newestFirst = Comparator.comparing(Action::getTimestamp).reversed();
            LpSupport.Bounded<Action> top = LpSupport.topN(log.getContent().stream()
                    .filter(action -> {
                        if (targetType != null && action.getTarget().getType() != targetType) return false;
                        if (sinceInstant != null && action.getTimestamp().isBefore(sinceInstant)) return false;
                        if (target == null || target.isEmpty()) return true;
                        String targetName = action.getTarget().getName();
                        String targetUuid = action.getTarget().getUniqueId().map(UUID::toString).orElse("");
                        return target.equalsIgnoreCase(targetName) || target.equalsIgnoreCase(targetUuid);
                    }), newestFirst, limit);
            List<Map<String, Object>> entries = top.values().stream().map(LpSupport::actionMap).toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("actions", entries);
            out.put("count", entries.size());
            out.put("total", top.total());
            out.put("logTotal", log.getContent().size());
            out.put("limit", limit);
            out.put("hasMore", top.hasMore());
            return ToolResult.ok(out);
        }
    }

    /** Common shape for the permission mutation tools. */
    private abstract static class LpPermissionSetTool extends LpTool {
        protected LpPermissionSetTool() {
            super(CapabilityLevel.UNSAFE_ACTION, SideEffects.MUTATING);
        }

        @Override
        public String description() {
            return "Set or remove a LuckPerms permission node (UNSAFE_ACTION).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "permission", Map.of("type", "string"),
                    "value", Map.of("type", "boolean", "description", "false creates a negated node; default true"),
                    "remove", Map.of("type", "boolean", "description", "remove the matching node instead of setting it"),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                    "expiry", Map.of("type", "string", "description", "ISO-8601 instant; omit for a permanent node")
            ));
        }
    }

    private static final class LpUserPermissionSetTool extends LpPermissionSetTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.user.permission.set";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>(super.inputSchema());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
            properties.put("username", Map.of("type", "string"));
            properties.put("uuid", Map.of("type", "string"));
            schema.put("properties", properties);
            return schema;
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String permission = LpSupport.keyArg(args, "permission");
            User user = LpSupport.requireUser(api, args);
            var contexts = LpSupport.contextSet(args.get("contexts"));
            boolean remove = LpSupport.boolArg(args, "remove", false);
            boolean value = LpSupport.boolArg(args, "value", true);
            Node node = remove ? null : LpSupport.permissionNode(args);
            List<Node> cleared = LpSupport.clearPermissionNodes(user, permission, contexts);

            if (node != null && !user.data().add(node).wasSuccessful()) {
                LpSupport.restoreNodes(user, cleared);
                return ToolResult.error("Could not set permission");
            }
            int removed = cleared.size();
            LpSupport.saveHolder(api, user);
            LpSupport.audit(api, context.callerId(),
                    (remove ? "removed permission " : "set permission ") + permission + "=" + value, user);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", LpSupport.holderMap(user));
            out.put("permission", permission);
            out.put("value", remove ? null : value);
            out.put("removed", removed);
            out.put("contexts", contexts.toMap());
            return ToolResult.ok(out);
        }
    }

    private static final class LpGroupPermissionSetTool extends LpPermissionSetTool {
        @Override
        public String name() {
            return "edenmcp.luckperms.group.permission.set";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>(super.inputSchema());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
            properties.put("name", Map.of("type", "string"));
            schema.put("properties", properties);
            return schema;
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String permission = LpSupport.keyArg(args, "permission");
            Group group = LpSupport.requireGroup(api, LpSupport.nameArg(args, "name"));
            var contexts = LpSupport.contextSet(args.get("contexts"));
            boolean remove = LpSupport.boolArg(args, "remove", false);
            boolean value = LpSupport.boolArg(args, "value", true);
            Node node = remove ? null : LpSupport.permissionNode(args);
            List<Node> cleared = LpSupport.clearPermissionNodes(group, permission, contexts);

            if (node != null && !group.data().add(node).wasSuccessful()) {
                LpSupport.restoreNodes(group, cleared);
                return ToolResult.error("Could not set permission");
            }
            int removed = cleared.size();
            LpSupport.saveHolder(api, group);
            LpSupport.audit(api, context.callerId(),
                    (remove ? "removed permission " : "set permission ") + permission + "=" + value, group);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", LpSupport.holderMap(group));
            out.put("permission", permission);
            out.put("value", remove ? null : value);
            out.put("removed", removed);
            out.put("contexts", contexts.toMap());
            return ToolResult.ok(out);
        }
    }

    private static final class LpGroupCreateTool extends LpTool {
        private LpGroupCreateTool() {
            super(CapabilityLevel.UNSAFE_ACTION, SideEffects.MUTATING);
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.group.create";
        }

        @Override
        public String description() {
            return "Create a LuckPerms group (UNSAFE_ACTION).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            String name = LpSupport.nameArg(args, "name");
            // LuckPerms' createAndLoadGroup() returns an existing group successfully, so duplicate
            // detection must load first. The API has no atomic create-if-absent; a concurrent
            // create between these two calls is an unavoidable residual race.
            if (LpSupport.await(api.getGroupManager().loadGroup(name), "loadGroup").isPresent()) {
                return ToolResult.error("Group already exists");
            }
            Group group = LpSupport.await(api.getGroupManager().createAndLoadGroup(name), "createAndLoadGroup");
            if (group == null) {
                return ToolResult.error("Could not create group");
            }
            LpSupport.await(api.getGroupManager().saveGroup(group), "saveGroup");
            LpSupport.audit(api, context.callerId(), "created group " + name,
                    Action.Target.Type.GROUP, group.getName(), null);
            return ToolResult.ok(Map.of("name", group.getName(), "created", true));
        }
    }

    /** Base for adding/removing a user's inherited groups. */
    private abstract static class LpUserGroupMutationTool extends LpTool {
        protected LpUserGroupMutationTool(String action) {
            super(CapabilityLevel.UNSAFE_ACTION, SideEffects.MUTATING);
            this.action = action;
        }

        private final String action;

        @Override
        public String description() {
            return action + " a LuckPerms user's group inheritance (UNSAFE_ACTION).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("username", Map.of("type", "string"));
            properties.put("uuid", Map.of("type", "string"));
            properties.put("group", Map.of("type", "string"));
            properties.put("contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string")));
            if (action.equals("Add")) {
                properties.put("expiry", Map.of("type", "string", "description", "ISO-8601 instant"));
            }
            return Map.of("type", "object", "properties", properties);
        }

        protected ToolResult mutate(LuckPerms api, ToolContext context, Map<String, Object> args, String groupName)
                throws Exception {
            User user = LpSupport.requireUser(api, args);
            var contexts = LpSupport.contextSet(args.get("contexts"));
            Node node = null;
            if (action.equals("Add")) {
                LpSupport.requireGroup(api, groupName);
                node = LpSupport.inheritanceNode(groupName, args);
            }
            List<Node> cleared = LpSupport.clearInheritanceNodes(user, groupName, contexts);
            if (node != null && !user.data().add(node).wasSuccessful()) {
                LpSupport.restoreNodes(user, cleared);
                return ToolResult.error("Could not add group");
            }
            int removed = cleared.size();
            LpSupport.saveHolder(api, user);
            LpSupport.audit(api, context.callerId(),
                    action.toLowerCase(Locale.ROOT) + " group " + groupName, user);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", LpSupport.holderMap(user));
            out.put("group", groupName);
            out.put("removed", removed);
            out.put("contexts", contexts.toMap());
            return ToolResult.ok(out);
        }
    }

    private static final class LpUserGroupAddTool extends LpUserGroupMutationTool {
        private LpUserGroupAddTool() {
            super("Add");
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.user.group.add";
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            return mutate(api, context, args, LpSupport.nameArg(args, "group"));
        }
    }

    private static final class LpUserGroupRemoveTool extends LpUserGroupMutationTool {
        private LpUserGroupRemoveTool() {
            super("Remove");
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.user.group.remove";
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            return mutate(api, context, args, LpSupport.nameArg(args, "group"));
        }
    }

    private static final class LpGroupMetaSetTool extends LpTool {
        private LpGroupMetaSetTool() {
            super(CapabilityLevel.UNSAFE_ACTION, SideEffects.MUTATING);
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.group.meta.set";
        }

        @Override
        public String description() {
            return "Set a LuckPerms group's meta, prefix or suffix (UNSAFE_ACTION).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "key", Map.of("type", "string", "description", "required for type META"),
                    "value", Map.of("type", "string"),
                    "type", Map.of("type", "string", "enum", List.of("META", "PREFIX", "SUFFIX")),
                    "priority", Map.of("type", "integer", "minimum", -100, "maximum", 100),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            Group group = LpSupport.requireGroup(api, LpSupport.nameArg(args, "name"));
            String type = LpSupport.stringArg(args, "type");
            if (type == null || type.isEmpty()) type = "META";
            type = type.toUpperCase(Locale.ROOT);

            var contexts = LpSupport.contextSet(args.get("contexts"));
            String value;
            Node node;
            Predicate<Node> clearPredicate;
            if (type.equals("PREFIX") || type.equals("SUFFIX")) {
                value = LpSupport.displayValueArg(args);
                int priority = LpSupport.intArg(args, "priority", 0, -100, 100);
                if (type.equals("PREFIX")) {
                    node = LpSupport.prefixNode(value, priority, args);
                    clearPredicate = existing -> existing instanceof PrefixNode && existing.getContexts().equals(contexts);
                } else {
                    node = LpSupport.suffixNode(value, priority, args);
                    clearPredicate = existing -> existing instanceof SuffixNode && existing.getContexts().equals(contexts);
                }
            } else if (type.equals("META")) {
                value = LpSupport.keyArg(args, "value");
                String key = LpSupport.keyArg(args, "key");
                node = LpSupport.metaNode(key, value, args);
                clearPredicate = existing -> existing instanceof MetaNode m
                        && m.getMetaKey().equalsIgnoreCase(key) && existing.getContexts().equals(contexts);
            } else {
                throw new LpSupport.InvalidArgument("Invalid type");
            }

            List<Node> cleared = LpSupport.clearNodes(group, clearPredicate);
            if (!group.data().add(node).wasSuccessful()) {
                LpSupport.restoreNodes(group, cleared);
                return ToolResult.error("Could not set meta");
            }
            int removed = cleared.size();
            LpSupport.saveHolder(api, group);
            LpSupport.audit(api, context.callerId(), "set " + type.toLowerCase(Locale.ROOT) + " " + value, group);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", LpSupport.holderMap(group));
            out.put("type", type);
            out.put("value", value);
            out.put("replaced", removed);
            out.put("contexts", contexts.toMap());
            return ToolResult.ok(out);
        }
    }

    /** Shared implementation for user track promotion/demotion. */
    private abstract static class LpUserTrackMutationTool extends LpTool {
        protected LpUserTrackMutationTool(String action) {
            super(CapabilityLevel.UNSAFE_ACTION, SideEffects.MUTATING);
            this.action = action;
        }

        private final String action;

        @Override
        public String description() {
            return action + " a LuckPerms user on a track (UNSAFE_ACTION).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "username", Map.of("type", "string"),
                    "uuid", Map.of("type", "string"),
                    "track", Map.of("type", "string"),
                    "contexts", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ));
        }

        protected ToolResult mutate(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            User user = LpSupport.requireUser(api, args);
            Track track = LpSupport.requireTrack(api, LpSupport.nameArg(args, "track"));
            var contexts = LpSupport.contextSet(args.get("contexts"));
            net.luckperms.api.util.Result result = action.equals("Promote")
                    ? track.promote(user, contexts) : track.demote(user, contexts);
            if (!result.wasSuccessful()) {
                return ToolResult.error(action + " failed");
            }
            LpSupport.saveHolder(api, user);
            LpSupport.audit(api, context.callerId(),
                    action.toLowerCase(Locale.ROOT) + " on track " + track.getName(),
                    Action.Target.Type.TRACK, track.getName(), null);

            List<String> allGroups = List.copyOf(track.getGroups());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", LpSupport.holderMap(user));
            out.put("track", track.getName());
            out.put("groups", LpSupport.limited(allGroups, LpSupport.MAX_LIST));
            out.put("hasMore", LpSupport.hasMore(allGroups, LpSupport.MAX_LIST));
            return ToolResult.ok(out);
        }
    }

    private static final class LpUserTrackPromoteTool extends LpUserTrackMutationTool {
        private LpUserTrackPromoteTool() {
            super("Promote");
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.user.track.promote";
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            return mutate(api, context, args);
        }
    }

    private static final class LpUserTrackDemoteTool extends LpUserTrackMutationTool {
        private LpUserTrackDemoteTool() {
            super("Demote");
        }

        @Override
        public String name() {
            return "edenmcp.luckperms.user.track.demote";
        }

        @Override
        protected ToolResult run(LuckPerms api, ToolContext context, Map<String, Object> args) throws Exception {
            return mutate(api, context, args);
        }
    }
}
