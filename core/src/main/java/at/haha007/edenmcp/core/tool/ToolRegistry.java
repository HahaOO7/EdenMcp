package at.haha007.edenmcp.core.tool;

import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.McpTool;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolRegistry {
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    public synchronized void register(@Nullable McpTool tool) {
        if (tool == null || tool.name() == null) return;
        tools.put(tool.name(), tool);
    }

    @SuppressWarnings("unused") // registry API; prefix/instance removal is used internally
    public synchronized void unregister(String name) {
        tools.remove(name);
    }

    /**
     * Removes {@code tool} only when this registry still holds that exact instance.
     * Used for rollback so a failed hook cannot remove a same-named tool that was
     * contributed by another source.
     */
    public synchronized boolean unregisterIfRegistered(@Nullable McpTool tool) {
        if (tool == null) return false;
        String name = tool.name();
        if (name == null || tools.get(name) != tool) return false;
        tools.remove(name);
        return true;
    }

    public synchronized void unregisterByPrefix(String prefix) {
        tools.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public Optional<McpTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<McpTool> visible(OperatingMode mode) {
        return tools.values().stream()
                .filter(McpTool::exposed)
                .filter(t -> mode.allows(t.level()))
                .sorted(Comparator.comparing(McpTool::name))
                .toList();
    }

    public List<McpTool> all() {
        return tools.values().stream().sorted(Comparator.comparing(McpTool::name)).toList();
    }
}
