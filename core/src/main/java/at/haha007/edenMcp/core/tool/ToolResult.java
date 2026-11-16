package at.haha007.edenMcp.core.tool;

import java.util.Map;

public record ToolResult(boolean success, Object content, String error) {
    public static ToolResult ok(Object content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message);
    }

    public Map<String, Object> toMap() {
        if (success) {
            return Map.of("ok", true, "content", content == null ? Map.of() : content);
        }
        return Map.of("ok", false, "error", error == null ? "unknown error" : error);
    }
}
