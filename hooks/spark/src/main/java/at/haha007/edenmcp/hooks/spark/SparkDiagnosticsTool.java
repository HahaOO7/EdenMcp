package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs a bounded set of Spark monitoring/diagnostic commands. */
final class SparkDiagnosticsTool implements McpTool {
    private static final Set<String> COMMANDS = Set.of("tickmonitor", "gcmonitor", "heapdump", "tps", "gc");
    private static final Set<String> COMPRESSIONS = Set.of("gzip", "xz", "lzma");

    private final SparkCommandRunner runner;
    @Nullable private final SparkDataStore store;

    SparkDiagnosticsTool(SparkCommandRunner runner, @Nullable SparkDataStore store) {
        this.runner = runner;
        this.store = store;
    }

    @Override
    public String name() {
        return "edenmcp.spark.diagnostics.run";
    }

    @Override
    public String description() {
        return "Run a bounded Spark diagnostic: tick/gc monitor toggles, heap dump (local file), TPS or GC report. "
                + "tickmonitor without thresholds toggles the monitor; thresholds start it.";
    }

    @Override
    public CapabilityLevel level() {
        return CapabilityLevel.MODERATE_ACTION;
    }

    @Override
    public SideEffects sideEffects() {
        return SideEffects.OBSERVABLE;
    }

    @Override
    public List<String> tags() {
        return List.of("spark", "profiling", "diagnostics");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "enum", List.of("tickmonitor", "gcmonitor", "heapdump", "tps", "gc")),
                        "thresholdPercent", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                                "description", "tickmonitor: percentage increase over the average tick"),
                        "thresholdTickMillis", Map.of("type", "integer", "minimum", 1, "maximum", 60000,
                                "description", "tickmonitor: absolute tick duration; takes precedence over thresholdPercent"),
                        "withoutGc", Map.of("type", "boolean", "description", "tickmonitor: omit GC reports"),
                        "compress", Map.of("type", "string", "enum", List.of("gzip", "xz", "lzma"),
                                "description", "heapdump: compression type"),
                        "includeNonLive", Map.of("type", "boolean", "description", "heapdump: include unreachable objects"),
                        "runGcBefore", Map.of("type", "boolean", "description", "heapdump: suggest a GC first")
                ),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String command = SparkArgs.enumeration(args, "command", null, COMMANDS);
            if (command == null) return ToolResult.error("Missing 'command'");

            String sparkCommand = buildCommand(command, args);
            SparkCommandRunner.Result result = runner.run(sparkCommand, "diagnostics." + command, context);
            Map<String, Object> out = new LinkedHashMap<>(result.toMap());
            if ("heapdump".equals(command)) out.put("recentHeapDumps", recentHeapDumps());
            return ToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Spark command failed: " + message(e));
        }
    }

    String buildCommand(String command, Map<String, Object> args) {
        StringBuilder spark = new StringBuilder("spark ").append(command);
        switch (command) {
            case "tickmonitor" -> {
                Integer absolute = SparkArgs.integer(args, "thresholdTickMillis", null, 1, 60000);
                Integer percent = SparkArgs.integer(args, "thresholdPercent", null, 1, 1000);
                if (absolute != null) spark.append(" --threshold-tick ").append(absolute);
                else if (percent != null) spark.append(" --threshold ").append(percent);
                if (SparkArgs.bool(args, "withoutGc", false)) spark.append(" --without-gc");
            }
            case "heapdump" -> {
                String compression = SparkArgs.enumeration(args, "compress", null, COMPRESSIONS);
                if (compression != null) spark.append(" --compress ").append(compression);
                if (SparkArgs.bool(args, "includeNonLive", false)) spark.append(" --include-non-live");
                if (SparkArgs.bool(args, "runGcBefore", false)) spark.append(" --run-gc-before");
            }
            case "gcmonitor", "tps", "gc" -> {
                // No options supported.
            }
            default -> throw new IllegalArgumentException("Unsupported diagnostic command");
        }
        return spark.toString();
    }

    private List<Map<String, Object>> recentHeapDumps() {
        if (store == null) return List.of();
        try {
            return store.list("heapdump", 3).files();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
