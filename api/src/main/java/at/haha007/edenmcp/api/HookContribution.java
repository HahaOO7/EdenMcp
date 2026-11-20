package at.haha007.edenmcp.api;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Tools and resources produced by a {@link Hook}. Lifecycle belongs on the hook itself. */
public record HookContribution(List<McpTool> tools, List<McpResource> resources) {
    public HookContribution {
        tools = tools == null ? List.of() : List.copyOf(tools);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public static HookContribution of(@Nullable List<McpTool> tools, @Nullable List<McpResource> resources) {
        return new HookContribution(tools, resources);
    }

    public static HookContribution tools(McpTool... tools) {
        return new HookContribution(List.of(tools), List.of());
    }

    public static HookContribution empty() {
        return new HookContribution(List.of(), List.of());
    }

    /** @deprecated use {@link #of(List, List)}; lifecycle is now on {@link Hook}. */
    @Deprecated
    public static HookContribution ofTools(@Nullable List<McpTool> tools) {
        return of(tools, List.of());
    }

    /** @deprecated use {@link #of(List, List)}; lifecycle is now on {@link Hook}. */
    @Deprecated
    public static HookContribution ofToolsAndResources(@Nullable List<McpTool> tools,
                                                       @Nullable List<McpResource> resources) {
        return of(tools, resources);
    }
}
