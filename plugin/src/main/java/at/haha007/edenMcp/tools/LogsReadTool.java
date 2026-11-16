package at.haha007.edenMcp.tools;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
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

public final class LogsReadTool implements McpTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_SCAN_BYTES = 1024 * 1024; // 1 MiB tail scan budget
    private final Path logFile;

    public LogsReadTool(Path logFile) {
        this.logFile = logFile;
    }

    @Override public String name() { return "eden.logs.read"; }
    @Override public String description() { return "Read recent server log lines with optional filter/limit (bounded)."; }
    @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
    @Override public SideEffects sideEffects() { return SideEffects.NONE; }
    @Override public List<String> tags() { return List.of("logs"); }
    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer", "description", "Max lines to return (default 100, max 500)"),
                        "filter", Map.of("type", "string", "description", "Optional substring or regex filter"),
                        "regex", Map.of("type", "boolean", "description", "Treat filter as regex (default false)")
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        if (logFile == null || !Files.exists(logFile)) {
            return ToolResult.error("Log file not available");
        }
        int limit = DEFAULT_LIMIT;
        Object lim = context.arguments().get("limit");
        if (lim instanceof Number n) limit = Math.max(1, Math.min(MAX_LIMIT, n.intValue()));
        else if (lim instanceof String s) try { limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(s))); } catch (Exception ignored) {}

        String filter = context.arguments().get("filter") == null ? null : String.valueOf(context.arguments().get("filter"));
        boolean regex = Boolean.TRUE.equals(context.arguments().get("regex")) || "true".equals(String.valueOf(context.arguments().get("regex")));
        Pattern pattern = null;
        if (filter != null && !filter.isBlank() && regex) {
            try { pattern = Pattern.compile(filter); } catch (Exception e) { return ToolResult.error("Invalid regex: " + e.getMessage()); }
        }
        try {
            List<String> lines = readTailLines(logFile, limit, filter, pattern, MAX_SCAN_BYTES);
            return ToolResult.ok(Map.of("file", logFile.toString(), "lines", lines, "count", lines.size()));
        } catch (IOException e) {
            return ToolResult.error("Failed to read logs: " + e.getMessage());
        }
    }

    private static List<String> readTailLines(Path file, int limit, String filter, Pattern pattern, int maxScanBytes) throws IOException {
        long length = Files.size(file);
        if (length <= 0) return List.of();
        int toRead = (int) Math.min(length, (long) maxScanBytes);
        byte[] buf = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(length - toRead);
            raf.readFully(buf);
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        String[] parts = text.split("\\R", -1);
        int start = 0;
        // if we didn't read from beginning, first line may be partial; drop it
        if (length > toRead && parts.length > 0) {
            start = 1;
        }
        Deque<String> out = new ArrayDeque<>();
        for (int i = start; i < parts.length; i++) {
            String line = parts[i];
            if (i == parts.length - 1 && line.isEmpty()) continue; // trailing newline artifact
            if (filter != null && !filter.isBlank()) {
                if (pattern != null) {
                    if (!pattern.matcher(line).find()) continue;
                } else {
                    if (!line.contains(filter)) continue;
                }
            }
            out.addLast(line);
            while (out.size() > limit) out.removeFirst();
        }
        return new ArrayList<>(out);
    }
}
