package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.core.config.EdenConfig;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class ChatSendTool implements McpTool {
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final Plugin plugin;
    private final Logger logger;
    private final boolean logVerbose;
    private final long minIntervalMs;
    private final int syncTimeoutSeconds;
    private final AtomicLong last = new AtomicLong(0);

    public ChatSendTool(Plugin plugin, Logger logger, boolean logVerbose, EdenConfig.ToolConfig tools) {
        this.plugin = plugin;
        this.logger = logger;
        this.logVerbose = logVerbose;
        this.minIntervalMs = tools.chatMinIntervalMs();
        this.syncTimeoutSeconds = tools.chatSyncTimeoutSeconds();
    }

    @Override public String name() { return "edenmcp.chat.send"; }
    @Override public String description() { return "Send a message to a player or broadcast (moderate, rate-limited, attributed)."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.MODERATE_ACTION; }
    @Override public SideEffects sideEffects() { return SideEffects.OBSERVABLE; }
    @Override public List<String> tags() { return List.of("chat"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of("type", "string", "description", "Message text"),
                        "target", Map.of("type", "string", "description", "Player name/UUID; omit or 'broadcast' for broadcast")
                ),
                "required", List.of("message")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        String message;
        String target;
        try {
            message = ToolArgs.requiredString(context.arguments(), "message");
            target = ToolArgs.string(context.arguments(), "target");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (message.isBlank()) return ToolResult.error("Missing message");
        if (message.length() > MAX_MESSAGE_LENGTH) return ToolResult.error("Message is too long");

        long now = System.currentTimeMillis();
        while (true) {
            long prev = last.get();
            if (now - prev < minIntervalMs) return ToolResult.error("Rate limited");
            if (last.compareAndSet(prev, now)) break;
            now = System.currentTimeMillis();
        }

        final String recipient = target == null ? "broadcast" : target;
        Component attributed = Component.text("[EdenMCP] " + message);
        try {
            if (recipient.equalsIgnoreCase("broadcast") || recipient.isBlank()) {
                runSync(() -> {
                    Bukkit.getServer().broadcast(attributed);
                    return null;
                });
                log("broadcast", "broadcast", context, message);
                return ToolResult.ok(Map.of("sent", "broadcast", "message", message));
            } else {
                String sentTo = runSync(() -> {
                    Player p = Bukkit.getPlayerExact(recipient);
                    if (p == null) {
                        try { p = Bukkit.getPlayer(UUID.fromString(recipient)); } catch (Exception ignored) {}
                    }
                    if (p == null) return null;
                    p.sendMessage(attributed);
                    return p.getName();
                });
                if (sentTo == null) return ToolResult.error("Player not found");
                log("player", sentTo, context, message);
                return ToolResult.ok(Map.of("sent", sentTo, "message", message));
            }
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "Chat send failed for " + context.callerId(), e);
            return ToolResult.error("Chat send failed");
        }
    }

    private void log(String kind, String target, ToolContext context, String message) {
        StringBuilder msg = new StringBuilder("[CHAT] kind=").append(kind)
                .append(" target=").append(target)
                .append(" caller=").append(context.callerId())
                .append(" length=").append(message.length());
        if (logVerbose) msg.append(" message=").append(message);
        logger.info(msg.toString());
    }

    private <T> @Nullable T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    }
}
