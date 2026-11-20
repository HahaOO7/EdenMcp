package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.config.EdenConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists available log files (plain, .gz, .zip) in the server's logs directory. */
public final class LogsListTool implements McpTool {
    private final LogFileAccess logs;
    private final boolean requireModerate;
    private final int maxEntries;

    public LogsListTool(Path logsDir, boolean requireModerate, EdenConfig.ToolConfig tools) {
        this.logs = new LogFileAccess(logsDir, tools.logsMaxDecompressedBytes());
        this.requireModerate = requireModerate;
        this.maxEntries = tools.listMaxEntries();
    }

    @Override public String name() { return "edenmcp.logs.list"; }
    @Override public String description() { return "List log files available to edenmcp.logs.read (bounded)."; }
    @Override public CapabilityLevel level() { return requireModerate ? CapabilityLevel.MODERATE_ACTION : CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("logs"); }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("limit",
                Map.of("type", "integer", "minimum", 1, "maximum", maxEntries,
                        "description", "Maximum number of log files to return (default " + maxEntries + ")")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit;
        try {
            limit = ToolArgs.integer(context.arguments(), "limit", maxEntries, 1, maxEntries);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        try {
            LogFileAccess.Listing listing = logs.list(limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("files", listing.files());
            out.put("count", listing.files().size());
            out.put("total", listing.total());
            out.put("hasMore", listing.hasMore());
            return ToolResult.ok(out);
        } catch (IOException e) {
            return ToolResult.error("Failed to list logs: " + e.getMessage());
        }
    }
}
