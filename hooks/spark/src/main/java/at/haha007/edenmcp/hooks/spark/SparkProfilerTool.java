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

/** Starts/stops/cancels the Spark profiler using only pre-validated command fragments. */
final class SparkProfilerTool implements McpTool {
    private static final Set<String> ACTIONS = Set.of("start", "stop", "cancel");
    private static final Set<String> MODES = Set.of("cpu", "alloc", "lock");

    private final SparkCommandRunner runner;
    @Nullable private final SparkDataStore store;

    SparkProfilerTool(SparkCommandRunner runner, @Nullable SparkDataStore store) {
        this.runner = runner;
        this.store = store;
    }

    @Override
    public String name() {
        return "edenmcp.spark.profiler.control";
    }

    @Override
    public String description() {
        return "Control Spark's profiler. Results are always saved locally to disk, never uploaded.";
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
        return List.of("spark", "profiling", "control");
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("start", "stop", "cancel"),
                "description", "start, stop (save results locally) or cancel (discard)"));
        properties.put("mode", Map.of("type", "string", "enum", List.of("cpu", "alloc", "lock"),
                "description", "Profiler mode for 'start' (default cpu)"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 11, "maximum", 3600,
                "description", "Auto-stop after this many seconds"));
        properties.put("intervalMillis", Map.of("type", "integer", "minimum", 1, "maximum", 5000,
                "description", "CPU/lock sampling interval in milliseconds"));
        properties.put("allocationIntervalBytes", Map.of("type", "integer", "minimum", 1024, "maximum", 1073741824,
                "description", "Allocation sampling interval in bytes"));
        properties.put("thread", Map.of("type", "string", "description", "Thread name, '*' for all threads, or a regex"));
        properties.put("threadRegex", Map.of("type", "boolean", "description", "Treat 'thread' as a regular expression"));
        properties.put("onlyTicksOverMillis", Map.of("type", "integer", "minimum", 1, "maximum", 60000,
                "description", "Only record samples from ticks longer than this"));
        properties.put("ignoreSleeping", Map.of("type", "boolean"));
        properties.put("forceJavaSampler", Map.of("type", "boolean"));
        properties.put("combineAll", Map.of("type", "boolean"));
        properties.put("notCombined", Map.of("type", "boolean"));
        properties.put("allocLiveOnly", Map.of("type", "boolean"));
        properties.put("comment", Map.of("type", "string", "description", "Optional comment stored with the profile"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String action = SparkArgs.enumeration(args, "action", null, ACTIONS);
            if (action == null) return ToolResult.error("Missing 'action'");

            String command = buildCommand(action, args);
            SparkCommandRunner.Result result = runner.run(command, "profiler." + action, context);
            Map<String, Object> out = new LinkedHashMap<>(result.toMap());
            out.put("storage", "local-only");
            out.put("recentProfiles", recentProfiles());
            return ToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Spark command failed: " + message(e));
        }
    }

    String buildCommand(String action, Map<String, Object> args) {
        StringBuilder command = new StringBuilder("spark profiler ").append(action);
        String comment = SparkArgs.optionalText(args);

        if ("cancel".equals(action)) return command.toString();

        if ("stop".equals(action)) {
            command.append(" --save-to-file");
            if (comment != null) command.append(" --comment ").append(SparkArgs.quote(comment));
            return command.toString();
        }

        String mode = SparkArgs.enumeration(args, "mode", "cpu", MODES);
        if ("alloc".equals(mode)) {
            command.append(" --alloc");
            Integer allocInterval = SparkArgs.integer(args, "allocationIntervalBytes", null, 1024, 1073741824);
            if (allocInterval != null) command.append(" --interval ").append(allocInterval);
            if (SparkArgs.bool(args, "allocLiveOnly", false)) command.append(" --alloc-live-only");
        } else {
            if ("lock".equals(mode)) command.append(" --lock");
            Integer interval = SparkArgs.integer(args, "intervalMillis", null, 1, 5000);
            if (interval != null) command.append(" --interval ").append(interval);
            Integer onlyTicks = SparkArgs.integer(args, "onlyTicksOverMillis", null, 1, 60000);
            if (onlyTicks != null) command.append(" --only-ticks-over ").append(onlyTicks);
            if (SparkArgs.bool(args, "ignoreSleeping", false)) command.append(" --ignore-sleeping");
            if (SparkArgs.bool(args, "forceJavaSampler", false)) command.append(" --force-java-sampler");
        }

        Integer timeout = SparkArgs.integer(args, "timeoutSeconds", null, 11, 3600);
        if (timeout != null) command.append(" --timeout ").append(timeout);

        String thread = SparkArgs.optionalThread(args);
        if (thread != null) {
            command.append(" --thread ").append(SparkArgs.quote(thread));
            if (SparkArgs.bool(args, "threadRegex", false)) command.append(" --regex");
        }
        if (SparkArgs.bool(args, "combineAll", false)) command.append(" --combine-all");
        if (SparkArgs.bool(args, "notCombined", false)) command.append(" --not-combined");
        if (comment != null) command.append(" --comment ").append(SparkArgs.quote(comment));

        // Always save locally: uploading profiling data to a third party must be opt-in elsewhere.
        command.append(" --save-to-file");
        return command.toString();
    }

    private List<Map<String, Object>> recentProfiles() {
        if (store == null) return List.of();
        try {
            return store.list("profile", 3).files();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
