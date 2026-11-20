package at.haha007.edenmcp.api;

import com.google.gson.Gson;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single MCP resource content entry returned by {@code resources/read}.
 *
 * <p>{@code text} may be any value; non-String values are serialized to JSON when
 * the response is built. {@code blob} is a base64-encoded string for binary
 * resources; at most one of the two is emitted.
 */
public record ResourceContents(@Nullable String uri, @Nullable String mimeType,
                               @Nullable Object text, @Nullable String blob) {
    private static final Gson GSON = new Gson();

    public static ResourceContents text(@Nullable String uri, @Nullable String mimeType,
                                        @Nullable Object content) {
        return new ResourceContents(uri, mimeType, content, null);
    }

    public static ResourceContents blob(@Nullable String uri, @Nullable String mimeType,
                                        @Nullable String base64) {
        return new ResourceContents(uri, mimeType, null, base64);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", uri == null ? "" : uri);
        if (mimeType != null) map.put("mimeType", mimeType);
        if (text != null) map.put("text", text instanceof String s ? s : GSON.toJson(text));
        if (blob != null) map.put("blob", blob);
        return map;
    }
}
