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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpServerCorsTest {
    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("cors-test-" + System.nanoTime());
    private McpHttpServer server;
    private String key;

    private void start(List<String> allowedOrigins) throws Exception {
        Path data = dir.resolve("data-" + System.nanoTime());
        Files.createDirectories(data);
        Path keyFile = data.resolve(".key");
        AccessKeyManager keyManager = new AccessKeyManager(keyFile, logger);
        keyManager.loadOrCreate();
        key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

        EdenConfig.TlsConfig tls = new EdenConfig.TlsConfig(false, "self-signed", "", "", "", "", false, "", "",
                2048, 3650);
        EdenConfig.TransportConfig transport = new EdenConfig.TransportConfig(
                "http-sse", "127.0.0.1", 0, List.of(), allowedOrigins,
                1024 * 1024, 20, 20, 1800, 100,
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

    private record Resp(int status, String allowOrigin, String vary, String allowHeaders) {}

    /** Raw socket so the JVM cannot strip the Origin header as a "restricted" header. */
    private Resp request(String method, String origin, boolean authorized) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            StringBuilder req = new StringBuilder()
                    .append(method).append(" /mcp HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1\r\n")
                    .append("Connection: close\r\n");
            if (authorized) {
                req.append("Authorization: Bearer ").append(key).append("\r\n")
                        .append("Content-Type: application/json\r\n")
                        .append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
            }
            if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
            req.append("\r\n");
            os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (authorized) os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String[] status = br.readLine().split(" ");
            int code = Integer.parseInt(status[1]);
            String allowOrigin = null, vary = null, allowHeaders = null;
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (lower.startsWith("access-control-allow-origin:")) allowOrigin = value;
                else if (lower.startsWith("vary:")) vary = value;
                else if (lower.startsWith("access-control-allow-headers:")) allowHeaders = value;
            }
            return new Resp(code, allowOrigin, vary, allowHeaders);
        }
    }

    @Test
    void noCorsHeadersWithoutConfiguration() throws Exception {
        start(List.of());
        // The Origin host is not in the default loopback allowlist, so it is rejected.
        assertEquals(403, request("POST", "https://app.example.com", true).status());
    }

    @Test
    void configuredOriginIsAcceptedAndEchoed() throws Exception {
        start(List.of("https://app.example.com"));
        Resp r = request("POST", "https://app.example.com", true);
        assertEquals(200, r.status());
        assertEquals("https://app.example.com", r.allowOrigin());
        assertEquals("Origin", r.vary());
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        start(List.of("https://app.example.com"));
        assertEquals(403, request("POST", "https://evil.example.com", true).status());
    }

    @Test
    void preflightAnsweredOnlyForConfiguredOrigin() throws Exception {
        start(List.of("https://app.example.com"));
        Resp ok = request("OPTIONS", "https://app.example.com", false);
        assertEquals(204, ok.status());
        assertEquals("https://app.example.com", ok.allowOrigin());
        assertTrue(ok.allowHeaders().contains("Authorization"));

        Resp bad = request("OPTIONS", "https://evil.example.com", false);
        assertEquals(405, bad.status());
        assertNull(bad.allowOrigin());
    }
}
