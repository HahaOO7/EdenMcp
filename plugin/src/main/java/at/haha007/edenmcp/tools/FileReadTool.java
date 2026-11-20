package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.util.ServerFileAccess;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Reads UTF-8 text files from anywhere under the server directory. */
public final class FileReadTool implements McpTool {
    private final ServerFileAccess files;
    private final int maxBytes;

    public FileReadTool(ServerFileAccess files, int maxBytes) {
        this.files = files;
        this.maxBytes = maxBytes;
    }

    @Override public String name() { return "edenmcp.file.read"; }
    @Override public String description() {
        return "Read a UTF-8 text file in the server directory (path relative to the server root). " +
                "Use offset/limit to page through long files; hasMore reports whether lines remain.";
    }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("files"); }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "File path relative to the server directory"),
                        "offset", Map.of("type", "integer", "minimum", 0, "default", 0,
                                "description", "Zero-based index of the first line to return"),
                        "limit", Map.of("type", "integer", "minimum", 1,
                                "description", "Maximum number of lines to return (default: whole file)")
                ),
                "required", List.of("path")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object raw = context.arguments().get("path");
        if (!(raw instanceof String path) || path.isBlank()) return ToolResult.error("Missing or invalid path");
        int offset;
        int limit;
        try {
            offset = ToolArgs.integer(context.arguments(), "offset", 0, 0, Integer.MAX_VALUE);
            limit = ToolArgs.integer(context.arguments(), "limit", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        try {
            ServerFileAccess.TextFile file = files.read(path, maxBytes, offset, limit);
            return ToolResult.ok(Map.of("path", file.path(), "content", file.content(), "bytes", file.bytes(),
                    "lineStart", file.lineStart(), "lineCount", file.lineCount(), "hasMore", file.hasMore()));
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }
}
