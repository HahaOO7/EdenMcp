package at.haha007.edenMcp.core.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        @NotNull String uri,
        @NotNull String name,
        @Nullable String description,
        @Nullable String mimeType,
        Map<String, Object> metadata,
        @NotNull ResourceReader reader
) {
    public McpResource {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(reader, "reader");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience for a resource whose content is produced by a supplier. */
    public static McpResource text(@NotNull String uri, @NotNull String name, @Nullable String description,
                                   @Nullable String mimeType, @NotNull Supplier<Object> content) {
        Objects.requireNonNull(content, "content");
        return new McpResource(uri, name, description, mimeType, Map.of(),
                ctx -> ResourceContents.text(ctx.uri(), mimeType, content.get()));
    }

    public ResourceContents read(@NotNull ResourceContext context) throws Exception {
        return reader.read(context);
    }
}
