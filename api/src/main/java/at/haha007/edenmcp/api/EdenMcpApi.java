package at.haha007.edenmcp.api;

import org.bukkit.plugin.Plugin;

/**
 * Public entrypoint, published under Bukkit's {@code ServicesManager}.
 *
 * <pre>{@code
 * var provider = Bukkit.getServicesManager().getRegistration(EdenMcpApi.class);
 * Registration reg = provider.getProvider().registerHook(this, myHook);
 * }</pre>
 *
 * <p>All methods must be called on the main server thread.
 */
public interface EdenMcpApi {
    HookRegistration registerHook(Plugin owner, Hook hook) throws HookRegistrationException;

    /** Wraps a single tool in an internal, owner-named hook. */
    Registration registerTool(Plugin owner, McpTool tool) throws HookRegistrationException;

    /** Wraps a single resource in an internal, owner-named hook. */
    Registration registerResource(Plugin owner, McpResource resource) throws HookRegistrationException;

    java.util.List<HookInfo> hooks();

    /** False once EdenMcp has begun shutting down. */
    @SuppressWarnings("unused") // public API for consumers, not called inside EdenMcp
    boolean isAcceptingRegistrations();
}
