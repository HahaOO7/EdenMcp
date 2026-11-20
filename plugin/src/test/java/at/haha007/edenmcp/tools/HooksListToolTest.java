package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HooksListToolTest {
    private final Logger logger = Logger.getLogger("hooks-list-tool-test-" + System.nanoTime());

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

    private Hook hook(String id, List<McpTool> tools) {
        return new Hook() {
            @Override public String id() { return id; }
            @Override public HookContribution contribute(HookContext ctx) {
                return HookContribution.of(tools, List.of());
            }
        };
    }

    private HookManager managerWithManyTools() {
        HookManager manager = new HookManager(new ToolRegistry(),
                new HookContext(null, null, null, logger));
        // Enabling via the registration handle avoids Bukkit-typed test entry points.
        manager.registerKnownHook(hook("many", IntStream.range(0, 250)
                        .mapToObj(i -> tool("edenmcp.many.tool" + i)).toList()), true)
                .setEnabled(true);
        return manager;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> content(ToolResult result) {
        assertTrue(result.success(), () -> "Unexpected error: " + result.error());
        return (Map<String, Object>) result.content();
    }

    private ToolContext context(int limit) {
        return new ToolContext(OperatingMode.UNSAFE, "test", Map.of("limit", limit));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedListsAreBoundedByTheOuterEntryLimit() {
        HooksListTool listTool = new HooksListTool(managerWithManyTools(), 10);

        Map<String, Object> out = content(listTool.execute(context(10)));

        assertEquals(1, out.get("count"));
        assertEquals(1, out.get("total"));
        assertEquals(false, out.get("hasMore"));
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) out.get("hooks");
        assertEquals(1, hooks.size());
        Map<String, Object> hook = hooks.getFirst();
        assertEquals(10, ((List<?>) hook.get("tools")).size());
        assertEquals(250, hook.get("toolsTotal"));
        assertEquals(true, hook.get("toolsHasMore"));
        assertEquals(List.of(), hook.get("resources"));
        assertEquals(List.of(), hook.get("skipped"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void outerHookLimitIsStillEnforced() {
        HookManager manager = new HookManager(new ToolRegistry(),
                new HookContext(null, null, null, logger));
        manager.registerKnownHook(hook("first", List.of(tool("edenmcp.first.tool"))), true)
                .setEnabled(true);
        manager.registerKnownHook(hook("second", List.of(tool("edenmcp.second.tool"))), true)
                .setEnabled(true);
        HooksListTool listTool = new HooksListTool(manager, 10);

        Map<String, Object> out = content(listTool.execute(context(1)));

        assertEquals(1, out.get("count"));
        assertEquals(2, out.get("total"));
        assertEquals(true, out.get("hasMore"));
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) out.get("hooks");
        assertEquals(1, hooks.size());
        assertEquals("first", hooks.getFirst().get("id"));
    }
}
