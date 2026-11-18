package at.haha007.edenMcp.core.transport;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.config.CommandClassification;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookContext;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.resource.McpResource;
import at.haha007.edenMcp.core.resource.ResourceContents;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpServerResourceTest {
    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("resource-test-" + System.nanoTime());
    private McpHttpServer server;
    private String key;
    private String session;

    @BeforeEach
    void start() throws Exception {
        Path data = dir.resolve("data-" + System.nanoTime());
        Files.createDirectories(data);
        Path keyFile = data.resolve(".key");
        AccessKeyManager keyManager = new AccessKeyManager(keyFile, logger);
        keyManager.loadOrCreate();
        key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

        EdenConfig.TlsConfig tls = new EdenConfig.TlsConfig(false, "self-signed", "", "", "", "", false, "", "",
                2048, 3650);
        EdenConfig.TransportConfig transport = new EdenConfig.TransportConfig(
                "http-sse", "127.0.0.1", 0, List.of(), List.of(), 1024 * 1024, 20, 20, 1800, 100,
                8, 32, 60, 256, 1, 15000, 30000, 600, 1000, 8192, tls);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(false, 10, 60, 0, null),
                new EdenConfig.PolicyConfig(true, false),
                EdenConfig.ToolConfig.defaults(),
                true, true, false, 200, Map.of(), CommandClassification.empty());

        ToolRegistry tools = new ToolRegistry();
        HookManager hooks = new HookManager(tools, new HookContext(null, null, logger, new AuditLogger(logger)));
        hooks.resources().register(McpResource.text("eden://test/broken", "Broken", "Fails on read", "text/plain",
                () -> { throw new IllegalStateException("reader exploded"); }));
        hooks.resources().register(new McpResource("eden://test/map", "Map", "JSON resource", "application/json",
                Map.of("source", "test"), ctx -> ResourceContents
                        .text(ctx.uri(), "application/json", Map.of("value", 42, "caller", ctx.callerId()))));
        hooks.resources().register(McpResource.text("eden://test/text", "Text", "Plain resource", "text/plain",
                () -> "plain text"));

        server = new McpHttpServer(config, keyManager, tools, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        server.start(data);

        JsonObject init = post("initialize", new JsonObject(), null);
        session = init.getAsJsonObject("result").get("sessionId").getAsString();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private JsonObject post(String method, JsonObject params, String sessionId) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", 1);
        req.addProperty("method", method);
        req.add("params", params);
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + "/mcp").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        if (sessionId != null) c.setRequestProperty("Mcp-Session-Id", sessionId);
        try (OutputStream os = c.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }
        String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = c.getResponseCode();
        c.disconnect();
        assertEquals(200, status);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private JsonObject readResponse(String uri) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return post("resources/read", params, session);
    }

    private JsonObject read(String uri) throws Exception {
        return readResponse(uri).getAsJsonObject("result");
    }

    @Test
    void resourcesListAdvertisesContributedResourcesIncludingMetadata() throws Exception {
        JsonObject result = post("resources/list", new JsonObject(), session).getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertNotNull(resources);
        assertEquals(3, resources.size());

        JsonObject map = resources.get(1).getAsJsonObject(); // sorted by uri: broken, map, text
        assertEquals("eden://test/map", map.get("uri").getAsString());
        assertEquals("Map", map.get("name").getAsString());
        assertEquals("JSON resource", map.get("description").getAsString());
        assertEquals("application/json", map.get("mimeType").getAsString());
        assertEquals("test", map.getAsJsonObject("_meta").get("source").getAsString());
    }

    @Test
    void resourcesReadReturnsMcpContent() throws Exception {
        JsonObject result = read("eden://test/map");
        JsonObject content = result.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("eden://test/map", content.get("uri").getAsString());
        assertEquals("application/json", content.get("mimeType").getAsString());
        JsonObject text = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
        assertEquals(42, text.get("value").getAsInt());
        assertTrue(text.get("caller").getAsString().startsWith("key=primary@"));

        assertEquals("plain text", read("eden://test/text")
                .getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void resourcesReadRequiresSession() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", "eden://test/text");
        JsonObject response = post("resources/read", params, null);
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32000, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void resourcesReadReportsMissingAndUnknownUris() throws Exception {
        JsonObject missing = post("resources/read", new JsonObject(), session);
        assertEquals(-32602, missing.getAsJsonObject("error").get("code").getAsInt());

        JsonObject unknown = readResponse("eden://test/missing");
        assertTrue(unknown.has("error"));
        assertEquals(-32002, unknown.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void resourcesReadDoesNotLeakReaderFailures() throws Exception {
        JsonObject response = readResponse("eden://test/broken");
        assertTrue(response.has("error"));
        assertEquals(-32000, response.getAsJsonObject("error").get("code").getAsInt());
        assertFalse(response.toString().contains("reader exploded"));
    }
}
