package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes the protobuf wire structure of a saved Spark profile for analysis. */
final class SparkDataInspectTool implements McpTool {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final SparkDataStore store;

    SparkDataInspectTool(SparkDataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "eden.spark.data.inspect";
    }

    @Override
    public String description() {
        return "Inspect a raw Spark file's protobuf wire structure (field numbers, values, strings) without uploading it.";
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
        return List.of("spark", "profiling", "data", "analysis");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file", Map.of("type", "string", "description", "File name from eden.spark.data.list"),
                        "maxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 16,
                                "description", "Maximum nesting depth (default 8)"),
                        "maxNodes", Map.of("type", "integer", "minimum", 100, "maximum", 20000,
                                "description", "Maximum decoded fields (default 4000)"),
                        "maxBytes", Map.of("type", "integer", "minimum", 1024, "maximum", MAX_BYTES,
                                "description", "Maximum bytes to decode (default 262144)")
                ),
                "required", List.of("file")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String file = SparkArgs.string(args, "file", SparkDataStore.FILE_NAME);
            if (file == null) return ToolResult.error("Missing 'file'");
            int maxDepth = SparkArgs.integer(args, "maxDepth", 8, 1, 16);
            int maxNodes = SparkArgs.integer(args, "maxNodes", 4000, 100, 20000);
            int maxBytes = SparkArgs.integer(args, "maxBytes", 262144, 1024, MAX_BYTES);

            try (SparkDataStore.FileHandle handle = store.open(file)) {
                byte[] bytes = store.readUpTo(handle, maxBytes);
                ProtobufInspector.Result inspected = ProtobufInspector.inspect(bytes, maxDepth, maxNodes, maxBytes);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", handle.name());
                out.put("type", handle.type());
                out.put("size", handle.size());
                out.put("bytesInspected", inspected.bytesInspected());
                out.put("truncated", inspected.truncated());
                out.put("summary", inspected.summary());
                out.put("tree", inspected.tree());
                return ToolResult.ok(out);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unable to inspect Spark data: " + message(e));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
