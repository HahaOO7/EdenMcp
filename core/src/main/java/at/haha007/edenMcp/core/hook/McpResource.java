package at.haha007.edenMcp.core.hook;

import java.util.Map;

public record McpResource(String uri, String name, String description, String mimeType, Map<String, Object> metadata) {
}
