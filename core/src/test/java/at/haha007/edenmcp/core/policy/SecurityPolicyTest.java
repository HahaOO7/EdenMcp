package at.haha007.edenmcp.core.policy;

import at.haha007.edenmcp.api.*;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolResult;
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
            public at.haha007.edenmcp.api.SideEffects sideEffects() {
                return at.haha007.edenmcp.api.SideEffects.NONE;
            }
            public List<String> tags() { return List.of(); }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public ToolResult execute(at.haha007.edenmcp.api.ToolContext context) { return ToolResult.ok(Map.of()); }
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
