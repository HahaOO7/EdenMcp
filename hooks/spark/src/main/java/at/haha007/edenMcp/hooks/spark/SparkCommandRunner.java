package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.tool.ToolContext;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Dispatches a fixed spark command on the server thread and captures its reply for the caller. */
final class SparkCommandRunner {
    private static final String COMPONENT = "net.kyori.adventure.text.Component";
    private static final String PLAIN_SERIALIZER = "net.kyori.adventure.text.serializer.plaintext.PlainTextComponentSerializer";

    private final Plugin plugin;
    private final Logger logger;
    private final int timeoutSeconds;
    private final long settleMillis;

    SparkCommandRunner(Plugin plugin, Logger logger, int timeoutSeconds, long settleMillis) {
        this.plugin = plugin;
        this.logger = logger;
        this.timeoutSeconds = timeoutSeconds;
        this.settleMillis = settleMillis;
    }

    record Result(String command, boolean dispatched, List<String> output) {
        Map<String, Object> toMap() {
            return Map.of(
                    "command", command,
                    "dispatched", dispatched,
                    "output", output
            );
        }
    }

    @SuppressWarnings("BusyWait")
    Result run(String command, String action, ToolContext context) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        CommandSender capture = capturingSender(lines);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(Bukkit.dispatchCommand(capture, command));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        boolean dispatched;
        try {
            dispatched = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        // Spark sends a short acknowledgement synchronously and some follow-up messages from a
        // worker thread. Give those a brief window to arrive before returning the captured reply.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMillis);
        while (lines.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        logger.warning("[SPARK] action=" + action
                + " caller=" + context.callerId()
                + " mode=" + context.activeMode()
                + " command=" + command
                + " dispatched=" + dispatched);

        return new Result(command, dispatched, List.copyOf(lines));
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static CommandSender capturingSender(List<String> lines) {
        // Paper exposes a sender implementation that forwards command feedback to a Consumer.
        // Using it keeps Bukkit's command dispatcher happy (it needs a vanilla-compatible
        // sender) while still capturing the reply for the MCP caller.
        try {
            Class<?> forwarding = Class.forName("io.papermc.paper.commands.FeedbackForwardingSender");
            InvocationHandler handler = (proxy, method, args) -> {
                if ("accept".equals(method.getName())) {
                    if (args != null && args.length > 0) lines.add(render(args[0]));
                    return null;
                }
                return switch (method.getName()) {
                    case "toString" -> "SparkFeedbackSender";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            };
            Object consumer = Proxy.newProxyInstance(
                    Consumer.class.getClassLoader(), new Class<?>[]{Consumer.class}, handler);
            Object server = Bukkit.getServer();
            Object sender = forwarding.getConstructor(Consumer.class, server.getClass())
                    .newInstance(consumer, server);
            return (CommandSender) sender;
        } catch (Throwable ignored) {
            // Fall back to the console sender; output will appear in the server log only.
            return Bukkit.getConsoleSender();
        }
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static String render(@Nullable Object message) {
        if (message == null) return "";
        if (message instanceof CharSequence) return message.toString();
        try {
            Class<?> componentClass = Class.forName(COMPONENT);
            if (componentClass.isInstance(message)) {
                Class<?> serializerClass = Class.forName(PLAIN_SERIALIZER);
                Object serializer = serializerClass.getMethod("plainText").invoke(null);
                Object rendered = serializer.getClass()
                        .getMethod("serialize", componentClass)
                        .invoke(serializer, message);
                return String.valueOf(rendered);
            }
        } catch (Throwable ignored) {
            // Fall back to the component's own string representation.
        }
        return String.valueOf(message);
    }
}
