package at.haha007.edenmcp.integration;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EdenMcpApiImplMetadataTest {
    private static final String PREFIX = "edenmcp.ext.test.";

    private McpTool delegate(String description, CapabilityLevel level, SideEffects sideEffects,
                             List<String> tags, Map<String, Object> schema) {
        return new McpTool() {
            @Override public String name() { return "External Tool!"; }
            @Override public String description() { return description; }
            @Override public CapabilityLevel level() { return level; }
            @Override public SideEffects sideEffects() { return sideEffects; }
            @Override public List<String> tags() { return tags; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public ToolResult execute(ToolContext context) { return ToolResult.ok(context.callerId()); }
        };
    }

    @Test
    void nullMetadataIsNormalizedToSafeDefaults() {
        McpTool wrapped = EdenMcpApiImpl.prefixed(
                delegate(null, null, null, null, null), PREFIX);

        assertEquals("edenmcp.ext.test.external-tool", wrapped.name());
        assertEquals("", wrapped.description());
        // SAFE_READ never passes a mode gate that rejected null/unknown metadata.
        assertEquals(CapabilityLevel.SAFE_READ, wrapped.level());
        assertTrue(OperatingMode.SAFE.allows(wrapped.level()));
        assertEquals(SideEffects.NONE, wrapped.sideEffects());
        assertEquals(List.of(), wrapped.tags());
        assertEquals(Map.of("type", "object", "properties", Map.of()), wrapped.inputSchema());
    }

    @Test
    void nonNullMetadataIsPreservedAndExecutionDelegates() throws Exception {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("value", Map.of("type", "string")));
        McpTool wrapped = EdenMcpApiImpl.prefixed(
                delegate("does things", CapabilityLevel.UNSAFE_ACTION, SideEffects.DANGEROUS,
                        List.of("external"), schema), PREFIX);

        assertEquals("does things", wrapped.description());
        assertEquals(CapabilityLevel.UNSAFE_ACTION, wrapped.level());
        assertEquals(SideEffects.DANGEROUS, wrapped.sideEffects());
        assertEquals(List.of("external"), wrapped.tags());
        assertEquals(schema, wrapped.inputSchema());
        assertEquals("caller", wrapped.execute(
                new ToolContext(OperatingMode.UNSAFE, "caller", Map.of())).content());
    }
}
