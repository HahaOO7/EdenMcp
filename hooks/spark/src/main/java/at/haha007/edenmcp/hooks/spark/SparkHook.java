package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.Hook;
import at.haha007.edenmcp.api.HookContribution;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contributes Spark-backed profiling tools and a read-only aggregated stats resource. */
public final class SparkHook implements Hook {
    @Override
    public String id() {
        return "spark";
    }

    @Override
    public boolean isAvailable(Server server) {
        // Paper's bundled spark is not registered in Bukkit's plugin list, and its API is
        // published after EdenMcp enables. Detect the API/class instead of a Plugin instance.
        return SparkApi.isAvailable(server);
    }

    @Override
    public HookContribution contribute(HookContext ctx) {
        SparkApi sparkApi = new SparkApi(ctx.server());
        SparkDataStore store = SparkDataStore.create(ctx.server()).orElse(null);
        SparkCommandRunner runner = new SparkCommandRunner(ctx.edenMcp(), ctx.logger(), 10, 750);

        List<McpTool> tools = new ArrayList<>();
        tools.add(new CpuStatsTool(sparkApi));
        tools.add(new TpsStatsTool(sparkApi));
        tools.add(new MsptStatsTool(sparkApi));
        tools.add(new GcStatsTool(sparkApi));
        tools.add(new SparkProfilerInfoTool(runner));
        tools.add(new SparkProfilerTool(runner, store));
        tools.add(new SparkDiagnosticsTool(runner, store));
        if (store != null) {
            tools.add(new SparkDataListTool(store));
            tools.add(new SparkDataReadTool(store));
            tools.add(new SparkDataInspectTool(store));
        }

        McpResource stats = McpResource.text(
                "edenmcp://spark/stats",
                "Spark performance statistics",
                "Current CPU, TPS, MSPT and GC statistics reported by Spark.",
                "application/json",
                () -> {
                    try {
                        return sparkApi.snapshot();
                    } catch (Throwable t) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("source", "spark");
                        error.put("error", "Spark statistics unavailable: " + t.getMessage());
                        return error;
                    }
                }
        );

        return HookContribution.of(tools, List.of(stats));
    }
}
