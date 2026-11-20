package at.haha007.edenmcp.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Read-only descriptor backing {@code edenmcp.hooks.list} and {@link EdenMcpApi#hooks()}. */
public record HookInfo(
        String id,
        String displayName,
        String source,
        @Nullable Plugin owner,
        boolean enabledInConfig,
        boolean available,
        boolean enabled,
        java.util.List<String> tools,
        java.util.List<String> resources,
        java.util.List<String> skipped,
        @Nullable String lastError
) {
}
