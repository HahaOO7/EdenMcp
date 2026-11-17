package at.haha007.edenMcp.core.tool;

import at.haha007.edenMcp.core.mode.OperatingMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolRegistry {
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    public synchronized void register(McpTool tool) {
        if (tool == null || tool.name() == null) return;
        tools.put(tool.name(), tool);
    }

    public synchronized void unregister(String name) {
        tools.remove(name);
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
