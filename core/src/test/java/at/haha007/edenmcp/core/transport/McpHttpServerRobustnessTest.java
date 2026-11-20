package at.haha007.edenmcp.core.transport;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.HookContext;
import at.haha007.edenmcp.api.McpResource;
import at.haha007.edenmcp.api.McpTool;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.ResourceContents;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.audit.AuditLogger;
import at.haha007.edenmcp.core.auth.AccessKeyManager;
import at.haha007.edenmcp.core.config.CommandClassification;
import at.haha007.edenmcp.core.config.EdenConfig;
import at.haha007.edenmcp.core.hook.HookManager;
import at.haha007.edenmcp.core.tool.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Response-size and per-call deadline behavior of the MCP transport. */
class McpHttpServerRobustnessTest {
    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("robustness-test-" + System.nanoTime());
    private final List<String> logMessages = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Handler logCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logMessages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private McpHttpServer server;
    private String key;
    private String session;
    private ToolRegistry tools;
    private HookManager hooks;

    @BeforeEach
    void attachLogCapture() {
        logger.addHandler(logCapture);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
        logger.removeHandler(logCapture);
    }

    private void start(int maxResponseChars, int callTimeoutSeconds) throws Exception {
        start(maxResponseChars, callTimeoutSeconds, 8, 32, 256);
    }

    private void start(int maxResponseChars, int callTimeoutSeconds,
                       int coreThreads, int maxThreads, int queueCapacity) throws Exception {
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
                coreThreads, maxThreads, 60, queueCapacity, 1, 15000, 30000, 600, 1000, 8192,
                tls, maxResponseChars, callTimeoutSeconds);
        EdenConfig config = new EdenConfig(true, OperatingMode.SAFE, transport,
                new EdenConfig.AuthConfig(false, 10, 60, 0, null),
                new EdenConfig.PolicyConfig(true, false),
                EdenConfig.ToolConfig.defaults(),
                true, true, false, 200, Map.of(),
                new EdenConfig.ExternalHookConfig(true, CapabilityLevel.MODERATE_ACTION, false, Map.of()),
                CommandClassification.empty());

        tools = new ToolRegistry();
        hooks = new HookManager(tools, new HookContext(null, null, null, logger));
        server = new McpHttpServer(config, keyManager, tools, hooks,
                new AuditLogger(logger), logger, new AtomicReference<>(OperatingMode.SAFE), Map::of);
        server.start(data);

        JsonObject init = post("initialize", new JsonObject(), null).body();
        assertFalse(init.has("error"), init.toString());
        session = init.getAsJsonObject("result").get("sessionId").getAsString();
    }

    private record Resp(int status, String raw, JsonObject body) {}

    private Resp post(String method, JsonObject params, String sessionId) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", 1);
        req.addProperty("method", method);
        req.add("params", params);
        return postRaw(req.toString(), sessionId);
    }

    private Resp postRaw(String body) throws Exception {
        return postRaw(body, null);
    }

    private Resp postRaw(String body, String sessionId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + "/mcp")
                .toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        if (sessionId != null) c.setRequestProperty("Mcp-Session-Id", sessionId);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        var stream = status < 400 ? c.getInputStream() : c.getErrorStream();
        String raw = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        return new Resp(status, raw,
                raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject());
    }

    private Resp callTool(String name) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", new JsonObject());
        return post("tools/call", params, session);
    }

    private Resp readResource(String uri) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return post("resources/read", params, session);
    }

    private static JsonObject toolResult(Resp resp) {
        assertFalse(resp.body().has("error"), resp.raw());
        return resp.body().getAsJsonObject("result");
    }

    private static String toolText(Resp resp) {
        JsonArray content = toolResult(resp).getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject item = content.get(0).getAsJsonObject();
        assertEquals("text", item.get("type").getAsString());
        return item.get("text").getAsString();
    }

    private static McpTool fixedTool(String name, ToolResult result) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of("test"); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { return result; }
        };
    }

    private static McpTool throwingTool(String name) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "failing test tool"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of("test"); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) { throw new IllegalStateException("boom"); }
        };
    }

    private static McpTool sleepingTool() {
        return new McpTool() {
            @Override public String name() { return "edenmcp.test.slow"; }
            @Override public String description() { return "slow test tool"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of("test"); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) throws Exception {
                Thread.sleep(8_000);
                return ToolResult.ok("finished");
            }
        };
    }

    @Test
    void hugeMapToolResultIsTruncatedToValidJson() throws Exception {
        start(2000, 30);
        tools.register(fixedTool("edenmcp.test.huge", ToolResult.ok(Map.of("blob", "x".repeat(100_000)))));

        Resp resp = callTool("edenmcp.test.huge");
        assertEquals(200, resp.status());
        assertFalse(toolResult(resp).get("isError").getAsBoolean());
        String text = toolText(resp);
        JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(parsed.get("truncated").getAsBoolean(), text);
        assertTrue(parsed.get("omittedCount").getAsInt() >= 1, text);
        assertFalse(text.contains("x".repeat(100)), "oversized payload should be gone");
        assertTrue(resp.raw().length() <= 2000, "response was " + resp.raw().length() + " chars");
        // Whole response is still valid JSON-RPC with the original envelope shape.
        assertEquals("2.0", resp.body().get("jsonrpc").getAsString());
        assertEquals(1, resp.body().get("id").getAsInt());
    }

    @Test
    void hugeStringToolResultGetsExplicitMarker() throws Exception {
        start(2000, 30);
        tools.register(fixedTool("edenmcp.test.huge-string", ToolResult.ok("y".repeat(100_000))));

        Resp resp = callTool("edenmcp.test.huge-string");
        String text = toolText(resp);
        assertTrue(text.endsWith("[truncated]"), "unexpected text length " + text.length());
        assertTrue(text.length() < 2000);
        assertTrue(resp.raw().length() <= 2000);
        assertNotNull(JsonParser.parseString(resp.raw()));
    }

    @Test
    void slowToolTimesOutWithCleanErrorAndTimeoutAudit() throws Exception {
        start(1_000_000, 1);
        tools.register(sleepingTool());

        long startNanos = System.nanoTime();
        Resp resp = callTool("edenmcp.test.slow");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(200, resp.status());
        assertTrue(elapsedMs < 5_000, "slow call blocked for " + elapsedMs + "ms");
        assertTrue(toolResult(resp).get("isError").getAsBoolean(), resp.raw());
        assertTrue(toolText(resp).contains("timed out"), resp.raw());
        assertTrue(logMessages.stream().anyMatch(m -> m.contains("outcome=timeout")),
                "audit should record the timeout: " + logMessages);
    }

    @Test
    void slowToolDoesNotOccupyTheOnlyHttpWorker() throws Exception {
        // One HTTP worker, one queued request: a tool that runs on the worker would block ping.
        start(1_000_000, 1, 1, 1, 1);
        tools.register(sleepingTool());

        AtomicReference<Resp> slowResp = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                slowResp.set(callTool("edenmcp.test.slow"));
            } catch (Exception e) {
                slowResp.set(new Resp(0, e.toString(), new JsonObject()));
            }
        });
        caller.start();
        Thread.sleep(150); // let the slow call get going

        long pingStart = System.nanoTime();
        Resp ping = post("ping", new JsonObject(), null);
        long pingMs = (System.nanoTime() - pingStart) / 1_000_000;
        caller.join(10_000);

        assertFalse(ping.body().has("error"), ping.raw());
        assertTrue(pingMs < 5_000, "ping waited " + pingMs + "ms behind the slow tool");
        assertNotNull(slowResp.get());
        assertTrue(toolResult(slowResp.get()).get("isError").getAsBoolean());
    }

    @Test
    void hugeStructuredResourceTextIsTruncatedToValidJson() throws Exception {
        start(2000, 30);
        hooks.resources().register(McpResource.text("edenmcp://test/huge", "Huge", null, "application/json",
                () -> Map.of("blob", "z".repeat(100_000))));

        Resp resp = readResource("edenmcp://test/huge");
        assertFalse(resp.body().has("error"), resp.raw());
        JsonArray contents = resp.body().getAsJsonObject("result").getAsJsonArray("contents");
        assertEquals(1, contents.size());
        String text = contents.get(0).getAsJsonObject().get("text").getAsString();
        JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(parsed.get("truncated").getAsBoolean(), text);
        assertTrue(parsed.get("omittedCount").getAsInt() >= 1, text);
        assertTrue(resp.raw().length() <= 2000, "response was " + resp.raw().length() + " chars");
    }

    @Test
    void slowResourceReadTimesOutWithRpcError() throws Exception {
        start(1_000_000, 1);
        hooks.resources().register(new McpResource("edenmcp://test/slow", "Slow", null, "text/plain", Map.of(),
                ctx -> {
                    Thread.sleep(8_000);
                    return ResourceContents.text(ctx.uri(), "text/plain", "finished");
                }));

        long startNanos = System.nanoTime();
        Resp resp = readResource("edenmcp://test/slow");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "slow resource read blocked for " + elapsedMs + "ms");
        assertTrue(resp.body().has("error"), resp.raw());
        assertEquals(-32000, resp.body().getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.body().getAsJsonObject("error").get("message").getAsString().contains("timed out"));
        assertTrue(logMessages.stream().anyMatch(m -> m.contains("outcome=timeout")),
                "audit should record the timeout: " + logMessages);
    }

    @Test
    void configParsesNewTransportKeysWithDocumentedDefaults() {
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("mcp.transport.max-response-chars", 1234);
        custom.set("mcp.transport.call-timeout-seconds", 7);
        custom.set("mcp.transport.max-json-depth", 100);
        EdenConfig parsed = EdenConfig.from(custom, logger);
        assertEquals(1234, parsed.transport().maxResponseChars());
        assertEquals(7, parsed.transport().callTimeoutSeconds());
        assertEquals(100, parsed.transport().maxJsonDepth());

        EdenConfig defaults = EdenConfig.from(new YamlConfiguration(), logger);
        assertEquals(1_000_000, defaults.transport().maxResponseChars());
        assertEquals(30, defaults.transport().callTimeoutSeconds());
        assertEquals(64, defaults.transport().maxJsonDepth());

        custom.set("mcp.transport.max-json-depth", 2);
        assertEquals(8, EdenConfig.from(custom, logger).transport().maxJsonDepth(),
                "max-json-depth has a floor of 8");
    }

    @Test
    void deeplyNestedRequestIsRejectedWith400() throws Exception {
        start(1_000_000, 30);
        String nested = "[".repeat(100) + "[]" + "]".repeat(100);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":" + nested + "}";

        Resp resp = postRaw(body);
        assertEquals(400, resp.status(), resp.raw());
        assertEquals(-32600, resp.body().getAsJsonObject("error").get("code").getAsInt());

        Resp ping = post("ping", new JsonObject(), null);
        assertFalse(ping.body().has("error"), "transport must stay usable: " + ping.raw());
    }

    @Test
    void stackOverflowProneRequestReturnsClean400AndServerSurvives() throws Exception {
        start(1_000_000, 30);
        // GSON's recursive parser overflows the stack long before this depth; if the JVM stack
        // is unusually large the iterative depth check rejects it anyway.
        String nested = "[".repeat(50_000) + "]".repeat(50_000);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":" + nested + "}";

        Resp resp = postRaw(body);
        assertEquals(400, resp.status(), resp.raw());
        assertEquals(-32600, resp.body().getAsJsonObject("error").get("code").getAsInt());

        Resp ping = post("ping", new JsonObject(), null);
        assertFalse(ping.body().has("error"), "server must recover after StackOverflowError: " + ping.raw());
    }

    @Test
    void protocolStringFieldsAreNotCoerced() throws Exception {
        start(1_000_000, 30);
        AtomicInteger invocations = new AtomicInteger();
        tools.register(new McpTool() {
            @Override public String name() { return "123"; }
            @Override public String description() { return "numeric name tool"; }
            @Override public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
            @Override public SideEffects sideEffects() { return SideEffects.NONE; }
            @Override public List<String> tags() { return List.of("test"); }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(ToolContext context) {
                invocations.incrementAndGet();
                return ToolResult.ok("called");
            }
        });

        JsonObject toolParams = new JsonObject();
        toolParams.addProperty("name", 123);
        toolParams.add("arguments", new JsonObject());
        Resp numericName = post("tools/call", toolParams, session);
        assertEquals(0, invocations.get(), "a JSON number must not become the tool name '123'");
        assertTrue(toolResult(numericName).get("isError").getAsBoolean(), numericName.raw());
        assertTrue(toolText(numericName).contains("Missing tool name"), numericName.raw());

        JsonObject methodReq = new JsonObject();
        methodReq.addProperty("jsonrpc", "2.0");
        methodReq.addProperty("id", 1);
        methodReq.addProperty("method", 42);
        Resp numericMethod = postRaw(methodReq.toString());
        assertEquals(-32600, numericMethod.body().getAsJsonObject("error").get("code").getAsInt(),
                numericMethod.raw());

        JsonObject arrayMethodReq = new JsonObject();
        arrayMethodReq.addProperty("jsonrpc", "2.0");
        arrayMethodReq.addProperty("id", 1);
        arrayMethodReq.add("method", new com.google.gson.JsonArray());
        Resp arrayMethod = postRaw(arrayMethodReq.toString());
        assertEquals(-32600, arrayMethod.body().getAsJsonObject("error").get("code").getAsInt(),
                arrayMethod.raw());

        JsonObject uriParams = new JsonObject();
        uriParams.addProperty("uri", 7);
        Resp numericUri = post("resources/read", uriParams, session);
        assertEquals(-32602, numericUri.body().getAsJsonObject("error").get("code").getAsInt(),
                numericUri.raw());
    }

    @Test
    void failingToolNameCannotForgeAuditLines() throws Exception {
        start(1_000_000, 30);
        String evilName = "evil\n[AUDIT] forged success=true";
        tools.register(throwingTool(evilName));

        Resp resp = callTool(evilName);
        assertTrue(toolResult(resp).get("isError").getAsBoolean(), resp.raw());
        assertTrue(logMessages.stream().noneMatch(m -> m.contains("\n") || m.contains("\r")),
                "no log line may contain a newline: " + logMessages);
        assertTrue(logMessages.stream().noneMatch(m -> m.startsWith("[AUDIT] forged")),
                "tool name must not inject a fake audit record: " + logMessages);
        assertTrue(logMessages.stream().anyMatch(m -> m.contains("Tool execution failed: evil [AUDIT] forged success=true")),
                "sanitized name should still be visible: " + logMessages);
    }

    @Test
    void partialAuthFailureRecordIsEvictedWhenIdle() throws Exception {
        start(1_000_000, 30);
        assertEquals(401, badAuthRequest());

        Field failuresField = McpHttpServer.class.getDeclaredField("failures");
        failuresField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> failures = (Map<String, Object>) failuresField.get(server);
        assertEquals(1, failures.size(), "a single auth failure creates a partial record");
        Object record = failures.values().iterator().next();
        Field lastSeen = record.getClass().getDeclaredField("lastSeen");
        lastSeen.setAccessible(true);
        lastSeen.setLong(record, System.currentTimeMillis() - 200_000L);

        Method prune = McpHttpServer.class.getDeclaredMethod("pruneFailures");
        prune.setAccessible(true);
        prune.invoke(server);
        assertTrue(failures.isEmpty(), "a partial-failure record must expire when idle");
    }

    private int badAuthRequest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + "/mcp")
                .toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + "f".repeat(64));
        try (OutputStream os = c.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        c.disconnect();
        return status;
    }

    @Test
    void processOutputCaptureIsBounded() throws Exception {
        byte[] huge = new byte[200_000];
        Arrays.fill(huge, (byte) 'x');
        String out = McpHttpServer.readBoundedProcessOutput(new ByteArrayInputStream(huge), 64 * 1024);
        assertEquals(64 * 1024, out.length());
    }
}
