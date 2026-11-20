package at.haha007.edenMcp.core.transport;

import at.haha007.edenMcp.core.audit.AuditLogger;
import at.haha007.edenMcp.core.auth.AccessKeyManager;
import at.haha007.edenMcp.core.auth.Principal;
import at.haha007.edenMcp.core.config.EdenConfig;
import at.haha007.edenMcp.core.hook.HookManager;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.policy.SecurityPolicy;
import at.haha007.edenMcp.core.resource.McpResource;
import at.haha007.edenMcp.core.resource.ResourceContext;
import at.haha007.edenMcp.core.resource.ResourceRegistry;
import at.haha007.edenMcp.core.tool.McpTool;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolRegistry;
import at.haha007.edenMcp.core.tool.ToolResult;
import at.haha007.edenMcp.core.util.PrivateFileUtil;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class McpHttpServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2024-11-05", "2025-03-26");

    private final EdenConfig config;
    private final AccessKeyManager keyManager;
    private final ToolRegistry registry;
    private final ResourceRegistry resources;
    private final AuditLogger audit;
    private final Logger logger;
    private final AtomicReference<OperatingMode> mode;
    private final SecurityPolicy securityPolicy;
    private final Set<String> allowedHosts = new HashSet<>();
    private final Set<String> allowedOrigins = new HashSet<>();
    private final boolean explicitOrigins;
    private final Map<String, FailureRecord> failures = new ConcurrentHashMap<>();
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private volatile long lastSessionPrune;
    private final boolean wildcardBind;
    private final boolean explicitHosts;
    @Nullable private HttpServer server;
    private volatile int port;
    private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;

    @SuppressWarnings("unused")
    public McpHttpServer(EdenConfig config,
                         AccessKeyManager keyManager,
                         ToolRegistry registry,
                         @Nullable HookManager hookManager,
                         AuditLogger audit,
                         Logger logger,
                         AtomicReference<OperatingMode> mode,
                         java.util.function.Supplier<Map<String, Object>> configSnapshotSupplier) {
        this.config = config;
        this.keyManager = keyManager;
        this.registry = registry;
        this.resources = hookManager == null ? new ResourceRegistry() : hookManager.resources();
        this.audit = audit;
        this.logger = logger;
        this.mode = mode;
        this.securityPolicy = new SecurityPolicy(config.policy().allowUnsafeAtRuntime());
        EdenConfig.TransportConfig transportConfig = config.transport();
        this.executor = new ThreadPoolExecutor(
                transportConfig.threadPoolCoreSize(), transportConfig.threadPoolMaxSize(),
                transportConfig.threadPoolKeepAliveSeconds(), TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(transportConfig.threadPoolQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "edenmcp-http");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        // configSnapshotSupplier is reserved for future protocol extensions.

        String bind = config.transport().bind();
        this.wildcardBind = bind == null || bind.isBlank() || bind.equals("0.0.0.0")
                || bind.equals("::") || bind.equals("*");
        this.explicitHosts = !config.transport().allowedHosts().isEmpty();
        allowedHosts.addAll(List.of("localhost", "127.0.0.1", "::1", "[::1]"));
        if (!wildcardBind) allowedHosts.add(bind.toLowerCase(Locale.ROOT));
        for (String h : config.transport().allowedHosts()) {
            if (h != null && !h.isBlank()) allowedHosts.add(h.trim().toLowerCase(Locale.ROOT));
        }
        this.explicitOrigins = !config.transport().allowedOrigins().isEmpty();
        for (String o : config.transport().allowedOrigins()) {
            if (o != null && !o.isBlank()) allowedOrigins.add(o.trim());
        }
    }

    public void start(Path dataFolder) throws Exception {
        InetSocketAddress addr = new InetSocketAddress(config.transport().bind(), config.transport().port());
        EdenConfig.TlsConfig tls = config.transport().tls();
        HttpServer active;
        if (tls.enabled()) {
            HttpsServer https = HttpsServer.create(addr, 0);
            SSLContext ssl = createSslContext(tls, dataFolder);
            SSLParameters hardened = hardenedSslParameters(ssl, tls.requireClientCert());
            https.setHttpsConfigurator(new HttpsConfigurator(ssl) {
                @Override
                public void configure(HttpsParameters params) {
                    params.setSSLParameters(hardened);
                }
            });
            active = https;
            logger.info("MCP HTTP server starting with TLS enabled on " + addr);
        } else {
            active = HttpServer.create(addr, 0);
            logger.warning("[SECURITY] MCP TLS is DISABLED. Access key and tool I/O may be exposed in plaintext. Bind=" + addr);
        }
        this.server = active;
        active.createContext("/mcp", this::handleMcp);
        active.createContext("/sse", this::handleSse);
        active.createContext("/message", this::handleMcp);
        active.setExecutor(executor);
        active.start();
        this.port = active.getAddress().getPort();
        logger.info("MCP server listening on " + (tls.enabled() ? "https" : "http") + "://"
                + config.transport().bind() + ":" + port);
        if (wildcardBind && !explicitHosts) {
            logger.info("MCP Host validation: wildcard bind, non-browser clients may use any Host; "
                    + "set mcp.transport.allowed-hosts for strict validation.");
        }
    }

    public int port() {
        return port;
    }

    /** Number of live MCP sessions (diagnostics/tests). */
    public int activeSessions() {
        return sessions.size();
    }

    /** Close every session and SSE stream bound to a key id (used after rotation/revocation). */
    public int invalidateSessionsForPrincipal(@Nullable String principalId) {
        int n = 0;
        Iterator<McpSession> it = sessions.values().iterator();
        while (it.hasNext()) {
            if (principalId != null && principalId.equals(it.next().principal().id())) {
                it.remove();
                n++;
            }
        }
        for (SseClient c : sseClients) {
            if (principalId != null && principalId.equals(c.principalId())) c.close();
        }
        if (n > 0) logger.info("Invalidated " + n + " MCP session(s) for key " + principalId);
        return n;
    }

    public void stop() {
        for (SseClient c : sseClients) c.close();
        sseClients.clear();
        sessions.clear();
        if (server != null) server.stop(config.transport().shutdownGraceSeconds());
        executor.shutdownNow();
    }

    @SuppressWarnings("BusyWait") // SSE keepalive cadence is configured; mcp.transport.sse-keepalive-ms floors it at 1s.
    private void handleSse(HttpExchange ex) {
        SseClient client = null;
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            Principal principal = authorize(ex);
            if (principal == null) return;
            if (sseClients.size() >= config.transport().maxSseClients()) {
                sendJson(ex, 503, Map.of("error", "too many SSE clients"));
                return;
            }
            McpSession session = newSession(principal);
            Headers h = ex.getResponseHeaders();
            h.add("Mcp-Session-Id", session.id());
            h.add("Content-Type", "text/event-stream");
            h.add("Cache-Control", "no-cache");
            h.add("Connection", "keep-alive");
            h.add("X-Accel-Buffering", "no");
            Object presented = ex.getAttribute("edenmcp.presented-key");
            String credential = presented instanceof String s ? s : null;
            ex.setAttribute("edenmcp.presented-key", null);
            ex.sendResponseHeaders(200, 0);
            client = new SseClient(ex, principal.id());
            sseClients.add(client);
            client.sendEvent("endpoint", Map.of("url", "/message?sessionId=" + session.id()));
            client.sendEvent("ready", Map.of("mode", mode.get().name().toLowerCase(Locale.ROOT)));
            while (client.isOpen() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(config.transport().sseKeepaliveMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // A revoked/expired key must not keep a long-lived stream alive.
                if (credential == null || keyManager.authenticate(credential) == null) break;
                if (!client.sendKeepalive()) break;
            }
        } catch (Exception e) {
            // Cleanup below.
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
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            if (handlePreflight(ex)) return;
            sendJson(ex, 405, rpcError(null, -32600, "POST required"));
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, rpcError(null, -32600, "POST required"));
            return;
        }
        Principal principal = authorize(ex);
        if (principal == null) return;

        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(readBounded(is, config.transport().maxBodyBytes()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            sendJson(ex, 413, rpcError(null, -32700, "Request too large"));
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
            if (arr.size() > config.transport().maxBatchSize()) {
                sendJson(ex, 400, rpcError(null, -32600, "Batch too large"));
                return;
            }
            JsonArray out = new JsonArray();
            for (JsonElement el : arr) {
                JsonObject resp = handleRpc(el.isJsonObject() ? el.getAsJsonObject() : new JsonObject(), ex, principal);
                if (resp != null) out.add(resp);
            }
            sendJson(ex, 200, out);
        } else if (rootEl.isJsonObject()) {
            JsonObject resp = handleRpc(rootEl.getAsJsonObject(), ex, principal);
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

    private JsonObject handleRpc(JsonObject req, HttpExchange ex, Principal principal) {
        JsonElement id = req.has("id") ? req.get("id") : null;
        String rpcMethod = optString(req, "method");
        JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
        if (rpcMethod == null) return rpcError(id, -32600, "Invalid Request");

        boolean needsSession = switch (rpcMethod) {
            case "tools/list", "tools/call", "resources/list", "resources/read",
                 "notifications/initialized", "initialized" -> true;
            default -> false;
        };
        if (needsSession && sessionFor(ex, principal) == null) {
            return rpcError(id, -32000, "Invalid or missing session; call initialize first");
        }

        try {
            return switch (rpcMethod) {
                case "initialize" -> rpcResult(id, handleInitialize(params, ex, principal));
                case "notifications/initialized", "initialized" -> null;
                case "ping" -> rpcResult(id, Map.of());
                case "tools/list" -> rpcResult(id, handleToolsList());
                case "tools/call" -> rpcResult(id, handleToolsCall(params, caller(ex, principal)));
                case "resources/list" -> rpcResult(id, handleResourcesList());
                case "resources/read" -> handleResourcesRead(params, id, caller(ex, principal));
                default -> rpcError(id, -32601, "Method not found: " + rpcMethod);
            };
        } catch (Exception e) {
            logger.log(Level.WARNING, "MCP method error: " + rpcMethod, e);
            return rpcError(id, -32000, "Internal error");
        }
    }

    private Map<String, Object> handleInitialize(JsonObject params, HttpExchange ex, Principal principal) {
        String requested = optString(params, "protocolVersion");
        String version = requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        McpSession session = newSession(principal);
        ex.getResponseHeaders().set("Mcp-Session-Id", session.id());

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of("listChanged", true));
        caps.put("resources", Map.of("listChanged", false));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "EdenMcp");
        info.put("version", "1.0");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("protocolVersion", version);
        res.put("capabilities", caps);
        res.put("serverInfo", info);
        res.put("mode", mode.get().name().toLowerCase(Locale.ROOT));
        res.put("sessionId", session.id());
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
        if (name == null || name.isBlank()) return toolCallResponse(false, "Missing tool name");
        Optional<McpTool> toolOpt = registry.get(name);
        if (toolOpt.isEmpty()) {
            audit.toolCall(caller, name, mode.get().name(), false, "not_found");
            return toolCallResponse(false, "Tool not found or not available: " + name);
        }
        McpTool tool = toolOpt.get();
        OperatingMode active = mode.get();
        if (!securityPolicy.canExecute(active, tool)) {
            audit.toolCall(caller, name, active.name(), false, "denied_mode");
            return toolCallResponse(false, "Tool not permitted in mode " + active.name());
        }
        try {
            ToolResult result = tool.execute(new ToolContext(active, caller, args));
            audit.toolCall(caller, name, active.name(), result.success(), result.success() ? "ok" : "tool_error");
            Object payload = result.success() ? result.content()
                    : Map.of("error", Objects.requireNonNull(result.error(), "Tool error message"));
            return toolCallResponse(result.success(), payload == null ? Map.of() : payload);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Tool execution failed: " + name, e);
            audit.toolCall(caller, name, active.name(), false, "exception:" + e.getClass().getSimpleName());
            return toolCallResponse(false, "Execution error");
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

    private Map<String, Object> handleResourcesList() {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (McpResource resource : resources.all()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uri", resource.uri());
            map.put("name", resource.name());
            if (resource.description() != null) map.put("description", resource.description());
            if (resource.mimeType() != null) map.put("mimeType", resource.mimeType());
            if (!resource.metadata().isEmpty()) map.put("_meta", resource.metadata());
            listed.add(map);
        }
        return Map.of("resources", listed);
    }

    private JsonObject handleResourcesRead(JsonObject params, JsonElement id, String caller) {
        String uri = optString(params, "uri");
        if (uri == null || uri.isBlank()) return rpcError(id, -32602, "Missing resource uri");
        OperatingMode active = mode.get();
        Optional<McpResource> resource = resources.get(uri);
        if (resource.isEmpty()) {
            audit.resourceRead(caller, uri, active.name(), false, "not_found");
            return rpcError(id, -32002, "Resource not found: " + uri);
        }
        try {
            var contents = resource.get().read(new ResourceContext(active, caller, uri));
            audit.resourceRead(caller, uri, active.name(), true, "ok");
            return rpcResult(id, Map.of("contents", List.of(contents.toMap())));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Resource read failed: " + uri, e);
            audit.resourceRead(caller, uri, active.name(), false, "exception:" + e.getClass().getSimpleName());
            return rpcError(id, -32000, "Resource read failed: " + uri);
        }
    }

    // ---- sessions --------------------------------------------------------

    private McpSession newSession(Principal principal) {
        pruneSessions();
        if (sessions.size() >= config.transport().maxSessions()) {
            sessions.values().stream()
                    .min(Comparator.comparingLong(McpSession::lastAccess))
                    .ifPresent(oldest -> sessions.remove(oldest.id(), oldest));
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        McpSession session = new McpSession(id, principal, System.currentTimeMillis());
        sessions.put(id, session);
        return session;
    }

    private McpSession sessionFor(HttpExchange ex, Principal principal) {
        String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sid == null || sid.isBlank()) sid = sessionIdParam(ex.getRequestURI().getRawQuery());
        if (sid == null || sid.isBlank()) return null;
        McpSession session = sessions.get(sid);
        if (session == null) return null;
        if (principal == null || !session.principal().id().equals(principal.id())) return null;
        if (session.expired(System.currentTimeMillis(), config.transport().sessionIdleSeconds() * 1000L)) {
            sessions.remove(sid, session);
            return null;
        }
        session.touch();
        return session;
    }

    private void pruneSessions() {
        long now = System.currentTimeMillis();
        if (now - lastSessionPrune < config.transport().sessionPruneIntervalMs()) return;
        lastSessionPrune = now;
        long idle = config.transport().sessionIdleSeconds() * 1000L;
        sessions.values().removeIf(s -> s.expired(now, idle));
    }

    private static String sessionIdParam(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            try {
                if (URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8).equals("sessionId")) {
                    return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // Malformed encoding: treat as no session.
            }
        }
        return null;
    }

    private static final class McpSession {
        private final String id;
        private final Principal principal;
        private volatile long lastAccess;

        McpSession(String id, Principal principal, long createdAt) {
            this.id = id;
            this.principal = principal;
            this.lastAccess = createdAt;
        }

        String id() { return id; }
        Principal principal() { return principal; }
        long lastAccess() { return lastAccess; }
        void touch() { lastAccess = System.currentTimeMillis(); }
        boolean expired(long now, long idleMillis) { return now - lastAccess > idleMillis; }
    }

    // ---- auth ------------------------------------------------------------

    private Principal authorize(HttpExchange ex) throws IOException {
        if (!checkRequestTarget(ex)) return null;

        String ip = callerIp(ex);
        FailureRecord record = failures.get(ip);
        if (record != null) {
            if (record.blockUntil > System.currentTimeMillis()) {
                sendJson(ex, 429, Map.of("error", "too many failed authentication attempts"));
                return null;
            }
            if (record.blockUntil > 0) failures.remove(ip);
        }
        pruneFailures();

        String provided = extractKey(ex);
        ex.setAttribute("edenmcp.presented-key", provided);
        Principal principal = keyManager.authenticate(provided);
        if (principal == null) {
            audit.authFailure(caller(ex, null));
            recordFailure(ip);
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendJson(ex, 401, rpcError(null, -32001, "Unauthorized: missing or invalid access key"));
            return null;
        }
        failures.remove(ip);
        pruneSessions();
        return principal;
    }

    private String extractKey(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            if (auth.length() > 7 && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return auth.substring(7);
            }
            return null; // Never treat a raw Authorization value as the key.
        }
        if (config.auth().allowLegacyHeader()) {
            return ex.getRequestHeaders().getFirst("X-Eden-Key");
        }
        return null; // No query-string credentials.
    }

    private boolean handlePreflight(HttpExchange ex) throws IOException {
        if (!explicitOrigins) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigins.contains(origin.trim())) return false;
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin.trim());
        h.set("Vary", "Origin");
        h.set("Access-Control-Allow-Methods", "POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Mcp-Session-Id");
        h.set("Access-Control-Max-Age", String.valueOf(config.transport().corsMaxAgeSeconds()));
        ex.sendResponseHeaders(204, -1);
        ex.close();
        return true;
    }

    private boolean checkRequestTarget(HttpExchange ex) throws IOException {
        List<String> origins = ex.getRequestHeaders().get("Origin");
        if (origins != null && !origins.isEmpty()) {
            String origin = origins.getFirst().trim();
            if (explicitOrigins) {
                if (!allowedOrigins.contains(origin)) {
                    logger.warning("[SECURITY] Rejected request with disallowed Origin: " + origin);
                    sendJson(ex, 403, Map.of("error", "origin not allowed"));
                    return false;
                }
                // Exact-match echo only; no wildcard and no credentialed CORS by default.
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                ex.getResponseHeaders().set("Vary", "Origin");
            } else {
                String originHost = null;
                try {
                    URI u = URI.create(origin);
                    originHost = u.getHost();
                } catch (Exception ignored) {
                }
                if (!isAllowedHost(originHost)) {
                    logger.warning("[SECURITY] Rejected request with disallowed Origin: " + origin);
                    sendJson(ex, 403, Map.of("error", "origin not allowed"));
                    return false;
                }
            }
        }
        String host = hostOf(ex.getRequestHeaders().getFirst("Host"));
        if (host != null && !isAllowedHost(host)
                && !(wildcardBind && !explicitHosts && (origins == null || origins.isEmpty()))) {
            logger.warning("[SECURITY] Rejected request with disallowed Host: " + host);
            sendJson(ex, 403, Map.of("error", "host not allowed"));
            return false;
        }
        return true;
    }

    private boolean isAllowedHost(String value) {
        if (value == null) return false;
        String host = value.toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(host)) return true;
        return host.startsWith("[") && host.endsWith("]") && allowedHosts.contains(host.substring(1, host.length() - 1));
    }

    private static String hostOf(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return null;
        String v = hostHeader.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end > 0 ? v.substring(1, end) : v;
        }
        int first = v.indexOf(':');
        if (first > 0 && first == v.lastIndexOf(':')) return v.substring(0, first);
        return v;
    }

    private void recordFailure(String ip) {
        FailureRecord rec = failures.computeIfAbsent(ip, ignored -> new FailureRecord());
        rec.count++;
        if (rec.count >= config.auth().maxFailures()) {
            rec.blockUntil = System.currentTimeMillis() + config.auth().blockSeconds() * 1000L;
            rec.count = 0;
            logger.warning("[SECURITY] Auth failure limit reached for " + ip + "; blocking for "
                    + config.auth().blockSeconds() + "s");
        }
    }

    private void pruneFailures() {
        if (failures.size() < config.transport().authFailurePruneThreshold()) return;
        long now = System.currentTimeMillis();
        failures.entrySet().removeIf(e -> e.getValue().blockUntil < now && e.getValue().count == 0);
    }

    private static final class FailureRecord {
        private int count;
        private long blockUntil;
    }

    private String caller(HttpExchange ex, Principal principal) {
        String id = principal == null ? "?" : principal.display();
        try {
            return "key=" + id + "@" + (ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress());
        } catch (Exception e) {
            return "key=" + id + "@unknown";
        }
    }

    private static String callerIp(HttpExchange ex) {
        try {
            return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) { return "unknown"; }
    }

    private byte[] readBounded(InputStream is, int max) throws IOException {
        int chunkSize = config.transport().readBufferBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, chunkSize));
        byte[] buf = new byte[chunkSize];
        int total = 0;
        while (true) {
            int n = is.read(buf);
            if (n < 0) break;
            total += n;
            if (total > max) throw new IOException("Request body too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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

    // ---- TLS -------------------------------------------------------------

    private SSLContext createSslContext(EdenConfig.TlsConfig tls, Path dataFolder) throws Exception {
        String mode = tls.mode() == null ? "self-signed" : tls.mode();
        if ("custom".equalsIgnoreCase(mode)) {
            if (tls.keystorePath() == null || tls.keystorePath().isBlank()) {
                throw new IllegalStateException("Custom TLS mode requires keystore-path");
            }
            Path ks = Path.of(tls.keystorePath());
            PrivateFileUtil.requireOwnerOnly(ks, "TLS keystore");
            char[] pass = tls.keystorePassword() == null ? new char[0] : tls.keystorePassword().toCharArray();
            SSLContext ctx = sslFromKeystore(ks, pass, tls);
            logFingerprint(ks, pass);
            return ctx;
        }

        Path ks = dataFolder.resolve("mcp-keystore.p12");
        Path passFile = dataFolder.resolve("mcp-keystore.pass");
        char[] pass;
        if (Files.exists(ks) && Files.exists(passFile)) {
            PrivateFileUtil.requireOwnerOnly(ks, "TLS keystore");
            PrivateFileUtil.requireOwnerOnly(passFile, "TLS keystore password file");
            pass = Files.readString(passFile, StandardCharsets.UTF_8).trim().toCharArray();
        } else {
            if (Files.exists(ks)) {
                logger.warning("[SECURITY] Self-signed keystore has no password file; regenerating it. "
                        + "Clients should verify the new certificate fingerprint.");
                Files.deleteIfExists(ks);
            }
            pass = randomPassword();
            boolean generated = tryGenerateSelfSigned(ks, new String(pass), tls);
            if (!generated) throw new IllegalStateException("Failed to generate self-signed keystore.");
            Files.writeString(passFile, new String(pass) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            PrivateFileUtil.setOwnerOnly(passFile);
        }
        PrivateFileUtil.setOwnerOnly(ks);
        SSLContext ctx = sslFromKeystore(ks, pass, tls);
        logFingerprint(ks, pass);
        return ctx;
    }

    private static char[] randomPassword() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw).toCharArray();
    }

    private boolean tryGenerateSelfSigned(Path ks, String pass, EdenConfig.TlsConfig tls) {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isExecutable(keytool)) {
            logger.warning("keytool is not available at " + keytool
                    + "; cannot generate a self-signed certificate.");
            return false;
        }
        try {
            Path parent = ks.getParent();
            if (parent != null) Files.createDirectories(parent);
            String dname = "CN=EdenMcp, OU=EdenMcp, O=EdenMcp, L=Local, S=Local, C=XX";
            ProcessBuilder pb = new ProcessBuilder(
                    keytool.toString(), "-genkeypair",
                    "-alias", "edenmcp",
                    "-keyalg", "RSA",
                    "-keysize", String.valueOf(tls.selfSignedKeySize()),
                    "-storetype", "PKCS12",
                    "-keystore", ks.toAbsolutePath().toString(),
                    "-validity", String.valueOf(tls.selfSignedValidityDays()),
                    "-storepass", pass,
                    "-keypass", pass,
                    "-dname", dname
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code == 0 && Files.exists(ks)) return true;
            logger.warning("keytool could not generate a self-signed certificate (exit " + code + "): "
                    + out.substring(0, Math.min(300, out.length())));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while generating a self-signed certificate", e);
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not run keytool for self-signed cert generation", e);
            return false;
        }
    }

    private void logFingerprint(Path ks, char[] pass) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream is = Files.newInputStream(ks)) {
                store.load(is, pass);
            }
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate cert = store.getCertificate(aliases.nextElement());
                if (cert == null) continue;
                String hex = HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                StringBuilder fp = new StringBuilder();
                for (int i = 0; i < hex.length(); i += 2) {
                    if (i > 0) fp.append(':');
                    fp.append(hex, i, i + 2);
                }
                logger.info("MCP TLS certificate SHA-256 fingerprint: " + fp + " (pin this in your client)");
                return;
            }
        } catch (Exception e) {
            logger.warning("Could not compute MCP TLS certificate fingerprint: " + e.getMessage());
        }
    }

    private SSLContext sslFromKeystore(Path ks, char[] pass, EdenConfig.TlsConfig tls) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(ks)) {
            store.load(is, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pass);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (tls.requireClientCert()) {
            if (tls.truststorePath() == null || tls.truststorePath().isBlank()) {
                throw new IllegalStateException("mcp.transport.tls.require-client-cert requires truststore-path");
            }
            Path trustPath = Path.of(tls.truststorePath());
            PrivateFileUtil.requireOwnerOnly(trustPath, "TLS truststore");
            char[] trustPass = tls.truststorePassword() == null || tls.truststorePassword().isBlank()
                    ? pass : tls.truststorePassword().toCharArray();
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream is = Files.newInputStream(trustPath)) {
                trust.load(is, trustPass);
            }
            tmf.init(trust);
        } else {
            tmf.init(store);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    static SSLParameters hardenedSslParameters(SSLContext ctx, boolean requireClientCert) {
        SSLParameters params = ctx.getSupportedSSLParameters();
        List<String> suites = new ArrayList<>();
        for (String suite : params.getCipherSuites()) {
            if (suite.contains("_GCM_") || suite.contains("_CHACHA20_POLY1305_")) suites.add(suite);
        }
        params.setCipherSuites(suites.toArray(new String[0]));
        params.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        // setNeedClientAuth(true) also clears wantClientAuth; do not call
        // setWantClientAuth afterwards or it silently clears needClientAuth.
        params.setNeedClientAuth(requireClientCert);
        return params;
    }

    public void broadcastModeChange(@Nullable OperatingMode newMode) {
        String name = newMode == null ? "unknown" : newMode.name().toLowerCase(Locale.ROOT);
        for (SseClient c : sseClients) {
            c.sendEvent("mode", Map.of("mode", name));
        }
    }

    private static final class SseClient {
        private final HttpExchange exchange;
        private final OutputStream os;
        private final String principalId;
        private final Thread owner;
        private volatile boolean open = true;

        SseClient(HttpExchange exchange, String principalId) {
            this.exchange = exchange;
            this.os = exchange.getResponseBody();
            this.principalId = principalId;
            this.owner = Thread.currentThread();
        }

        String principalId() { return principalId; }

        synchronized void sendEvent(String event, Object data) {
            if (!open) return;
            try {
                String payload = "event: " + event + "\n" + "data: " + GSON.toJson(data) + "\n\n";
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                close();
            }
        }

        synchronized boolean sendKeepalive() {
            if (!open) return false;
            try {
                os.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
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
            if (Thread.currentThread() != owner) owner.interrupt();
        }
    }
}
