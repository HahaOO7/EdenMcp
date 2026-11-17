package at.haha007.edenMcp.core.policy;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {
    private McpTool tool(CapabilityLevel level) {
        return new McpTool() {
            public String name() { return "test.tool"; }
            public String description() { return "test"; }
            public CapabilityLevel level() { return level; }
            public at.haha007.edenMcp.core.capability.SideEffects sideEffects() {
                return at.haha007.edenMcp.core.capability.SideEffects.NONE;
            }
            public List<String> tags() { return List.of(); }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public ToolResult execute(at.haha007.edenMcp.core.tool.ToolContext context) { return ToolResult.ok(Map.of()); }
        };
    }

    @Test
    void modeCeilingIsEnforced() {
        SecurityPolicy policy = new SecurityPolicy(true);
        assertTrue(policy.canExecute(OperatingMode.SAFE, tool(CapabilityLevel.SAFE_READ)));
        assertFalse(policy.canExecute(OperatingMode.SAFE, tool(CapabilityLevel.MODERATE_ACTION)));
        assertFalse(policy.canExecute(OperatingMode.MODERATE, tool(CapabilityLevel.UNSAFE_ACTION)));
        assertTrue(policy.canExecute(OperatingMode.UNSAFE, tool(CapabilityLevel.UNSAFE_ACTION)));
        assertFalse(policy.canExecute(null, tool(CapabilityLevel.SAFE_READ)));
        assertFalse(policy.canExecute(OperatingMode.UNSAFE, null));
    }

    @Test
    void unsafeEscalationLockOnlyAffectsUnsafe() {
        SecurityPolicy locked = new SecurityPolicy(false);
        assertTrue(locked.canEnter(OperatingMode.SAFE));
        assertTrue(locked.canEnter(OperatingMode.MODERATE));
        assertFalse(locked.canEnter(OperatingMode.UNSAFE));
        assertFalse(locked.allowsUnsafeAtRuntime());

        SecurityPolicy open = new SecurityPolicy(true);
        assertTrue(open.canEnter(OperatingMode.UNSAFE));
    }
}
