package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.config.CommandClassification;
import at.haha007.edenMcp.core.config.EdenConfig;
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
    private final boolean logVerbose;
    private final int dispatchTimeoutSeconds;

    public CommandDispatchTool(Plugin plugin, CommandClassification classification, Logger logger,
                               boolean logVerbose, EdenConfig.ToolConfig tools) {
        this.plugin = plugin;
        this.classification = new AtomicReference<>(classification == null ? CommandClassification.empty() : classification);
        this.logger = logger;
        this.logVerbose = logVerbose;
        this.dispatchTimeoutSeconds = tools.commandDispatchTimeoutSeconds();
    }

    public void updateClassification(CommandClassification classification) {
        this.classification.set(classification == null ? CommandClassification.empty() : classification);
    }

    @Override public String name() { return "eden.command.dispatch"; }
    @Override public String description() { return "Execute a console command (unsafe only). Strongly audited; server classification rules are reported in the result."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.UNSAFE_ACTION; }
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
        String normalized = CommandClassification.normalize(
                context.arguments().get("command") == null ? null : String.valueOf(context.arguments().get("command")));
        if (normalized == null) return ToolResult.error("Missing or invalid command");

        CapabilityLevel effective = classification.get().classify(normalized);
        OperatingMode mode = context.activeMode();
        if (!mode.allows(effective)) {
            log("denied-by-classification", context.callerId(), mode, effective, null);
            return ToolResult.error("Command classified as " + effective + " and not permitted in mode " + mode);
        }
        log("dispatch", context.callerId(), mode, effective, logVerbose ? normalized : null);
        try {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            boolean ok = future.get(dispatchTimeoutSeconds, TimeUnit.SECONDS);
            return ToolResult.ok(Map.of("dispatched", ok, "classification", effective.name(), "command", normalized));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(java.util.logging.Level.WARNING, "Command dispatch interrupted for " + context.callerId(), e);
            return ToolResult.error("Dispatch interrupted");
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "Command dispatch failed for " + context.callerId(), e);
            return ToolResult.error("Dispatch failed");
        }
    }

    private void log(String action, String caller, OperatingMode mode, CapabilityLevel classification, String command) {
        StringBuilder msg = new StringBuilder("[COMMAND] action=").append(action)
                .append(" caller=").append(caller)
                .append(" mode=").append(mode)
                .append(" classification=").append(classification);
        if (command != null) msg.append(" cmd=").append(command);
        logger.warning(msg.toString());
    }
}
