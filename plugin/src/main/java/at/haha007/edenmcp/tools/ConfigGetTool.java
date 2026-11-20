package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.core.config.ConfigRedactor;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class ConfigGetTool implements McpTool {
    private final Supplier<Map<String, Object>> snapshotSupplier;

    public ConfigGetTool(Supplier<Map<String, Object>> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override public String name() { return "edenmcp.config.get"; }
    @Override public String description() { return "Read server/EdenMcp configuration and state (secrets redacted)."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("config"); }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> snap = snapshotSupplier.get();
        Object redacted = ConfigRedactor.redact(snap, "");
        return ToolResult.ok(redacted);
    }
}
