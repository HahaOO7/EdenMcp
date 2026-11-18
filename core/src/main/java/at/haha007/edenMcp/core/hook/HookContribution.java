package at.haha007.edenMcp.core.hook;

import at.haha007.edenMcp.core.resource.McpResource;
import at.haha007.edenMcp.core.tool.McpTool;

import java.util.List;
import java.util.Optional;

public record HookContribution(
        List<McpTool> tools,
        List<McpResource> resources,
        Optional<Runnable> onEnable,
        Optional<Runnable> onDisable
) {
    public HookContribution {
        tools = tools == null ? List.of() : List.copyOf(tools);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public static HookContribution ofTools(List<McpTool> tools) {
        return ofToolsAndResources(tools, List.of());
    }

    public static HookContribution ofToolsAndResources(List<McpTool> tools, List<McpResource> resources) {
        return new HookContribution(tools, resources, Optional.empty(), Optional.empty());
    }

    public static HookContribution empty() {
        return new HookContribution(List.of(), List.of(), Optional.empty(), Optional.empty());
    }
}
