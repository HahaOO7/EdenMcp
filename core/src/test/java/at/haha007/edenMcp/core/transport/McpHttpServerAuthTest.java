package at.haha007.edenMcp.core.transport;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.config.CommandClassification;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookContext;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
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

/** Verifies the credential-extraction and brute-force rules of the MCP endpoint. */
class McpHttpServerAuthTest {
    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("auth-test-" + System.nanoTime());
    private McpHttpServer server;
    private String key;

    private void start(boolean allowLegacyHeader, int maxFailures) throws Exception {
        Path data = dir.resolve("data-" + System.nanoTime());
        Files.createDirectories(data);
        Path keyFile = data.resolve(".key");
        AccessKeyManager keyManager = new AccessKeyManager(keyFile, logger);
        keyManager.loadOrCreate();
        key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

        EdenConfig.TlsConfig tls = new EdenConfig.TlsConfig(false, "self-signed", "", "", "", "", false, "", "");
        EdenConfig.TransportConfig transport = new EdenConfig.TransportConfig(
                "http-sse", "127.0.0.1", 0, List.of(), List.of(),
                1024 * 1024, 20, 20, 1800, 100, tls);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(allowLegacyHeader, maxFailures, 60, 0, null),
                new EdenConfig.PolicyConfig(true, false),
                true, true, false, Map.of(), CommandClassification.empty());

        ToolRegistry registry = new ToolRegistry();
        HookManager hooks = new HookManager(registry, new HookContext(null, null, logger, new AuditLogger(logger)));
        server = new McpHttpServer(config, keyManager, registry, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        server.start(data);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private record Resp(int status, String wwwAuthenticate, String body) {}

    private Resp post(String path, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + path).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        headers.forEach(c::setRequestProperty);
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int status = c.getResponseCode();
        InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String challenge = c.getHeaderField("WWW-Authenticate");
        c.disconnect();
        return new Resp(status, challenge, text);
    }

    @Test
    void bearerSchemeIsRequiredButCaseInsensitive() throws Exception {
        start(false, 10);

        assertEquals(200, post("/mcp", Map.of("Authorization", "Bearer " + key)).status());
        assertEquals(200, post("/mcp", Map.of("Authorization", "bearer " + key)).status(),
                "the Bearer scheme is case-insensitive");

        Resp raw = post("/mcp", Map.of("Authorization", key));
        assertEquals(401, raw.status(), "a raw Authorization value must never be treated as the key");
        assertEquals("Bearer", raw.wwwAuthenticate());
    }

    @Test
    void missingCredentialsAreRejectedWithABearerChallenge() throws Exception {
        start(false, 10);

        Resp none = post("/mcp", Map.of());
        assertEquals(401, none.status());
        assertEquals("Bearer", none.wwwAuthenticate());
    }

    @Test
    void legacyHeaderIsIgnoredByDefault() throws Exception {
        start(false, 10);

        assertEquals(401, post("/mcp", Map.of("X-Eden-Key", key)).status(),
                "the legacy header must be opt-in");
    }

    @Test
    void legacyHeaderWorksWhenExplicitlyEnabled() throws Exception {
        start(true, 10);

        assertEquals(200, post("/mcp", Map.of("X-Eden-Key", key)).status());
    }

    @Test
    void credentialsInTheQueryStringAreIgnored() throws Exception {
        start(false, 10);

        assertEquals(401, post("/mcp?key=" + key, Map.of()).status());
        assertEquals(401, post("/mcp?access_key=" + key + "&sessionId=x", Map.of()).status(),
                "a query string must not authenticate or select a session");
    }

    @Test
    void repeatedFailuresLockOutEvenTheCorrectKey() throws Exception {
        start(false, 2);
        String bad = "f".repeat(64);

        assertEquals(401, post("/mcp", Map.of("Authorization", "Bearer " + bad)).status());
        assertEquals(401, post("/mcp", Map.of("Authorization", "Bearer " + bad)).status());

        Resp locked = post("/mcp", Map.of("Authorization", "Bearer " + key));
        assertEquals(429, locked.status(), "a valid key must not bypass an active lockout");
        assertTrue(locked.body().contains("too many failed authentication attempts"), locked.body());
    }

    @Test
    void successfulAuthenticationClearsTheFailureCounter() throws Exception {
        start(false, 2);
        String bad = "f".repeat(64);

        assertEquals(401, post("/mcp", Map.of("Authorization", "Bearer " + bad)).status());
        assertEquals(200, post("/mcp", Map.of("Authorization", "Bearer " + key)).status());
        assertEquals(401, post("/mcp", Map.of("Authorization", "Bearer " + bad)).status());
        assertEquals(200, post("/mcp", Map.of("Authorization", "Bearer " + key)).status(),
                "one success must reset the counter so the next single failure is tolerated");
    }
}
