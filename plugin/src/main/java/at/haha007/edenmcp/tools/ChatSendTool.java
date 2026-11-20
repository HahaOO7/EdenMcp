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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class ChatSendTool implements McpTool {
    private static final int MAX_MESSAGE_LENGTH = 4096;
    /** Sentinel for a bucket that has never been used; nanoTime may legitimately be zero or negative. */
    private static final long NO_PREVIOUS = Long.MIN_VALUE;
    private static final long CLEANUP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Plugin plugin;
    private final Logger logger;
    private final boolean logVerbose;
    private final long minIntervalNanos;
    private final long staleAfterNanos;
    private final int syncTimeoutSeconds;
    private final ConcurrentHashMap<String, AtomicLong> lastByCaller = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupNanos = new AtomicLong(NO_PREVIOUS);

    public ChatSendTool(Plugin plugin, Logger logger, boolean logVerbose, EdenConfig.ToolConfig tools) {
        this.plugin = plugin;
        this.logger = logger;
        this.logVerbose = logVerbose;
        this.minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(tools.chatMinIntervalMs());
        this.staleAfterNanos = Math.max(TimeUnit.MINUTES.toNanos(5), minIntervalNanos);
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

        String caller = context.callerId();
        if (!acquire(caller == null ? "" : caller, System.nanoTime())) {
            return ToolResult.error("Rate limited");
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
            logger.log(java.util.logging.Level.WARNING,
                    "Chat send failed for " + sanitizeLog(context.callerId()), e);
            return ToolResult.error("Chat send failed");
        }
    }

    /**
     * Per-principal rate-limit check using a monotonic clock. Package-private so it can be
     * tested with a synthetic timeline; production always passes {@link System#nanoTime()}.
     */
    boolean acquire(String caller, long nowNanos) {
        maybePrune(nowNanos);
        AtomicLong bucket = lastByCaller.computeIfAbsent(caller, ignored -> new AtomicLong(NO_PREVIOUS));
        while (true) {
            long prev = bucket.get();
            if (prev != NO_PREVIOUS && nowNanos - prev < minIntervalNanos) return false;
            if (bucket.compareAndSet(prev, nowNanos)) return true;
            nowNanos = System.nanoTime();
        }
    }

    /** Bounds memory by occasionally dropping buckets that have not been used recently. */
    private void maybePrune(long nowNanos) {
        long last = lastCleanupNanos.get();
        if (last != NO_PREVIOUS && nowNanos - last < CLEANUP_INTERVAL_NANOS) return;
        if (!lastCleanupNanos.compareAndSet(last, nowNanos)) return;
        long staleAfter = staleAfterNanos;
        lastByCaller.entrySet().removeIf(entry -> {
            long value = entry.getValue().get();
            return value == NO_PREVIOUS || nowNanos - value > staleAfter;
        });
    }

    /** Prevents CR/LF (and other control characters) in caller input from forging log lines. */
    static String sanitizeLog(String value) {
        if (value == null) return "null";
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? '?' : c);
        }
        return clean.toString();
    }

    private void log(String kind, String target, ToolContext context, String message) {
        StringBuilder msg = new StringBuilder("[CHAT] kind=").append(kind)
                .append(" target=").append(sanitizeLog(target))
                .append(" caller=").append(sanitizeLog(context.callerId()))
                .append(" length=").append(message.length());
        if (logVerbose) msg.append(" message=").append(sanitizeLog(message));
        logger.info(msg.toString());
    }

    private <T> @Nullable T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    }
}
