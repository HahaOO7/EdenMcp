package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

public final class LogsReadTool implements McpTool {
    // The first character class excludes the quantifier characters, so the group
    // cannot be matched at multiple positions - avoids super-linear backtracking.
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("(\\([^)+*]*[+*][^)]*\\))[+*{]");
    @Nullable private final Path logFile;
    private final boolean requireModerate;
    private final int defaultLimit;
    private final int maxLimit;
    private final int maxScanBytes;
    private final int maxPatternLength;

    public LogsReadTool(@Nullable Path logFile, boolean requireModerate, EdenConfig.ToolConfig tools) {
        this.logFile = logFile;
        this.requireModerate = requireModerate;
        this.defaultLimit = tools.logsDefaultLimit();
        this.maxLimit = tools.logsMaxLimit();
        this.maxScanBytes = tools.logsMaxScanBytes();
        this.maxPatternLength = tools.logsMaxPatternLength();
    }

    @Override public String name() { return "eden.logs.read"; }
    @Override public String description() { return "Read recent server log lines with optional filter/limit (bounded)."; }
    @Override public CapabilityLevel level() { return requireModerate ? CapabilityLevel.MODERATE_ACTION : CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("logs"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer", "description",
                                "Max lines to return (default " + defaultLimit + ", max " + maxLimit + ")"),
                        "filter", Map.of("type", "string", "description", "Optional substring or regex filter"),
                        "regex", Map.of("type", "boolean", "description", "Treat filter as regex (default false)")
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        if (logFile == null || !Files.exists(logFile)) return ToolResult.error("Log file not available");
        int limit = defaultLimit;
        Object lim = context.arguments().get("limit");
        if (lim instanceof Number n) limit = Math.clamp(n.intValue(), 1, maxLimit);
        else if (lim instanceof String s) try { limit = Math.clamp(Integer.parseInt(s), 1, maxLimit); } catch (Exception ignored) {}

        String filter = context.arguments().get("filter") == null ? null : String.valueOf(context.arguments().get("filter"));
        boolean regex = Boolean.TRUE.equals(context.arguments().get("regex")) || "true".equals(String.valueOf(context.arguments().get("regex")));
        Pattern pattern = null;
        if (filter != null && !filter.isBlank() && regex) {
            if (filter.length() > maxPatternLength) return ToolResult.error("Regex filter is too long");
            if (NESTED_QUANTIFIER.matcher(filter).find()) return ToolResult.error("Regex filter rejected: nested quantifier");
            try {
                pattern = Pattern.compile(filter);
            } catch (Exception e) {
                return ToolResult.error("Invalid regex");
            }
        }
        try {
            List<String> lines = readTailLines(logFile, limit, filter, pattern);
            return ToolResult.ok(Map.of("file", logFile.toString(), "lines", lines, "count", lines.size()));
        } catch (IOException e) {
            return ToolResult.error("Failed to read logs");
        }
    }

    private List<String> readTailLines(Path file, int limit, @Nullable String filter,
                                       @Nullable Pattern pattern) throws IOException {
        long length = Files.size(file);
        if (length <= 0) return List.of();
        int toRead = (int) Math.min(length, maxScanBytes);
        byte[] buf = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(length - toRead);
            raf.readFully(buf);
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        String[] parts = text.split("\\R", -1);
        int start = length > toRead && parts.length > 0 ? 1 : 0;
        Deque<String> out = new ArrayDeque<>();
        for (int i = start; i < parts.length; i++) {
            String line = parts[i];
            if (i == parts.length - 1 && line.isEmpty()) continue;
            if (filter != null && !filter.isBlank()) {
                if (pattern != null) {
                    if (!pattern.matcher(line).find()) continue;
                } else if (!line.contains(filter)) {
                    continue;
                }
            }
            out.addLast(line);
            while (out.size() > limit) out.removeFirst();
        }
        return new ArrayList<>(out);
    }
}
