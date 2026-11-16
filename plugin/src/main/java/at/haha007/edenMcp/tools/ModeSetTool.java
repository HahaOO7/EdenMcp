package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ModeSetTool implements McpTool {
    private final AtomicReference<OperatingMode> mode;
    private final AuditLogger audit;
    private final Consumer<OperatingMode> onChange;

    public ModeSetTool(AtomicReference<OperatingMode> mode, AuditLogger audit, Consumer<OperatingMode> onChange) {
        this.mode = mode;
        this.audit = audit;
        this.onChange = onChange;
    }

    @Override public String name() { return "eden.mode.set"; }
    @Override public String description() { return "Change operating mode at runtime (audited). Allowed in moderate/unsafe modes."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.MODERATE_ACTION; }
    @Override public SideEffects sideEffects() { return SideEffects.MUTATING; }
    @Override public List<String> tags() { return List.of("mode", "control"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("mode", Map.of("type", "string", "enum", List.of("safe", "moderate", "unsafe"))),
                "required", List.of("mode")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object m = context.arguments().get("mode");
        if (m == null) return ToolResult.error("Missing 'mode'");
        OperatingMode next = OperatingMode.parse(String.valueOf(m), null);
        if (next == null) return ToolResult.error("Invalid mode");
        OperatingMode prev = mode.getAndSet(next);
        audit.modeChange(context.callerId(), prev.name(), next.name());
        if (onChange != null) {
            try { onChange.accept(next); } catch (Exception ignored) {}
        }
        return ToolResult.ok(Map.of("previous", prev.name().toLowerCase(Locale.ROOT), "mode", next.name().toLowerCase(Locale.ROOT)));
    }
}
