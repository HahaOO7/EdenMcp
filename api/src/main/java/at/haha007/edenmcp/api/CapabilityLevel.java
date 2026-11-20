package at.haha007.edenmcp.api;

/**
 * Minimum operating mode required to execute a contributed tool.
 *
 * <p>This is enforced by EdenMcp before a tool is advertised or invoked; a hook
 * cannot grant itself a higher level than it declares here.
 */
public enum CapabilityLevel {
    SAFE_READ,
    MODERATE_ACTION,
    UNSAFE_ACTION
}
