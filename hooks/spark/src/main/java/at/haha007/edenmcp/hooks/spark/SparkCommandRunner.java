package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.ToolContext;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/** Dispatches a fixed spark command on the server thread and captures its reply for the caller. */
final class SparkCommandRunner {
    private static final String COMPONENT = "net.kyori.adventure.text.Component";
    private static final String PLAIN_SERIALIZER = "net.kyori.adventure.text.serializer.plaintext.PlainTextComponentSerializer";
    private static final int MAX_OUTPUT_LINES = 200;
    private static final int MAX_LINE_CHARS = 2000;
    private static final int MAX_TOTAL_CHARS = 64_000;
    private static final long MIN_QUIET_MILLIS = 1;
    private static final long MAX_QUIET_MILLIS = 5_000;
    private static final long POLL_MILLIS = 10;

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

    record Result(String command, boolean dispatched, List<String> output, boolean truncated) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", command);
            out.put("dispatched", dispatched);
            out.put("output", output);
            out.put("truncated", truncated);
            return out;
        }
    }

    Result run(String command, String action, ToolContext context) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        boolean[] truncated = {false};
        int[] capturedChars = {0};
        int[] received = {0};
        CommandSender capture = capturingSender(lines, truncated, capturedChars, received);
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

        // Spark sends a short acknowledgement synchronously and detailed output from a worker
        // thread. Wait for a quiet period after *any* output, not just after the first line.
        awaitQuiet(() -> received[0], settleMillis);

        logger.warning("[SPARK] action=" + action
                + " caller=" + context.callerId()
                + " mode=" + context.activeMode()
                + " command=" + command
                + " dispatched=" + dispatched);

        return new Result(command, dispatched, List.copyOf(lines), truncated[0]);
    }

    /**
     * Waits until no new command output has arrived for a short window, with an overall bound.
     * {@code activity} counts every accepted message, including ones dropped by the output caps.
     */
    @SuppressWarnings("BusyWait")
    static void awaitQuiet(IntSupplier activity, long settleMillis) throws InterruptedException {
        long quietNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(MIN_QUIET_MILLIS, settleMillis));
        long maxMillis = Math.clamp(settleMillis * 6L, 2_000L, MAX_QUIET_MILLIS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        int last = activity.getAsInt();
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int current = activity.getAsInt();
            if (current != last) {
                last = current;
                lastChange = System.nanoTime();
            }
            if (System.nanoTime() - lastChange >= quietNanos) return;
            Thread.sleep(POLL_MILLIS);
        }
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static CommandSender capturingSender(List<String> lines, boolean[] truncated,
                                                  int[] capturedChars, int[] received) {
        // Paper exposes a sender implementation that forwards command feedback to a Consumer.
        // Using it keeps Bukkit's command dispatcher happy (it needs a vanilla-compatible
        // sender) while still capturing the reply for the MCP caller.
        try {
            Class<?> forwarding = Class.forName("io.papermc.paper.commands.FeedbackForwardingSender");
            InvocationHandler handler = (proxy, method, args) -> {
                if ("accept".equals(method.getName())) {
                    if (args != null && args.length > 0) {
                        received[0]++;
                        String line = render(args[0]);
                        if (line.length() > MAX_LINE_CHARS) {
                            line = line.substring(0, MAX_LINE_CHARS) + "...";
                            truncated[0] = true;
                        }
                        if (lines.size() >= MAX_OUTPUT_LINES
                                || capturedChars[0] + line.length() > MAX_TOTAL_CHARS) {
                            truncated[0] = true;
                        } else {
                            lines.add(line);
                            capturedChars[0] += line.length();
                        }
                    }
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
