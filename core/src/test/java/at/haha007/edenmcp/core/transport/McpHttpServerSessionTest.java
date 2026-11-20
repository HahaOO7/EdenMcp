package at.haha007.edenmcp.core.transport;

import at.haha007.edenmcp.api.*;

import at.haha007.edenmcp.core.audit.AuditLogger;
import at.haha007.edenmcp.core.auth.AccessKeyManager;
import at.haha007.edenmcp.core.config.CommandClassification;
import at.haha007.edenmcp.core.config.EdenConfig;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpServerSessionTest {
    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("session-test-" + System.nanoTime());
    private McpHttpServer server;
    private String key;

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
                true, true, false, 200, Map.of(), new EdenConfig.ExternalHookConfig(true, CapabilityLevel.MODERATE_ACTION, false, Map.of()), CommandClassification.empty());

        ToolRegistry registry = new ToolRegistry();
        HookManager hooks = new HookManager(registry, new HookContext(null, null, null, logger));
        server = new McpHttpServer(config, keyManager, registry, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        server.start(data);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private record Resp(int status, String sessionHeader, JsonObject body) {}

    private Resp post(String body, String authKey, String sessionId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + "/mcp").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (authKey != null) c.setRequestProperty("Authorization", "Bearer " + authKey);
        if (sessionId != null) c.setRequestProperty("Mcp-Session-Id", sessionId);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        var stream = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        JsonObject parsed = text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        return new Resp(status, c.getHeaderField("Mcp-Session-Id"), parsed);
    }

    private static String rpc(int id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
    }

    @Test
    void initializeIssuesSessionThatIsRequiredForToolCalls() throws Exception {
        Resp unauth = post(rpc(1, "initialize"), null, null);
        assertEquals(401, unauth.status());

        Resp init = post(rpc(2, "initialize"), key, null);
        assertEquals(200, init.status());
        assertNotNull(init.sessionHeader(), "initialize should return Mcp-Session-Id");
        assertFalse(init.sessionHeader().isBlank());
        assertEquals(init.sessionHeader(),
                init.body().getAsJsonObject("result").get("sessionId").getAsString());
        assertEquals("2024-11-05", init.body().getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(1, server.activeSessions());

        Resp noSession = post(rpc(3, "tools/list"), key, null);
        assertEquals(200, noSession.status());
        assertTrue(noSession.body().has("error"), "tools/list without a session must be rejected: " + noSession.body());
        assertEquals(-32000, noSession.body().getAsJsonObject("error").get("code").getAsInt());

        Resp wrongSession = post(rpc(4, "tools/list"), key, "0".repeat(32));
        assertTrue(wrongSession.body().has("error"));

        Resp ok = post(rpc(5, "tools/list"), key, init.sessionHeader());
        assertFalse(ok.body().has("error"), ok.body().toString());
        assertTrue(ok.body().getAsJsonObject("result").has("tools"));

        Resp ping = post(rpc(6, "ping"), key, null);
        assertFalse(ping.body().has("error"), "ping is a connectivity check and needs no session");
    }

    @Test
    void sessionIsBoundToKeyAndCanBeInvalidated() throws Exception {
        Resp init = post(rpc(10, "initialize"), key, null);
        String session = init.sessionHeader();
        assertNotNull(session);

        Resp wrongKey = post(rpc(11, "tools/list"), "f".repeat(64), session);
        assertEquals(401, wrongKey.status(), "a session must not authenticate a different key");

        assertEquals(1, server.invalidateSessionsForPrincipal("primary"));
        assertEquals(0, server.activeSessions());
        Resp after = post(rpc(12, "tools/list"), key, session);
        assertTrue(after.body().has("error"), "invalidated session must be rejected: " + after.body());
    }

    @Test
    void rotationClosesSseStreamsOfInvalidatedPrincipal() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(4000);
            OutputStream os = s.getOutputStream();
            os.write(("GET /sse HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + key + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            assertEquals("HTTP/1.1 200 OK", br.readLine());
            assertNotNull(br.readLine(), "SSE response headers should follow the status line");

            assertEquals(1, server.invalidateSessionsForPrincipal("primary"));
            long start = System.currentTimeMillis();
            int next = br.read();
            assertTrue(next == -1 || System.currentTimeMillis() - start < 3500,
                    "SSE stream should close promptly when the principal is invalidated");
        }
    }
}
