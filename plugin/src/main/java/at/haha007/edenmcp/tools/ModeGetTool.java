package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ModeGetTool implements McpTool {
    private final AtomicReference<OperatingMode> mode;

    public ModeGetTool(AtomicReference<OperatingMode> mode) {
        this.mode = mode;
    }

    @Override public String name() { return "edenmcp.mode.get"; }
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
