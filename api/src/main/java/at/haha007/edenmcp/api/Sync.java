package at.haha007.edenmcp.api;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Helper for calling Bukkit API from a tool's {@link McpTool#execute(ToolContext)},
 * which runs on an HTTP worker thread.
 */
public final class Sync {
    private Sync() {
    }

    public static <T> T call(Plugin owner, Callable<T> task) throws Exception {
        if (org.bukkit.Bukkit.isPrimaryThread()) return task.call();
        FutureTask<T> future = new FutureTask<>(task);
        owner.getServer().getScheduler().runTask(owner, future);
        return future.get();
    }

    public static void run(Plugin owner, Runnable task) {
        BukkitScheduler scheduler = owner.getServer().getScheduler();
        if (org.bukkit.Bukkit.isPrimaryThread()) task.run();
        else scheduler.runTask(owner, task);
    }
}
