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

/** Lists files and directories under the server directory so clients can discover paths. */
public final class FileListTool implements McpTool {
    private static final int MAX_PATTERN_LENGTH = 200;

    private final ServerFileAccess files;
    private final int maxEntries;
    private final int maxDepth;

    public FileListTool(ServerFileAccess files, int maxEntries, int maxDepth) {
        this.files = files;
        this.maxEntries = maxEntries;
        this.maxDepth = maxDepth;
    }

    @Override public String name() { return "edenmcp.file.list"; }
    @Override public String description() {
        return "List entries in a directory under the server directory (relative path; default: server root). " +
                "Supports recursive listing with a depth cap and an optional glob filter on relative paths.";
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
                                "description", "Directory path relative to the server directory (empty for root)"),
                        "recursive", Map.of("type", "boolean", "default", false,
                                "description", "Descend into subdirectories (symlinks are never followed)"),
                        "depth", Map.of("type", "integer", "minimum", 1, "maximum", maxDepth,
                                "description", "Maximum directory depth when recursive"),
                        "pattern", Map.of("type", "string",
                                "description", "Optional glob matched against server-relative paths")
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object raw = context.arguments().get("path");
        if (raw != null && !(raw instanceof String)) return ToolResult.error("Invalid path");
        String path = raw == null ? "" : (String) raw;

        Object rawPattern = context.arguments().get("pattern");
        if (rawPattern != null && !(rawPattern instanceof String)) return ToolResult.error("Invalid pattern");
        String pattern = (String) rawPattern;
        if (pattern != null && pattern.length() > MAX_PATTERN_LENGTH) {
            return ToolResult.error("Pattern is too long (max " + MAX_PATTERN_LENGTH + " characters)");
        }

        boolean recursive;
        int depth;
        try {
            recursive = ToolArgs.bool(context.arguments(), "recursive");
            depth = recursive
                    ? ToolArgs.integer(context.arguments(), "depth", Math.min(5, maxDepth), 1, maxDepth)
                    : 1;
            ServerFileAccess.Listing listing = files.list(path, maxEntries, recursive, depth, pattern);
            return ToolResult.ok(Map.of("path", path.isBlank() ? "." : path,
                    "entries", listing.entries(), "count", listing.entries().size(),
                    "total", listing.total(), "hasMore", listing.hasMore(),
                    "truncated", listing.truncated(), "recursive", recursive, "depth", depth));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }
}
