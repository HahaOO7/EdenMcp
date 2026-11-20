package at.haha007.edenmcp.api;

import org.jetbrains.annotations.Nullable;

/** Result of a tool invocation. Non-string content is serialized to JSON by EdenMcp. */
public record ToolResult(boolean success, @Nullable Object content, @Nullable String error) {
    public static ToolResult ok(@Nullable Object content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult error(@Nullable String message) {
        return new ToolResult(false, null, message);
    }
}
