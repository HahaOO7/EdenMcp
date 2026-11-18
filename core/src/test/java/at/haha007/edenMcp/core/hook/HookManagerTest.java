package at.haha007.edenMcp.core.hook;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class HookManagerTest {
    private final Logger logger = Logger.getLogger("hook-manager-test-" + System.nanoTime());
    private final ToolRegistry registry = new ToolRegistry();
    private final HookManager manager = new HookManager(registry,
            new HookContext(null, null, logger, new AuditLogger(logger)));

    private McpTool tool(String name) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of(); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { return ToolResult.ok(Map.of()); }
        };
    }

    private HookContribution contribution(McpTool tool, Runnable onEnable, Runnable onDisable) {
        return new HookContribution(List.of(tool), List.of(),
                Optional.ofNullable(onEnable), Optional.ofNullable(onDisable));
    }

    private EdenHook hook(String id, HookContribution contribution) {
        return new EdenHook() {
            @Override public String id() { return id; }
            @Override public boolean isAvailable(Server server) { return true; }
            @Override public HookContribution contribute(HookContext ctx) { return contribution; }
        };
    }

    private Map<String, Object> describeFirstHook() {
        return manager.describeHooks().getFirst();
    }

    @Test
    void throwingOnEnableLeavesNoCallableTools() {
        McpTool tool = tool("eden.broken.tool");
        manager.registerKnownHook(hook("broken", contribution(tool,
                () -> { throw new IllegalStateException("boom"); }, null)), true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(registry.get("eden.broken.tool").isEmpty());
        assertTrue(registry.all().isEmpty());
        assertEquals(false, describeFirstHook().get("enabled"));
        assertEquals(List.of(), describeFirstHook().get("tools"));
    }

    @Test
    void failedHookDoesNotClobberExistingToolWithSameName() {
        McpTool coreTool = tool("shared.tool");
        registry.register(coreTool);
        McpTool hookTool = tool("shared.tool");
        manager.registerKnownHook(hook("broken", contribution(hookTool,
                () -> { throw new IllegalStateException("boom"); }, null)), true);

        manager.enableAvailableHooks(null);

        assertSame(coreTool, registry.get("shared.tool").orElseThrow());
    }

    @Test
    void throwingContributeLeavesNoToolsRegistered() {
        manager.registerKnownHook(new EdenHook() {
            @Override public String id() { return "broken"; }
            @Override public boolean isAvailable(Server server) { return true; }
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
        McpTool good = tool("eden.partial.good");
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
        manager.registerKnownHook(hook("partial", new HookContribution(
                List.of(good, broken), List.of(), Optional.empty(),
                Optional.of(() -> disabled[0] = true))), true);

        assertDoesNotThrow(() -> manager.enableAvailableHooks(null));

        assertTrue(registry.get("eden.partial.good").isEmpty());
        assertTrue(registry.all().isEmpty());
        assertTrue(disabled[0]);
        assertEquals(false, describeFirstHook().get("enabled"));
    }

    @Test
    void successfulHookRegistersToolsAndReportsEnabled() {
        McpTool tool = tool("eden.working.tool");
        manager.registerKnownHook(hook("working", contribution(tool, null, null)), true);

        manager.enableAvailableHooks(null);

        assertSame(tool, registry.get("eden.working.tool").orElseThrow());
        assertEquals(true, describeFirstHook().get("enabled"));
        assertEquals(List.of("eden.working.tool"), describeFirstHook().get("tools"));

        manager.disableAll();
        assertTrue(registry.get("eden.working.tool").isEmpty());
    }
}
