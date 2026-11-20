package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.actionlog.ActionLog;
import net.luckperms.api.actionlog.ActionLogger;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused tool tests using interface proxies, so no LuckPerms server instance is needed. */
@SuppressWarnings({"unused", "SameParameterValue", "IfCanBeSwitch", "TestFailedLine"})
class LuckPermsHookTest {
    @Test
    void groupCreateRejectsExistingGroup() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        Group existing = proxy(Group.class, (p, method, args) -> {
            if (method.getName().equals("getName")) return "existing";
            throw new UnsupportedOperationException(method.getName());
        });
        GroupManager groups = proxy(GroupManager.class, (p, method, args) -> {
            if (method.getName().equals("createAndLoadGroup")) {
                creates.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("duplicate"));
            }
            if (method.getName().equals("loadGroup")) {
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(existing));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        LuckPerms api = proxy(LuckPerms.class, (p, method, args) -> {
            if (method.getName().equals("getGroupManager")) return groups;
            throw new UnsupportedOperationException(method.getName());
        });

        ToolResult result = runTool("edenmcp.luckperms.group.create", api, Map.of("name", "existing"));

        assertFalse(result.success());
        assertEquals("Group already exists", result.error());
        assertEquals(0, creates.get(), "an existing group must not be recreated");
        assertTrue(loads.get() > 0, "existing groups are detected with a load");
    }

    @Test
    void auditReadFiltersBeforeRankingAndReportsTotals() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        TreeSet<Action> content = new TreeSet<>();
        content.add(action(base.plusSeconds(1), "alice"));
        content.add(action(base.plusSeconds(2), "bob"));
        content.add(action(base.plusSeconds(3), "alice"));
        content.add(action(base.plusSeconds(4), "bob"));
        content.add(action(base.plusSeconds(5), "alice"));
        ActionLog log = proxy(ActionLog.class, (p, method, args) -> {
            if (method.getName().equals("getContent")) return content;
            throw new UnsupportedOperationException(method.getName());
        });
        ActionLogger logger = proxy(ActionLogger.class, (p, method, args) -> {
            if (method.getName().equals("getLog")) return CompletableFuture.completedFuture(log);
            throw new UnsupportedOperationException(method.getName());
        });
        LuckPerms api = proxy(LuckPerms.class, (p, method, args) -> {
            if (method.getName().equals("getActionLogger")) return logger;
            throw new UnsupportedOperationException(method.getName());
        });

        ToolResult result = runTool("edenmcp.luckperms.audit.read", api,
                Map.of("limit", 2, "target", "alice"));

        assertTrue(result.success(), String.valueOf(result.error()));
        Map<String, Object> out = content(result);
        assertEquals(2, out.get("count"));
        assertEquals(3, out.get("total"), "only matching entries count toward total");
        assertEquals(5, out.get("logTotal"));
        assertEquals(true, out.get("hasMore"));
        List<?> actions = (List<?>) out.get("actions");
        assertEquals(base.plusSeconds(5).toString(), ((Map<?, ?>) actions.get(0)).get("timestamp"));
        assertEquals(base.plusSeconds(3).toString(), ((Map<?, ?>) actions.get(1)).get("timestamp"));
    }

    @Test
    void groupGetBoundsInheritedGroupsAndMetaWithTotals() throws Exception {
        Map<String, List<String>> allMeta = new LinkedHashMap<>();
        allMeta.put("one", List.of("a"));
        allMeta.put("two", List.of("b"));
        allMeta.put("three", List.of("c"));
        CachedMetaData metaData = proxy(CachedMetaData.class, (p, method, args) -> switch (method.getName()) {
            case "getMeta" -> allMeta;
            case "getPrefix" -> "prefix";
            case "getSuffix" -> "suffix";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        CachedDataManager cachedData = proxy(CachedDataManager.class, (p, method, args) -> {
            if (method.getName().equals("getMetaData")) return metaData;
            throw new UnsupportedOperationException(method.getName());
        });
        Group group = group("test", cachedData, List.of(group("z", null, List.of()), group("a", null, List.of()),
                group("m", null, List.of()), group("b", null, List.of())));
        GroupManager groups = proxy(GroupManager.class, (p, method, args) -> {
            if (method.getName().equals("loadGroup")) {
                return CompletableFuture.completedFuture(Optional.of(group));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        ContextManager contextManager = proxy(ContextManager.class, (p, method, args) -> {
            if (method.getName().equals("getStaticQueryOptions")) return queryOptions();
            throw new UnsupportedOperationException(method.getName());
        });
        LuckPerms api = proxy(LuckPerms.class, (p, method, args) -> switch (method.getName()) {
            case "getGroupManager" -> groups;
            case "getContextManager" -> contextManager;
            default -> throw new UnsupportedOperationException(method.getName());
        });

        ToolResult result = runTool("edenmcp.luckperms.group.get", api, Map.of("name", "test", "limit", 2));

        assertTrue(result.success(), String.valueOf(result.error()));
        Map<String, Object> out = content(result);
        assertEquals(List.of("a", "b"), out.get("inheritedGroups"));
        assertEquals(4, out.get("inheritedTotal"));
        assertEquals(true, out.get("inheritedTruncated"));
        assertEquals(2, ((Map<?, ?>) out.get("meta")).size());
        assertEquals(3, out.get("metaTotal"));
        assertEquals(true, out.get("metaTruncated"));
    }

    private static Group group(String name, CachedDataManager cachedData, List<Group> inherited) {
        Node node = proxy(Node.class, (p, method, args) -> switch (method.getName()) {
            case "getKey" -> "test.node";
            case "getType" -> NodeType.PERMISSION;
            case "getValue" -> true;
            case "hasExpiry" -> false;
            case "getContexts" -> emptyContexts();
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Group.class, (p, method, args) -> switch (method.getName()) {
            case "getName", "getDisplayName" -> name;
            case "getWeight" -> OptionalInt.of(10);
            case "getNodes" -> List.of(node);
            case "getCachedData" -> cachedData;
            case "getInheritedGroups" -> inherited;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Action action(Instant timestamp, String targetName) {
        UUID sourceUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Action.Source source = proxy(Action.Source.class, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> sourceUuid;
            case "getName" -> "source";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Action.Target target = proxy(Action.Target.class, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> Optional.of(targetUuid);
            case "getName" -> targetName;
            case "getType" -> Action.Target.Type.USER;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Action.class, (p, method, args) -> switch (method.getName()) {
            case "getTimestamp" -> timestamp;
            case "getSource" -> source;
            case "getTarget" -> target;
            case "getDescription" -> "description";
            case "compareTo" -> timestamp.compareTo(((Action) args[0]).getTimestamp());
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static QueryOptions queryOptions() {
        return proxy(QueryOptions.class, (p, method, args) -> switch (method.getName()) {
            case "mode" -> QueryMode.NON_CONTEXTUAL;
            case "context" -> emptyContexts();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static ImmutableContextSet emptyContexts() {
        return proxy(ImmutableContextSet.class, (p, method, args) -> {
            if (method.getName().equals("toMap")) return Map.of();
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static ToolResult runTool(String name, LuckPerms api, Map<String, Object> args) throws Exception {
        McpTool tool = new LuckPermsHook().contribute(null).tools().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
        Method run = tool.getClass().getDeclaredMethod("run", LuckPerms.class, ToolContext.class, Map.class);
        run.setAccessible(true);
        return (ToolResult) run.invoke(tool, api, new ToolContext(null, "test", args), args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(ToolResult result) {
        return (Map<String, Object>) result.content();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        InvocationHandler safeHandler = (p, method, args) -> switch (method.getName()) {
            case "toString" -> type.getSimpleName() + "@" + System.identityHashCode(p);
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> handler.invoke(p, method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, safeHandler);
    }
}
