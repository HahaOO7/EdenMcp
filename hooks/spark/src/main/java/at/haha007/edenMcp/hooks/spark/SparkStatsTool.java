package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.List;
import java.util.Map;

abstract class SparkStatsTool implements McpTool {
    protected final SparkApi sparkApi;

    SparkStatsTool(SparkApi sparkApi) {
        this.sparkApi = sparkApi;
    }

    @Override
    public final CapabilityLevel level() {
        return CapabilityLevel.SAFE_READ;
    }

    @Override
    public final SideEffects sideEffects() {
        return SideEffects.NONE;
    }

    @Override
    public final List<String> tags() {
        return List.of("spark", "profiling", "statistics");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("window", Map.of(
                        "type", "string",
                        "description", "Optional polling window",
                        "enum", windows()))
        );
    }

    protected abstract List<String> windows();

    protected abstract Map<String, Object> poll(String window) throws Exception;

    @Override
    public final ToolResult execute(ToolContext context) {
        try {
            return ToolResult.ok(poll(window(context.arguments())));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Spark API error: " + message(e));
        }
    }

    protected final String window(Map<String, Object> args) {
        return SparkApi.Windows.resolve(args, aliases(), allowed());
    }

    protected abstract Map<String, String> aliases();

    protected abstract java.util.Set<String> allowed();

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
