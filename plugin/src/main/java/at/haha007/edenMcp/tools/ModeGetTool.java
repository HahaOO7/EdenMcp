package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ModeGetTool implements McpTool {
    private final AtomicReference<OperatingMode> mode;

    public ModeGetTool(AtomicReference<OperatingMode> mode) {
        this.mode = mode;
    }

    @Override public String name() { return "eden.mode.get"; }
    @Override public String description() { return "Get current EdenMcp operating mode."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("mode"); }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override
    public ToolResult execute(ToolContext context) {
        return ToolResult.ok(Map.of("mode", mode.get().name().toLowerCase()));
    }
}
