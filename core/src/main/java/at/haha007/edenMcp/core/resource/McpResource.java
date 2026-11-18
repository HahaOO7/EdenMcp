package at.haha007.edenMcp.core.resource;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A read-only MCP resource contributed by core or by a hook.
 *
 * <p>Metadata is advertised by {@code resources/list}; {@link #read(ResourceContext)}
 * supplies the content for {@code resources/read}. Resources are observations only:
 * they never carry a capability level above {@code SAFE_READ} and are visible in
 * every operating mode, just like the read-only tools in safe mode.
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType,
        Map<String, Object> metadata,
        ResourceReader reader
) {
    public McpResource {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(reader, "reader");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience for a resource whose content is produced by a supplier. */
    public static McpResource text(String uri, String name, String description, String mimeType,
                                   Supplier<Object> content) {
        Objects.requireNonNull(content, "content");
        return new McpResource(uri, name, description, mimeType, Map.of(),
                ctx -> ResourceContents.text(ctx.uri(), mimeType, content.get()));
    }

    public ResourceContents read(ResourceContext context) throws Exception {
        return reader.read(context);
    }
}
