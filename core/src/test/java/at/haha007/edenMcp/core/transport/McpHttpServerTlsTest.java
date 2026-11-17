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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
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
                "http-sse", "127.0.0.1", 0, List.of(), List.of(), 1024 * 1024, 20, 20, 1800, 100, tls);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(false, 10, 60, 0, null),
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

            HttpsURLConnection conn = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/mcp").toURL().openConnection();
            conn.setSSLSocketFactory(trustAll.getSocketFactory());
            conn.setHostnameVerifier((ignoredHost, ignoredSession) -> true);
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

    @Test
    void mtlsRejectsClientsWithoutCertificate() throws Exception {
        Logger logger = Logger.getLogger("mtls-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);

        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Path keyFile = data.resolve(".key");
        AccessKeyManager keyManager = new AccessKeyManager(keyFile, logger);
        keyManager.loadOrCreate();
        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

        // Server keystore (custom TLS mode).
        String serverPass = "serverpass";
        Path serverKs = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", serverKs.toString(),
                "-storepass", serverPass, "-keypass", serverPass,
                "-dname", "CN=localhost", "-validity", "3650");
        PrivateFileUtil.setOwnerOnly(serverKs);

        // Client keypair + exported certificate.
        String clientPass = "clientpass";
        Path clientKs = dir.resolve("client.p12");
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", clientKs.toString(),
                "-storepass", clientPass, "-keypass", clientPass,
                "-dname", "CN=test-client", "-validity", "3650");
        Path clientCrt = dir.resolve("client.crt");
        keytool("-exportcert", "-alias", "client", "-keystore", clientKs.toString(),
                "-storepass", clientPass, "-file", clientCrt.toString(), "-rfc");

        // Truststore containing only the client certificate.
        String trustPass = "trustpass";
        Path truststore = dir.resolve("truststore.p12");
        keytool("-importcert", "-alias", "client", "-file", clientCrt.toString(),
                "-keystore", truststore.toString(), "-storetype", "PKCS12",
                "-storepass", trustPass, "-noprompt");
        PrivateFileUtil.setOwnerOnly(truststore);

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(clientKs)) {
            clientStore.load(is, clientPass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, clientPass.toCharArray());

        X509TrustManager trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        EdenConfig.TlsConfig tls = new EdenConfig.TlsConfig(true, "custom", "", "",
                serverKs.toString(), serverPass, true, truststore.toString(), trustPass);
        EdenConfig.TransportConfig transport = new EdenConfig.TransportConfig(
                "http-sse", "127.0.0.1", 0, List.of(), List.of(), 1024 * 1024, 20, 20, 1800, 100, tls);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(false, 10, 60, 0, null),
                new EdenConfig.PolicyConfig(true, false),
                true, true, false, Map.of(), CommandClassification.empty());

        ToolRegistry registry = new ToolRegistry();
        HookManager hooks = new HookManager(registry, new HookContext(null, null, logger, new AuditLogger(logger)));
        McpHttpServer server = new McpHttpServer(config, keyManager, registry, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        try {
            server.start(data);
            int port = server.port();
            String url = "https://127.0.0.1:" + port + "/mcp";

            // A connection without a client certificate must fail the TLS handshake.
            SSLContext noClient = SSLContext.getInstance("TLS");
            noClient.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            assertThrows(java.io.IOException.class, () -> {
                HttpsURLConnection conn = (HttpsURLConnection) URI.create(url).toURL().openConnection();
                conn.setSSLSocketFactory(noClient.getSocketFactory());
                conn.setHostnameVerifier((ignoredHost, ignoredSession) -> true);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
                conn.getResponseCode();
            }, "mTLS should reject a client that presents no certificate");

            // A connection presenting the trusted client certificate succeeds.
            SSLContext withClient = SSLContext.getInstance("TLS");
            withClient.init(kmf.getKeyManagers(), new TrustManager[]{trustAll}, new SecureRandom());
            HttpsURLConnection conn = (HttpsURLConnection) URI.create(url).toURL().openConnection();
            conn.setSSLSocketFactory(withClient.getSocketFactory());
            conn.setHostnameVerifier((ignoredHost, ignoredSession) -> true);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, conn.getResponseCode());
            conn.disconnect();
        } finally {
            server.stop();
        }
    }

    private static void keytool(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("keytool");
        cmd.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "keytool failed: " + out);
    }
}
