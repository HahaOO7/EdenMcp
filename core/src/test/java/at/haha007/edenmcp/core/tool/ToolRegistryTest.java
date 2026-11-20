package at.haha007.edenmcp.core.tool;

import at.haha007.edenmcp.api.*;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    private static McpTool tool(String name) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public CapabilityLevel level() {
                return CapabilityLevel.SAFE_READ;
            }

            @Override
            public SideEffects sideEffects() {
                return SideEffects.NONE;
            }

            @Override
            public List<String> tags() {
                return List.of("test");
            }

            @Override
            public Map<String, Object> inputSchema() {
                return null;
            }

            @Override
            public ToolResult execute(ToolContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void unregisterIfRegisteredIsInstanceSafe() {
        ToolRegistry registry = new ToolRegistry();
        McpTool owner = tool("edenmcp.test.same");
        registry.register(owner);

        assertFalse(registry.unregisterIfRegistered(tool("edenmcp.test.same")));
        assertFalse(registry.unregisterIfRegistered(null));
        assertTrue(registry.unregisterIfRegistered(owner));
        assertFalse(registry.unregisterIfRegistered(owner));
        assertTrue(registry.get("edenmcp.test.same").isEmpty());
    }

    @Test
    void registersAndListsTools() {
        ToolRegistry registry = new ToolRegistry();
        McpTool b = tool("edenmcp.test.b");
        McpTool a = tool("edenmcp.test.a");
        registry.register(b);
        registry.register(a);
        registry.register(null);

        assertSame(a, registry.get("edenmcp.test.a").orElseThrow());
        assertEquals(List.of(a, b), registry.all());
    }
}
