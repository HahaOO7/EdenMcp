package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.core.audit.AuditLogger;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.util.ServerFileAccess;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Creates or overwrites UTF-8 text files under the server directory (unsafe mode only). */
public final class FileWriteTool implements McpTool {
    private final ServerFileAccess files;
    private final int maxBytes;
    private final AuditLogger audit;

    public FileWriteTool(ServerFileAccess files, int maxBytes, AuditLogger audit) {
        this.files = files;
        this.maxBytes = maxBytes;
        this.audit = audit;
    }

    @Override public String name() { return "edenmcp.file.write"; }
    @Override public String description() {
        return "Create or overwrite a UTF-8 text file in the server directory (unsafe only).";
    }
    @Override public CapabilityLevel level() { return CapabilityLevel.UNSAFE_ACTION; }
    @Override public SideEffects sideEffects() { return SideEffects.MUTATING; }
    @Override public List<String> tags() { return List.of("files"); }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "File path relative to the server directory"),
                        "content", Map.of("type", "string", "description", "New UTF-8 file content")
                ),
                "required", List.of("path", "content")
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        // Defense in depth: the registry hides this tool below unsafe, but do not
        // rely solely on discovery filtering for a filesystem mutation.
        if (context.activeMode() != OperatingMode.UNSAFE) {
            return ToolResult.error("File writes require unsafe mode");
        }
        Object rawPath = context.arguments().get("path");
        Object rawContent = context.arguments().get("content");
        if (!(rawPath instanceof String path) || path.isBlank()) return ToolResult.error("Missing or invalid path");
        if (!(rawContent instanceof String content)) return ToolResult.error("Missing or invalid content");
        try {
            ServerFileAccess.TextFile file = files.write(path, content, maxBytes);
            audit.fileWrite(context.callerId(), file.path(), context.activeMode().name());
            return ToolResult.ok(Map.of("path", file.path(), "bytes", file.bytes()));
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }
}
