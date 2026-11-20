package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.List;
import java.util.Map;

final class SparkProfilerInfoTool implements McpTool {
    private final SparkCommandRunner runner;

    SparkProfilerInfoTool(SparkCommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "eden.spark.profiler.info";
    }

    @Override
    public String description() {
        return "Read Spark's current profiler status (running, duration, expected completion).";
    }

    @Override
    public CapabilityLevel level() {
        return CapabilityLevel.SAFE_READ;
    }

    @Override
    public SideEffects sideEffects() {
        return SideEffects.NONE;
    }

    @Override
    public List<String> tags() {
        return List.of("spark", "profiling", "status");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            return ToolResult.ok(runner.run("spark profiler info", "profiler.info", context).toMap());
        } catch (Exception e) {
            return ToolResult.error("Spark command failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
