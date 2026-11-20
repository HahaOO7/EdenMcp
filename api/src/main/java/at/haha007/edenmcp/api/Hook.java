package at.haha007.edenmcp.api;

import org.bukkit.Server;

/**
 * A named, owner-scoped contribution of MCP tools and resources.
 *
 * <p>Register through {@link EdenMcpApi#registerHook(org.bukkit.plugin.Plugin, Hook)}.
 * All lifecycle methods are invoked on the main server thread; {@code contribute()} must
 * be fast and non-blocking because it runs synchronously during registration/enable.
 */
public interface Hook {
    /** Unique stable id: {@code [a-z0-9][a-z0-9._-]{0,63}}, e.g. {@code "myplugin"}. */
    String id();

    /** Human-readable name for {@code edenmcp.hooks.list}. */
    default String displayName() {
        return id();
    }

    /** Return false to leave this hook disabled even when it is enabled in config. */
    default boolean isAvailable(Server server) {
        return true;
    }

    /** Build the contribution. Called again on every (re-)enable. */
    HookContribution contribute(HookContext ctx) throws Exception;

    default void onEnable() {
    }

    default void onDisable() {
    }
}
