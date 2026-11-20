package at.haha007.edenmcp.core.hook;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ResourceContext;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HookManagerTest {
    private final Logger logger = Logger.getLogger("hook-manager-test-" + System.nanoTime());
    private final ToolRegistry registry = new ToolRegistry();
    private final HookManager manager = new HookManager(registry,
            new HookContext(null, null, null, logger));

    private McpTool tool(String name) {
        return tool(name, CapabilityLevel.SAFE_READ);
    }

    private McpTool tool(String name, CapabilityLevel level) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public CapabilityLevel level() { return level; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of(); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { return ToolResult.ok(Map.of()); }
        };
    }

    private McpResource resource(String uri) {
        return McpResource.text(uri, uri, "test resource", "text/plain", () -> "content of " + uri);
    }

    private Hook hook(String id, HookContribution contribution) {
        return hook(id, contribution, () -> {}, () -> {});
    }

    private Hook hook(String id, HookContribution contribution, Runnable onEnable, Runnable onDisable) {
        return new Hook() {
            @Override public String id() { return id; }
            @Override public HookContribution contribute(HookContext ctx) { return contribution; }
            @Override public void onEnable() { onEnable.run(); }
            @Override public void onDisable() { onDisable.run(); }
        };
    }

    private Map<String, Object> describeFirstHook() {
        return manager.describeHooks().getFirst();
    }

    @Test
    void throwingOnEnableLeavesNoCallableTools() {
        McpTool tool = tool("edenmcp.broken.tool");
        manager.registerKnownHook(hook("broken", HookContribution.tools(tool),
                () -> { throw new IllegalStateException("boom"); }, () -> {}), true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(registry.get("edenmcp.broken.tool").isEmpty());
        assertTrue(registry.all().isEmpty());
        assertEquals(false, describeFirstHook().get("enabled"));
        assertEquals(List.of(), describeFirstHook().get("tools"));
    }

    @Test
    void failedHookDoesNotClobberExistingToolWithSameName() {
        McpTool coreTool = tool("shared.tool");
        registry.register(coreTool);
        McpTool hookTool = tool("shared.tool");
        manager.registerKnownHook(hook("broken", HookContribution.tools(hookTool),
                () -> { throw new IllegalStateException("boom"); }, () -> {}), true);

        manager.enableAvailableHooks(null);

        assertSame(coreTool, registry.get("shared.tool").orElseThrow());
    }

    @Test
    void throwingContributeLeavesNoToolsRegistered() {
        manager.registerKnownHook(new Hook() {
            @Override public String id() { return "broken"; }
            @Override public HookContribution contribute(HookContext ctx) {
                throw new IllegalStateException("contribute failed");
            }
        }, true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(registry.all().isEmpty());
        assertEquals(false, describeFirstHook().get("enabled"));
    }

    @Test
    void registrationFailureRollsBackEarlierToolsAndDisablesHook() {
        McpTool good = tool("edenmcp.partial.good");
        McpTool broken = new McpTool() {
            @Override public String name() { throw new IllegalStateException("bad name"); }
            @Override public String description() { return "broken"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of(); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { return ToolResult.ok(Map.of()); }
        };
        boolean[] disabled = {false};
        manager.registerKnownHook(hook("partial", HookContribution.tools(good, broken),
                () -> {}, () -> disabled[0] = true), true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(registry.get("edenmcp.partial.good").isEmpty());
        assertTrue(registry.all().isEmpty());
        assertTrue(disabled[0]);
        assertEquals(false, describeFirstHook().get("enabled"));
    }

    @Test
    void successfulHookRegistersToolsAndReportsEnabled() {
        McpTool tool = tool("edenmcp.working.tool");
        manager.registerKnownHook(hook("working", HookContribution.tools(tool)), true);

        manager.enableAvailableHooks(null);

        assertSame(tool, registry.get("edenmcp.working.tool").orElseThrow());
        assertEquals(true, describeFirstHook().get("enabled"));
        assertEquals(List.of("edenmcp.working.tool"), describeFirstHook().get("tools"));

        manager.disableAll();
        assertTrue(registry.get("edenmcp.working.tool").isEmpty());
    }

    @Test
    void contributionFactoriesExposeExpectedSurface() {
        assertTrue(HookContribution.empty().tools().isEmpty());
        assertTrue(HookContribution.empty().resources().isEmpty());

        McpTool tool = tool("edenmcp.factory.tool");
        HookContribution toolOnly = HookContribution.tools(tool);
        assertEquals(List.of(tool), toolOnly.tools());
        assertTrue(toolOnly.resources().isEmpty());
    }

    @Test
    void successfulHookRegistersResourcesAndReportsThem() throws Exception {
        McpResource resource = resource("edenmcp://test/status");
        manager.registerKnownHook(hook("res", HookContribution.of(
                List.of(tool("edenmcp.res.tool")), List.of(resource))), true);

        manager.enableAvailableHooks(null);

        assertSame(resource, manager.resources().get("edenmcp://test/status").orElseThrow());
        assertEquals("content of edenmcp://test/status",
                manager.resources().get("edenmcp://test/status").orElseThrow()
                        .read(new ResourceContext(null, "caller", "edenmcp://test/status")).text());
        assertEquals(List.of("edenmcp://test/status"), describeFirstHook().get("resources"));

        manager.disableAll();
        assertTrue(manager.resources().get("edenmcp://test/status").isEmpty());
    }

    @Test
    void failedHookRollsBackResourcesWithoutClobberingExistingOnes() {
        McpResource coreResource = resource("edenmcp://shared/status");
        manager.resources().register(coreResource);
        McpResource hookResource = resource("edenmcp://partial/added");
        McpTool broken = new McpTool() {
            @Override public String name() { throw new IllegalStateException("bad name"); }
            @Override public String description() { return "broken"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of(); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { return ToolResult.ok(Map.of()); }
        };
        manager.registerKnownHook(hook("partial", HookContribution.of(
                List.of(broken), List.of(hookResource, resource("edenmcp://shared/status")))), true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(manager.resources().get("edenmcp://partial/added").isEmpty());
        assertSame(coreResource, manager.resources().get("edenmcp://shared/status").orElseThrow());
        assertEquals(false, describeFirstHook().get("enabled"));
        assertEquals(List.of(), describeFirstHook().get("resources"));
    }

    @Test
    void hookRegisteredAfterStartupIsEnabledImmediately() {
        manager.enableAvailableHooks(null);
        McpTool tool = tool("edenmcp.dynamic.tool");

        var registration = manager.register(null, hook("dynamic", HookContribution.tools(tool)),
                HookManager.HookPolicy.builtin(true), "test");

        assertTrue(registration.isEnabled());
        assertSame(tool, registry.get("edenmcp.dynamic.tool").orElseThrow());
        registration.setEnabled(false);
        assertTrue(registry.get("edenmcp.dynamic.tool").isEmpty());
        registration.setEnabled(true);
        assertSame(tool, registry.get("edenmcp.dynamic.tool").orElseThrow());
        assertTrue(registration.unregister());
        assertFalse(registration.unregister());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void hookContextExposesTheOwningPlugin() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "External";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PluginProxy(External)";
                    default -> null;
                });
        AtomicReference<Plugin> seenOwner = new AtomicReference<>();
        Hook hook = new Hook() {
            @Override public String id() { return "owned"; }
            @Override public HookContribution contribute(HookContext ctx) {
                seenOwner.set(ctx.owner());
                return HookContribution.empty();
            }
        };
        manager.enableAvailableHooks(null);

        manager.register(owner, hook, HookManager.HookPolicy.builtin(true), "test");

        assertSame(owner, seenOwner.get());
    }

    @Test
    void policyCapsToolLevelAndRecordsSkipped() {
        manager.enableAvailableHooks(null);
        McpTool tooHigh = tool("edenmcp.capped.action", CapabilityLevel.UNSAFE_ACTION);
        var policy = HookManager.HookPolicy.builtin(true).withLimits(true,
                CapabilityLevel.MODERATE_ACTION, false, Objects::nonNull, Objects::nonNull);

        var registration = manager.register(null, hook("capped", HookContribution.tools(tooHigh)), policy, "test");

        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("edenmcp.capped.action"), registration.info().skipped());
        assertEquals(at.haha007.edenmcp.api.Registration.RegistrationState.ENABLED, registration.state());
    }

    @Test
    void namespacingValidatorRejectsBadToolNames() {
        manager.enableAvailableHooks(null);
        McpTool bad = tool("edenmcp.wrong.tool");
        var policy = HookManager.HookPolicy.builtin(true).withLimits(true,
                CapabilityLevel.UNSAFE_ACTION, true,
                t -> t.name().startsWith("edenmcp.right."), Objects::nonNull);

        manager.register(null, hook("ns", HookContribution.tools(bad)), policy, "test");

        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("edenmcp.wrong.tool"), manager.hookInfos().getFirst().skipped());
    }

    @Test
    void describeHooksBoundsNestedArraysAndReportsTruncation() {
        List<McpTool> manyTools = IntStream.range(0, 250)
                .mapToObj(i -> tool("edenmcp.big.tool" + i)).toList();
        List<McpResource> manyResources = IntStream.range(0, 150)
                .mapToObj(i -> resource("edenmcp://big/" + i)).toList();
        manager.registerKnownHook(hook("big", HookContribution.of(manyTools, manyResources)), true);

        manager.enableAvailableHooks(null);

        Map<String, Object> described = describeFirstHook();
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) described.get("tools");
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) described.get("resources");
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, tools.size());
        assertEquals("edenmcp.big.tool0", tools.getFirst());
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, described.get("toolsCount"));
        assertEquals(250, described.get("toolsTotal"));
        assertEquals(true, described.get("toolsHasMore"));
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, resources.size());
        assertEquals("edenmcp://big/0", resources.getFirst());
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, described.get("resourcesCount"));
        assertEquals(150, described.get("resourcesTotal"));
        assertEquals(true, described.get("resourcesHasMore"));
        assertEquals(List.of(), described.get("skipped"));
        assertEquals(0, described.get("skippedCount"));
        assertEquals(0, described.get("skippedTotal"));
        assertEquals(false, described.get("skippedHasMore"));

        // Callers may request a tighter cap than the hard limit.
        Map<String, Object> tighter = manager.describeHooks(5).getFirst();
        assertEquals(5, ((List<?>) tighter.get("tools")).size());
        assertEquals(true, tighter.get("toolsHasMore"));
        assertEquals(250, tighter.get("toolsTotal"));
    }

    @Test
    void describeHooksBoundsSkippedNames() {
        List<McpTool> rejected = IntStream.range(0, 160)
                .mapToObj(i -> tool("edenmcp.rejected.tool" + i)).toList();
        var policy = HookManager.HookPolicy.builtin(true).withLimits(true,
                CapabilityLevel.UNSAFE_ACTION, true, Objects::isNull, Objects::nonNull);
        manager.register(null, hook("skipped", HookContribution.of(rejected, List.of())), policy, "test");

        manager.enableAvailableHooks(null);

        Map<String, Object> described = describeFirstHook();
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) described.get("skipped");
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, skipped.size());
        assertEquals("edenmcp.rejected.tool0", skipped.getFirst());
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS, described.get("skippedCount"));
        assertEquals(160, described.get("skippedTotal"));
        assertEquals(true, described.get("skippedHasMore"));
        // Descriptors describe the raw contribution; skipped names are reported separately.
        assertEquals(HookManager.MAX_DESCRIBED_LIST_ITEMS,
                ((List<?>) described.get("tools")).size());
        assertEquals(160, described.get("toolsTotal"));
        assertEquals(true, described.get("toolsHasMore"));
    }
}
