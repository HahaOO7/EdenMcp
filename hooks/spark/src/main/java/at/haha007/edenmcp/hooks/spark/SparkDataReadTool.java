package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Returns raw Spark bytes as base64 in bounded chunks. */
final class SparkDataReadTool implements McpTool {
    private static final int MAX_LENGTH = 8 * 1024 * 1024;

    private final SparkDataStore store;

    SparkDataReadTool(SparkDataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "edenmcp.spark.data.read";
    }

    @Override
    public String description() {
        return "Read a chunk of a raw Spark profile or heap dump as base64, with bounded size.";
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
                        "file", Map.of("type", "string", "description", "File name from edenmcp.spark.data.list"),
                        "offset", Map.of("type", "integer", "minimum", 0,
                                "description", "Byte offset (default 0)"),
                        "length", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LENGTH,
                                "description", "Maximum bytes to return (default 65536)")
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
            long offset = SparkArgs.offset(args);
            int length = SparkArgs.integer(args, "length", 65536, 1, MAX_LENGTH);

            try (SparkDataStore.FileHandle handle = store.open(file)) {
                SparkDataStore.Chunk chunk = store.read(handle, offset, length);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", handle.name());
                out.put("type", handle.type());
                out.put("mimeType", handle.mimeType());
                out.put("size", handle.size());
                out.put("offset", offset);
                out.put("returned", chunk.returned());
                // Subtract instead of adding to avoid overflowing for offsets near Long.MAX_VALUE.
                out.put("truncated", chunk.returned() < handle.size() - offset);
                out.put("sha256OfChunk", chunk.sha256());
                out.put("encoding", "base64");
                out.put("data", Base64.getEncoder().encodeToString(chunk.bytes()));
                return ToolResult.ok(out);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unable to read Spark data: " + message(e));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
