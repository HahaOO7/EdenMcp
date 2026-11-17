package at.haha007.edenMcp.core.transport;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.config.CommandClassification;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookContext;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import at.haha007.edenMcp.core.util.PrivateFileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpServerTlsTest {
    @TempDir
    Path dir;

    @Test
    void selfSignedTlsStartsWithHardenedDefaults() throws Exception {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger("tls-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });

        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Path keyFile = data.resolve(".key");
        AccessKeyManager keyManager = new AccessKeyManager(keyFile, logger);
        keyManager.loadOrCreate();
        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

        EdenConfig.TlsConfig tls = new EdenConfig.TlsConfig(true, "self-signed", "", "", "", "", false, "", "");
        EdenConfig.TransportConfig transport = new EdenConfig.TransportConfig(
                "http-sse", "127.0.0.1", 0, List.of(), List.of(), 1024 * 1024, 20, 20, tls);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(false, 10, 60),
                new EdenConfig.PolicyConfig(true, false),
                true, true, false, Map.of(), CommandClassification.empty());

        ToolRegistry registry = new ToolRegistry();
        HookManager hooks = new HookManager(registry, new HookContext(null, null, logger, new AuditLogger(logger)));
        McpHttpServer server = new McpHttpServer(config, keyManager, registry, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        try {
            server.start(data);
            int port = server.port();

            Path ks = data.resolve("mcp-keystore.p12");
            Path passFile = data.resolve("mcp-keystore.pass");
            assertTrue(Files.exists(ks));
            assertTrue(Files.exists(passFile));
            assertTrue(PrivateFileUtil.isOwnerOnly(ks), "keystore must be owner-only");
            assertTrue(PrivateFileUtil.isOwnerOnly(passFile), "password file must be owner-only");
            String password = Files.readString(passFile, StandardCharsets.UTF_8).trim();
            assertTrue(password.matches("[0-9a-f]{64}"), "password should be random, not hardcoded");
            assertTrue(messages.stream().anyMatch(m -> m.contains("SHA-256 fingerprint")),
                    "startup log should contain the certificate fingerprint: " + messages);

            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());

            HttpsURLConnection conn = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/mcp").openConnection();
            conn.setSSLSocketFactory(trustAll.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, conn.getResponseCode());
            assertTrue(conn.getCipherSuite().contains("_GCM_") || conn.getCipherSuite().contains("_CHACHA20_"),
                    "only AEAD ciphers should be negotiated: " + conn.getCipherSuite());
            conn.disconnect();

            SSLParameters configured = McpHttpServer.hardenedSslParameters(SSLContext.getDefault(), false);
            assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"}, configured.getProtocols());
            assertTrue(java.util.Arrays.stream(configured.getCipherSuites())
                    .allMatch(s -> s.contains("_GCM_") || s.contains("_CHACHA20_POLY1305_")));
        } finally {
            server.stop();
        }
    }
}
