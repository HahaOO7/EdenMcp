package at.haha007.edenMcp.core.hook;

import at.haha007.edenMcp.core.tool.McpTool;

import java.util.List;
import java.util.Optional;

public record HookContribution(
        List<McpTool> tools,
        List<McpResource> resources,
        Optional<Runnable> onEnable,
        Optional<Runnable> onDisable
) {
    public static HookContribution ofTools(List<McpTool> tools) {
        return new HookContribution(List.copyOf(tools), List.of(), Optional.empty(), Optional.empty());
    }

    public static HookContribution empty() {
        return new HookContribution(List.of(), List.of(), Optional.empty(), Optional.empty());
    }
}
