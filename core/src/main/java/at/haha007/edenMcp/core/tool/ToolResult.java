package at.haha007.edenMcp.core.tool;

import org.jetbrains.annotations.Nullable;

public record ToolResult(boolean success, @Nullable Object content, @Nullable String error) {
    public static ToolResult ok(@Nullable Object content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult error(@Nullable String message) {
        return new ToolResult(false, null, message);
    }
}
