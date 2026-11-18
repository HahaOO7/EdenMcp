package at.haha007.edenMcp.core.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceRegistryTest {
    private static McpResource resource(String uri, Object content) {
        return McpResource.text(uri, uri, "test", "text/plain", () -> content);
    }

    @Test
    void registersAndListsResourcesSortedByUri() {
        ResourceRegistry registry = new ResourceRegistry();
        McpResource b = resource("eden://b", "b");
        McpResource a = resource("eden://a", "a");
        registry.register(b);
        registry.register(a);
        registry.register(null);
        registry.register(resource("", "blank"));

        assertEquals(List.of(a, b), registry.all());
        assertSame(a, registry.get("eden://a").orElseThrow());
        assertTrue(registry.get(null).isEmpty());
    }

    @Test
    void unregisterIfRegisteredIsInstanceSafe() {
        ResourceRegistry registry = new ResourceRegistry();
        McpResource owner = resource("eden://same", "owner");
        registry.register(owner);

        assertFalse(registry.unregisterIfRegistered(resource("eden://same", "other")));
        assertSame(owner, registry.get("eden://same").orElseThrow());
        assertTrue(registry.unregisterIfRegistered(owner));
        assertFalse(registry.unregisterIfRegistered(owner));
        assertTrue(registry.get("eden://same").isEmpty());
    }

    @Test
    void blobContentsKeepBase64PayloadSeparateFromText() {
        Map<String, Object> map = ResourceContents.blob("eden://image", "image/png", "AQID").toMap();

        assertEquals("eden://image", map.get("uri"));
        assertEquals("image/png", map.get("mimeType"));
        assertEquals("AQID", map.get("blob"));
        assertFalse(map.containsKey("text"));
    }
}
