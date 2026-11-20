package at.haha007.edenmcp.api;

import org.bukkit.plugin.Plugin;

/**
 * Handle for a registration made through {@link EdenMcpApi}.
 *
 * <p>This is intentionally not {@link AutoCloseable}: registrations normally live for the
 * whole lifetime of the owning plugin and are cleaned up by EdenMcp when it disables.
 */
public interface Registration {
    Plugin owner();

    /** Hook id, tool name, or resource URI. */
    String id();

    RegistrationState state();

    /** Idempotent; disables and removes the contribution. */
    boolean unregister();

    /** Alias for {@link #unregister()}. */
    default void close() {
        unregister();
    }

    enum RegistrationState {
        ENABLED,
        DISABLED,
        FAILED,
        UNREGISTERED
    }
}
