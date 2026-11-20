package at.haha007.edenmcp.api;

/**
 * Context supplied to a resource reader for a single {@code resources/read} request.
 * Resources are read-only observations, so the active mode is informational here;
 * access-key and session enforcement happen in the transport before a read occurs.
 */
public record ResourceContext(
        OperatingMode activeMode,
        String callerId,
        String uri
) {
}
