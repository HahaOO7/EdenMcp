package at.haha007.edenMcp.core.transport;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import at.haha007.edenMcp.core.tool.ToolResult;
import com.google.gson.*;
import com.sun.net.httpserver.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class McpHttpServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final EdenConfig config;
    private final AccessKeyManager keyManager;
    private final ToolRegistry registry;
    private final HookManager hookManager;
    private final AuditLogger audit;
    private final Logger logger;
    private final AtomicReference<OperatingMode> mode;
    private HttpServer server;
    private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "edenmcp-http");
        t.setDaemon(true);
        return t;
    });

    public McpHttpServer(EdenConfig config,
                         AccessKeyManager keyManager,
                         ToolRegistry registry,
                         HookManager hookManager,
                         AuditLogger audit,
                         Logger logger,
                         AtomicReference<OperatingMode> mode,
                         java.util.function.Supplier<Map<String, Object>> configSnapshotSupplier) {
        this.config = config;
        this.keyManager = keyManager;
        this.registry = registry;
        this.hookManager = hookManager;
        this.audit = audit;
        this.logger = logger;
        this.mode = mode;
        // configSnapshotSupplier currently unused by transport; kept for future protocol extensions
    }

    public void start(Path dataFolder) throws Exception {
        InetSocketAddress addr = new InetSocketAddress(config.transport().bind(), config.transport().port());
        EdenConfig.TlsConfig tls = config.transport().tls();
        if (tls.enabled()) {
            HttpsServer https = HttpsServer.create(addr, 0);
            SSLContext ssl = createSslContext(tls, dataFolder);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            this.server = https;
            logger.info("MCP HTTP server starting with TLS enabled on " + addr);
        } else {
            this.server = HttpServer.create(addr, 0);
            logger.warning("[SECURITY] MCP TLS is DISABLED. Access key and tool I/O may be exposed in plaintext. Bind=" + addr);
        }
        server.createContext("/mcp", this::handleMcp);
        server.createContext("/sse", this::handleSse);
        server.createContext("/message", this::handleMcp);
        server.setExecutor(executor);
        server.start();
        logger.info("MCP server listening on " + (tls.enabled() ? "https" : "http") + "://" + config.transport().bind() + ":" + config.transport().port());
    }

    public void stop() {
        for (SseClient c : sseClients) c.close();
        sseClients.clear();
        if (server != null) server.stop(1);
        executor.shutdownNow();
    }

    private void handleSse(HttpExchange ex) throws IOException {
        SseClient client = null;
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            if (!authorize(ex)) return;
            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", "text/event-stream");
            h.add("Cache-Control", "no-cache");
            h.add("Connection", "keep-alive");
            h.add("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            client = new SseClient(ex);
            sseClients.add(client);
            client.sendEvent("endpoint", Map.of("url", "/message"));
            client.sendEvent("ready", Map.of("mode", mode.get().name().toLowerCase(Locale.ROOT)));
            // Keep handler alive to maintain stream; periodic heartbeats detect disconnects.
            while (client.isOpen() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!client.sendComment("keepalive")) break;
            }
        } catch (Exception e) {
            // fall through to cleanup
        } finally {
            if (client != null) {
                sseClients.remove(client);
                client.close();
            } else {
                try { ex.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleMcp(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            sendJson(ex, 405, Map.of("jsonrpc", "2.0", "error", Map.of("code", -32600, "message", "POST required"), "id", null));
            return;
        }
        if (!authorize(ex)) return;
        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            sendJson(ex, 400, rpcError(null, -32700, "Parse error"));
            return;
        }
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseString(body);
        } catch (Exception e) {
            sendJson(ex, 400, rpcError(null, -32700, "Parse error"));
            return;
        }
        if (rootEl.isJsonArray()) {
            JsonArray arr = rootEl.getAsJsonArray();
            JsonArray out = new JsonArray();
            for (JsonElement el : arr) {
                JsonObject resp = handleRpc(el.isJsonObject() ? el.getAsJsonObject() : new JsonObject(), ex);
                if (resp != null) out.add(resp);
            }
            sendJson(ex, 200, out);
        } else if (rootEl.isJsonObject()) {
            JsonObject resp = handleRpc(rootEl.getAsJsonObject(), ex);
            if (resp == null) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            } else {
                sendJson(ex, 200, resp);
            }
        } else {
            sendJson(ex, 400, rpcError(null, -32600, "Invalid Request"));
        }
    }

    private JsonObject handleRpc(JsonObject req, HttpExchange ex) {
        JsonElement id = req.has("id") ? req.get("id") : null;
        String rpcMethod = optString(req, "method");
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        if (rpcMethod == null) return rpcError(id, -32600, "Invalid Request");
        try {
            return switch (rpcMethod) {
                case "initialize" -> rpcResult(id, handleInitialize(params));
                case "notifications/initialized", "initialized" -> null; // notification
                case "ping" -> rpcResult(id, Map.of());
                case "tools/list" -> rpcResult(id, handleToolsList());
                case "tools/call" -> rpcResult(id, handleToolsCall(params, caller(ex)));
                case "resources/list" -> rpcResult(id, Map.of("resources", List.of()));
                default -> rpcError(id, -32601, "Method not found: " + rpcMethod);
            };
        } catch (Exception e) {
            logger.log(Level.WARNING, "MCP method error: " + rpcMethod, e);
            return rpcError(id, -32000, "Server error: " + e.getMessage());
        }
    }

    private Map<String, Object> handleInitialize(JsonObject params) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of("listChanged", true));
        caps.put("resources", Map.of("listChanged", false));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "EdenMcp");
        info.put("version", "1.0");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("protocolVersion", "2024-11-05");
        res.put("capabilities", caps);
        res.put("serverInfo", info);
        res.put("mode", mode.get().name().toLowerCase(Locale.ROOT));
        return res;
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpTool t : registry.visible(mode.get())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description() + " [level=" + t.level() + ", sideEffects=" + t.sideEffects() + "]");
            m.put("inputSchema", t.inputSchema() == null ? Map.of("type", "object", "properties", Map.of()) : t.inputSchema());
            tools.add(m);
        }
        return Map.of("tools", tools);
    }

    private Map<String, Object> handleToolsCall(JsonObject params, String caller) {
        String name = optString(params, "name");
        JsonObject argsObj = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        Map<String, Object> args = jsonObjectToMap(argsObj);
        if (name == null || name.isBlank()) {
            return toolCallResponse(false, "Missing tool name");
        }
        Optional<McpTool> toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) {
            audit.toolCall(caller, name, mode.get().name(), false, "not_found");
            return toolCallResponse(false, "Tool not found or not available: " + name);
        }
        McpTool tool = toolOpt.get();
        OperatingMode active = mode.get();
        if (!registry.canExecute(active, tool)) {
            audit.toolCall(caller, name, active.name(), false, "denied_mode");
            return toolCallResponse(false, "Tool not permitted in mode " + active.name());
        }
        try {
            ToolResult result = tool.execute(new ToolContext(active, caller, args));
            audit.toolCall(caller, name, active.name(), result.success(), result.success() ? "ok" : result.error());
            Object payload = result.success() ? result.content() : Map.of("error", result.error());
            return toolCallResponse(result.success(), payload == null ? Map.of() : payload);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Tool execution failed: " + name, e);
            audit.toolCall(caller, name, active.name(), false, "exception:" + e.getClass().getSimpleName());
            return toolCallResponse(false, "Execution error: " + e.getMessage());
        }
    }

    private Map<String, Object> toolCallResponse(boolean ok, Object payload) {
        String text = payload instanceof String s ? s : GSON.toJson(payload);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", List.of(content));
        res.put("isError", !ok);
        return res;
    }

    private boolean authorize(HttpExchange ex) throws IOException {
        String provided = extractKey(ex);
        if (!keyManager.verify(provided)) {
            audit.authFailure(caller(ex));
            sendJson(ex, 401, rpcError(null, -32001, "Unauthorized: missing or invalid access key"));
            return false;
        }
        return true;
    }

    private String extractKey(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
            return auth.trim();
        }
        String x = ex.getRequestHeaders().getFirst("X-Eden-Key");
        if (x != null) return x.trim();
        // query param fallback
        URI uri = ex.getRequestURI();
        if (uri != null && uri.getQuery() != null) {
            for (String part : uri.getQuery().split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String k = part.substring(0, eq);
                    if (k.equals("key") || k.equals("access_key")) return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private String caller(HttpExchange ex) {
        try {
            String ra = ex.getRemoteAddress() == null ? "unknown" : String.valueOf(ex.getRemoteAddress());
            return ra;
        } catch (Exception e) { return "unknown"; }
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonObject rpcResult(JsonElement id, Object result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) o.add("id", id); else o.add("id", JsonNull.INSTANCE);
        o.add("result", GSON.toJsonTree(result));
        return o;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) o.add("id", id); else o.add("id", JsonNull.INSTANCE);
        o.add("error", err);
        return o;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObjectToMap(JsonObject obj) {
        if (obj == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            out.put(e.getKey(), jsonToJava(e.getValue()));
        }
        return out;
    }

    private static Object jsonToJava(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                try {
                    String s = p.getAsString();
                    if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
                    long l = Long.parseLong(s);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                    return l;
                } catch (Exception ex) { return p.getAsNumber(); }
            }
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement i : el.getAsJsonArray()) list.add(jsonToJava(i));
            return list;
        }
        if (el.isJsonObject()) return jsonObjectToMap(el.getAsJsonObject());
        return el.toString();
    }

    private SSLContext createSslContext(EdenConfig.TlsConfig tls, Path dataFolder) throws Exception {
        String mode = tls.mode() == null ? "self-signed" : tls.mode();
        if ("custom".equalsIgnoreCase(mode)) {
            if (tls.keystorePath() != null && !tls.keystorePath().isBlank()) {
                return sslFromKeystore(Path.of(tls.keystorePath()), tls.keystorePassword());
            }
            throw new IllegalStateException("Custom TLS mode requires keystore-path (PEM cert/key direct loading not implemented; provide keystore)");
        }
        // self-signed: expect/generate keystore in data folder
        Path ks = dataFolder.resolve("mcp-keystore.p12");
        String pass = "edenmcp";
        if (!Files.exists(ks)) {
            boolean generated = tryGenerateSelfSigned(ks, pass, dataFolder);
            if (!generated) {
                throw new IllegalStateException("Failed to generate self-signed keystore. Provide keytool or configure custom TLS.");
            }
        }
        return sslFromKeystore(ks, pass);
    }

    private boolean tryGenerateSelfSigned(Path ks, String pass, Path dataFolder) {
        try {
            // attempt keytool
            String dname = "CN=EdenMcp, OU=EdenMcp, O=EdenMcp, L=Local, S=Local, C=XX";
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "edenmcp",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-storetype", "PKCS12",
                    "-keystore", ks.toAbsolutePath().toString(),
                    "-validity", "3650",
                    "-storepass", pass,
                    "-keypass", pass,
                    "-dname", dname
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code == 0 && Files.exists(ks)) {
                // fingerprint
                try {
                    ProcessBuilder fp = new ProcessBuilder("keytool", "-list", "-keystore", ks.toAbsolutePath().toString(), "-storepass", pass);
                    fp.redirectErrorStream(true);
                    Process pf = fp.start();
                    String fpo = new String(pf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    pf.waitFor();
                    logger.info("Generated self-signed MCP TLS certificate at " + ks + "\n" + fpo);
                } catch (Exception e) {
                    logger.info("Generated self-signed MCP TLS certificate at " + ks);
                }
                return true;
            } else {
                logger.warning("keytool failed to generate self-signed cert: " + out);
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not run keytool for self-signed cert generation", e);
            return false;
        }
    }

    private SSLContext sslFromKeystore(Path ks, String password) throws Exception {
        char[] pass = password == null ? new char[0] : password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(ks)) {
            store.load(is, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pass);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    public void broadcastModeChange(OperatingMode newMode) {
        String name = newMode == null ? "unknown" : newMode.name().toLowerCase(Locale.ROOT);
        for (SseClient c : sseClients) {
            c.sendEvent("mode", Map.of("mode", name));
        }
    }

    private static final class SseClient {
        private final HttpExchange exchange;
        private final OutputStream os;
        private volatile boolean open = true;

        SseClient(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.os = exchange.getResponseBody();
        }

        synchronized boolean sendEvent(String event, Object data) {
            if (!open) return false;
            try {
                String payload = "event: " + event + "\n" + "data: " + GSON.toJson(data) + "\n\n";
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        synchronized boolean sendComment(String comment) {
            if (!open) return false;
            try {
                os.write((":" + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        boolean isOpen() {
            return open;
        }

        void close() {
            open = false;
            try { os.close(); } catch (Exception ignored) {}
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }
}
