package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.config.CommandClassification;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class CommandDispatchTool implements McpTool {
    private final Plugin plugin;
    private final AtomicReference<CommandClassification> classification;
    private final Logger logger;

    public CommandDispatchTool(Plugin plugin, CommandClassification classification, Logger logger) {
        this.plugin = plugin;
        this.classification = new AtomicReference<>(classification == null ? CommandClassification.empty() : classification);
        this.logger = logger;
    }

    public void updateClassification(CommandClassification classification) {
        this.classification.set(classification == null ? CommandClassification.empty() : classification);
    }

    @Override public String name() { return "eden.command.dispatch"; }
    @Override public String description() { return "Execute a console command. UNSAFE_ACTION by default; server regex rules may reclassify to MODERATE/SAFE. Effective classification is enforced per command. Strongly audited."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.DANGEROUS; }
    @Override public List<String> tags() { return List.of("commands"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("command", Map.of("type", "string", "description", "Console command to execute")),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        String cmd = context.arguments().get("command") == null ? null : String.valueOf(context.arguments().get("command"));
        if (cmd == null || cmd.isBlank()) return ToolResult.error("Missing command");
        String normalized = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        CapabilityLevel effective = classification.get().classify(normalized);
        OperatingMode mode = context.activeMode();
        if (!mode.allows(effective)) {
            return ToolResult.error("Command classified as " + effective + " and not permitted in mode " + mode);
        }
        logger.warning("[COMMAND] caller=" + context.callerId() + " mode=" + mode + " classification=" + effective + " cmd=" + normalized);
        try {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized);
                    future.complete(ok);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            boolean ok = future.get(10, TimeUnit.SECONDS);
            return ToolResult.ok(Map.of("dispatched", ok, "classification", effective.name(), "command", normalized));
        } catch (Exception e) {
            return ToolResult.error("Dispatch failed: " + e.getMessage());
        }
    }
}
