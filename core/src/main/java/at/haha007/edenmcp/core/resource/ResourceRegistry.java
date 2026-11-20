package at.haha007.edenmcp.core.resource;

import at.haha007.edenmcp.api.McpResource;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collection of resources advertised by {@code resources/list} and readable via
 * {@code resources/read}. Keyed by URI, mirroring {@code ToolRegistry}.
 * Resources are read-only observations, so they are available in every mode.
 */
public final class ResourceRegistry {
    private final Map<String, McpResource> resources = new ConcurrentHashMap<>();

    public synchronized void register(@Nullable McpResource resource) {
        if (resource == null || resource.uri().isBlank()) return;
        resources.put(resource.uri(), resource);
    }

    @SuppressWarnings("unused") // registry API; prefix/instance removal is used internally
    public synchronized void unregister(@Nullable String uri) {
        if (uri != null) resources.remove(uri);
    }

    /**
     * Removes {@code resource} only when this registry still holds that exact instance,
     * so a failed hook cannot remove a same-URI resource contributed elsewhere.
     */
    public synchronized boolean unregisterIfRegistered(@Nullable McpResource resource) {
        if (resource == null) return false;
        String uri = resource.uri();
        if (resources.get(uri) != resource) return false;
        resources.remove(uri);
        return true;
    }

    public synchronized void unregisterByPrefix(String prefix) {
        resources.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public Optional<McpResource> get(@Nullable String uri) {
        return Optional.ofNullable(uri == null ? null : resources.get(uri));
    }

    public List<McpResource> all() {
        return resources.values().stream().sorted(Comparator.comparing(McpResource::uri)).toList();
    }
}
