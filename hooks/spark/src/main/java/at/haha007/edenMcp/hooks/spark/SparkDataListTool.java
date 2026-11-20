package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SparkDataListTool implements McpTool {
    private static final Set<String> KINDS = Set.of("all", "profile", "heapdump");

    private final SparkDataStore store;

    SparkDataListTool(SparkDataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "eden.spark.data.list";
    }

    @Override
    public String description() {
        return "List raw Spark profile (.sparkprofile) and heap dump (.hprof) files saved on this server.";
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
        return List.of("spark", "profiling", "data");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("type", "string", "enum", List.of("all", "profile", "heapdump"),
                                "description", "Filter by saved artifact type"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                                "description", "Maximum number of files to return (default 50)")
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String kind = SparkArgs.enumeration(args, "kind", "all", KINDS);
            int limit = SparkArgs.integer(args, "limit", 50, 1, 200);
            List<Map<String, Object>> files = store.list(kind, limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", files.size());
            out.put("files", files);
            return ToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unable to list Spark data: " + message(e));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
