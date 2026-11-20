package at.haha007.edenmcp.integration;

import at.haha007.edenmcp.api.*;

import com.google.gson.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("BusyWait") // Bounded polling helpers wait for an external server process; deliberate sleeps.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class McpIntegrationTest {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger(McpIntegrationTest.class.getName());

    private Process serverProcess;
    private Thread outputDrainer;
    private Path serverDir;
    private String baseUrl;
    private int mcpPort;
    private String accessKey;
    private volatile String sessionId;
    private HttpClient httpClient;

    @BeforeAll
    void startServer() throws Exception {
        String serverDirProp = System.getProperty("edenmcp.it.serverDir");
        String paperJar = System.getProperty("edenmcp.it.paperJar", "");
        String javaBin = System.getProperty("edenmcp.it.java", "java");
        String port = System.getProperty("edenmcp.it.mcpPort", "18765");
        String tls = System.getProperty("edenmcp.it.mcpTls", "false");

        assertNotNull(serverDirProp, "edenmcp.it.serverDir not set");
        serverDir = Path.of(serverDirProp);
        assertTrue(Files.isDirectory(serverDir), "Integration server directory missing: " + serverDir);

        if (paperJar == null || paperJar.isBlank()) {
            fail("edenmcp.it.paperJar not set. Run ./gradlew test to provision the default Paper jar or pass -Pintegration.paperJar=/path/to/paper.jar");
        }
        Path paperJarPath = Path.of(paperJar);
        assertTrue(Files.exists(paperJarPath), "Paper jar not found: " + paperJarPath);

        deleteIfExists(serverDir.resolve("plugins/EdenMcp/.key"));
        deleteIfExists(serverDir.resolve("plugins/EdenMcp/mcp-keystore.p12"));
        deleteIfExists(serverDir.resolve("plugins/EdenMcp/mcp-keystore.pass"));
        deleteRecursively(serverDir.resolve("world"));
        deleteRecursively(serverDir.resolve("world_nether"));
        deleteRecursively(serverDir.resolve("world_the_end"));
        deleteIfExists(serverDir.resolve("usercache.json"));
        deleteIfExists(serverDir.resolve("banned-ips.json"));
        deleteIfExists(serverDir.resolve("banned-players.json"));
        deleteIfExists(serverDir.resolve("ops.json"));
        deleteIfExists(serverDir.resolve("whitelist.json"));

        List<String> cmd = buildServerCommand(javaBin, paperJarPath, serverDir);
        LOG.info("[IT] Starting server: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(serverDir.toFile());
        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        outputDrainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    LOG.info("[SERVER] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "it-server-log");
        outputDrainer.setDaemon(true);
        outputDrainer.start();

        boolean isTls = "true".equalsIgnoreCase(tls);
        mcpPort = Integer.parseInt(port);
        baseUrl = (isTls ? "https" : "http") + "://127.0.0.1:" + port;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        waitForLocalPort(Integer.parseInt(port), Duration.ofMinutes(3));
        waitForKey(Duration.ofMinutes(2));
        accessKey = Files.readString(serverDir.resolve("plugins/EdenMcp/.key"), StandardCharsets.UTF_8).trim();
        assertFalse(accessKey.isBlank(), "Access key is blank");
        assertTrue(accessKey.matches("[0-9a-f]{64}"), "Access key should be 64 lowercase hex characters");
        waitForMcpReady(Duration.ofMinutes(2));
    }

    @AfterAll
    void stopServer() throws Exception {
        if (serverProcess != null) {
            try {
                OutputStream os = serverProcess.getOutputStream();
                os.write("stop\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {
            }
            boolean exited = serverProcess.waitFor(60, TimeUnit.SECONDS);
            if (!exited) {
                serverProcess.destroyForcibly();
                serverProcess.waitFor(15, TimeUnit.SECONDS);
            }
        }
        if (outputDrainer != null) outputDrainer.join(2000);
    }

    @Test
    @Order(1)
    void initializeAndAuth() throws Exception {
        JsonObject unauth = rpcRaw(null, "initialize", new JsonObject(), 1, false);
        assertTrue(unauth.has("error"), "Expected auth error: " + unauth);

        JsonObject init = rpc("initialize", jsonObj("protocolVersion", "2024-11-05"), 10);
        JsonObject result = init.getAsJsonObject("result");
        assertNotNull(result, "Missing initialize result: " + init);
        assertEquals("EdenMcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertEquals("unsafe", result.get("mode").getAsString(), "Integration server should start in unsafe mode: " + init);
    }

    @Test
    @Order(2)
    void toolsListIncludesAllTools() throws Exception {
        JsonObject resp = rpc("tools/list", new JsonObject(), 20);
        JsonArray tools = resp.getAsJsonObject("result").getAsJsonArray("tools");
        assertNotNull(tools);
        assertTrue(hasTool(tools, "edenmcp.server.status"));
        assertTrue(hasTool(tools, "edenmcp.plugins.list"));
        assertTrue(hasTool(tools, "edenmcp.players.list"));
        assertTrue(hasTool(tools, "edenmcp.logs.read"));
        assertTrue(hasTool(tools, "edenmcp.file.list"));
        assertTrue(hasTool(tools, "edenmcp.file.read"));
        assertTrue(hasTool(tools, "edenmcp.file.write"));
        assertTrue(hasTool(tools, "edenmcp.mode.get"));
        assertTrue(hasTool(tools, "edenmcp.mode.set"));
        assertTrue(hasTool(tools, "edenmcp.hooks.list"));
        assertTrue(hasTool(tools, "edenmcp.chat.send"));
        assertTrue(hasTool(tools, "edenmcp.command.dispatch"));
        assertTrue(hasTool(tools, "edenmcp.luckperms.groups.list"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.lookup"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.permission.check"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.group.get"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.tracks.list"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.track.get"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.groups"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.audit.read"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.permission.set"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.group.permission.set"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.group.create"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.group.add"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.group.remove"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.group.meta.set"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.track.promote"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.luckperms.user.track.demote"), "LuckPerms mutations should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.stats.cpu"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.stats.tps"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.stats.mspt"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.stats.gc"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.profiler.control"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.profiler.info"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.diagnostics.run"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.data.list"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.data.read"), "Spark hook should be available: " + tools);
        assertTrue(hasTool(tools, "edenmcp.spark.data.inspect"), "Spark hook should be available: " + tools);
    }

    @Test
    @Order(7)
    void resourcesListIncludesCoreAndHookResources() throws Exception {
        JsonObject resp = rpc("resources/list", new JsonObject(), 220);
        JsonArray resources = resp.getAsJsonObject("result").getAsJsonArray("resources");
        assertNotNull(resources);
        assertTrue(hasResource(resources, "edenmcp://luckperms/groups"),
                "LuckPerms hook should contribute a resource: " + resources);
        assertTrue(hasResource(resources, "edenmcp://spark/stats"),
                "Spark hook should contribute a resource: " + resources);
    }

    @Test
    @Order(8)
    void resourcesReadReturnsContributedContent() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", "edenmcp://luckperms/groups");
        JsonObject result = rpc("resources/read", params, 221).getAsJsonObject("result");
        JsonObject content = result.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("edenmcp://luckperms/groups", content.get("uri").getAsString());
        assertEquals("application/json", content.get("mimeType").getAsString());
        JsonObject payload = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
        assertTrue(payload.get("groups").isJsonArray(), payload.toString());
        assertEquals(payload.getAsJsonArray("groups").size(), payload.get("count").getAsInt());
    }

    @Test
    @Order(9)
    void resourcesReadSparkStats() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", "edenmcp://spark/stats");
        JsonObject result = rpc("resources/read", params, 222).getAsJsonObject("result");
        JsonObject content = result.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("application/json", content.get("mimeType").getAsString());
        JsonObject payload = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
        assertEquals("spark", payload.get("source").getAsString(), payload.toString());
        assertTrue(payload.has("tps"), payload.toString());
    }

    @Test
    @Order(3)
    void authRejectsWrongRawAndLegacyCredentials() throws Exception {
        Map<String, String> wrongKey = new LinkedHashMap<>();
        wrongKey.put("Authorization", "Bearer " + "0".repeat(64));
        HttpResponse<String> r1 = sendRaw("/mcp", pingBody(30), wrongKey);
        assertEquals(401, r1.statusCode());
        assertEquals("Bearer", r1.headers().firstValue("WWW-Authenticate").orElse(null));

        Map<String, String> rawAuth = new LinkedHashMap<>();
        rawAuth.put("Authorization", accessKey);
        assertEquals(401, sendRaw("/mcp", pingBody(31), rawAuth).statusCode());

        Map<String, String> legacy = new LinkedHashMap<>();
        legacy.put("X-Eden-Key", accessKey);
        assertEquals(401, sendRaw("/mcp", pingBody(32), legacy).statusCode());

        HttpResponse<String> query = sendRaw("/mcp?key=" + accessKey + "&access_key=" + accessKey, pingBody(33), Map.of());
        assertEquals(401, query.statusCode());
    }

    @Test
    @Order(4)
    void hostAndOriginAreValidated() throws Exception {
        assertEquals(403, rawHttpStatus("evil.example", null));
        assertEquals(403, rawHttpStatus("127.0.0.1:" + mcpPort, "https://evil.example"));
        assertEquals(200, rawHttpStatus("127.0.0.1:" + mcpPort, null));
    }

    @Test
    @Order(5)
    void requestSizeAndBatchAreBounded() throws Exception {
        Map<String, String> auth = Map.of("Authorization", "Bearer " + accessKey);
        byte[] huge = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) 'a');
        HttpRequest.Builder oversized = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(huge));
        assertEquals(413, httpClient.send(oversized.build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) batch.append(',');
            batch.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i).append(",\"method\":\"ping\"}");
        }
        batch.append(']');
        assertEquals(400, sendRaw("/mcp", batch.toString(), auth).statusCode());
    }

    @Test
    @Order(6)
    void sessionIsRequiredForToolRequests() throws Exception {
        Map<String, String> auth = Map.of("Authorization", "Bearer " + accessKey);

        HttpResponse<String> noSession = sendRaw("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}", auth);
        assertEquals(200, noSession.statusCode());
        JsonObject body = JsonParser.parseString(noSession.body()).getAsJsonObject();
        assertTrue(body.has("error"), "tools/list without a session must be rejected: " + body);
        assertEquals(-32000, body.getAsJsonObject("error").get("code").getAsInt());

        HttpResponse<String> badSession = sendRaw("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}",
                Map.of("Authorization", "Bearer " + accessKey, "Mcp-Session-Id", "0".repeat(32)));
        assertTrue(JsonParser.parseString(badSession.body()).getAsJsonObject().has("error"));

        // ping is a connectivity check and needs no session.
        HttpResponse<String> resourceList = sendRaw("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":38,\"method\":\"resources/list\"}", auth);
        assertTrue(JsonParser.parseString(resourceList.body()).getAsJsonObject().has("error"),
                "resources/list without a session must be rejected");

        HttpResponse<String> resourceRead = sendRaw("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":39,\"method\":\"resources/read\",\"params\":{\"uri\":\"edenmcp://luckperms/groups\"}}", auth);
        assertTrue(JsonParser.parseString(resourceRead.body()).getAsJsonObject().has("error"),
                "resources/read without a session must be rejected");

        HttpResponse<String> ping = sendRaw("/mcp", pingBody(8), auth);
        assertEquals(200, ping.statusCode());
        assertFalse(JsonParser.parseString(ping.body()).getAsJsonObject().has("error"));
    }

    @Test
    @Order(10)
    void toolModeGet() throws Exception {
        JsonObject res = toolCall("edenmcp.mode.get", new JsonObject(), 100);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("unsafe", payload.get("mode").getAsString(), payload.toString());
    }

    @Test
    @Order(11)
    void toolModeSet() throws Exception {
        JsonObject toModerate = toolCall("edenmcp.mode.set", jsonObj("mode", "moderate"), 110);
        assertFalse(toModerate.get("isError").getAsBoolean(), toModerate.toString());
        assertEquals("moderate", payloadJson(toModerate).get("mode").getAsString());
        assertEquals("moderate", payloadJson(toolCall("edenmcp.mode.get", new JsonObject(), 111)).get("mode").getAsString());

        JsonObject toSafe = toolCall("edenmcp.mode.set", jsonObj("mode", "safe"), 112);
        assertFalse(toSafe.get("isError").getAsBoolean(), toSafe.toString());
        assertEquals("safe", payloadJson(toSafe).get("mode").getAsString());

        JsonArray safeTools = rpc("tools/list", new JsonObject(), 113)
                .getAsJsonObject("result").getAsJsonArray("tools");
        assertFalse(hasTool(safeTools, "edenmcp.command.dispatch"), "dispatch must be hidden in safe mode");
        assertFalse(hasTool(safeTools, "edenmcp.mode.set"), "mode.set must be hidden in safe mode");
        assertFalse(hasTool(safeTools, "edenmcp.chat.send"), "chat.send must be hidden in safe mode");
        assertTrue(hasTool(safeTools, "edenmcp.file.list"), "file listings stay available in safe mode");
        assertTrue(hasTool(safeTools, "edenmcp.file.read"), "file reads stay available in safe mode");
        assertFalse(hasTool(safeTools, "edenmcp.file.write"), "file writes must be hidden in safe mode");
        JsonObject deniedWrite = toolCall("edenmcp.file.write",
                args("path", "denied.txt", "content", "no"), 116);
        assertTrue(deniedWrite.get("isError").getAsBoolean(), "writes must be denied in safe mode: " + deniedWrite);
        assertFalse(hasTool(safeTools, "edenmcp.spark.profiler.control"),
                "Spark profiler control must be hidden in safe mode");
        assertFalse(hasTool(safeTools, "edenmcp.luckperms.user.permission.set"),
                "LuckPerms mutations must be hidden in safe mode");
        assertFalse(hasTool(safeTools, "edenmcp.luckperms.group.create"),
                "LuckPerms mutations must be hidden in safe mode");
        assertTrue(hasTool(safeTools, "edenmcp.spark.stats.cpu"),
                "Spark read-only stats must remain visible in safe mode");

        JsonObject denied = toolCall("edenmcp.command.dispatch", jsonObj("command", "say hello"), 114);
        assertTrue(denied.get("isError").getAsBoolean(), "Expected denial in safe mode: " + denied);

        setUnsafeModeViaConsole();
        assertEquals("unsafe", payloadJson(toolCall("edenmcp.mode.get", new JsonObject(), 115)).get("mode").getAsString());
    }

    @Test
    @Order(12)
    void toolServerStatus() throws Exception {
        JsonObject res = toolCall("edenmcp.server.status", new JsonObject(), 120);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("version"), payload.toString());
        assertTrue(payload.has("bukkitVersion"), payload.toString());
        assertTrue(payload.has("onlinePlayers"), payload.toString());
        assertTrue(payload.has("worlds"), payload.toString());
    }

    @Test
    @Order(22)
    void toolSparkStats() throws Exception {
        int id = 600;
        for (String name : List.of("edenmcp.spark.stats.cpu", "edenmcp.spark.stats.tps",
                "edenmcp.spark.stats.mspt", "edenmcp.spark.stats.gc")) {
            JsonObject result = toolCall(name, new JsonObject(), id++);
            assertFalse(result.get("isError").getAsBoolean(), name + ": " + result);
            JsonObject payload = payloadJson(result);
            assertFalse(payload.has("error"), name + ": " + payload);
        }

        JsonObject windowed = toolCall("edenmcp.spark.stats.tps", jsonObj("window", "1m"), id);
        assertFalse(windowed.get("isError").getAsBoolean(), windowed.toString());
        JsonObject windows = payloadJson(windowed).getAsJsonObject("windows");
        assertTrue(windows.has("MINUTES_1"), windows.toString());
        assertEquals(1, windows.size(), windows.toString());
    }

    @Test
    @Order(23)
    void toolSparkDataListAndBounds() throws Exception {
        JsonObject listArgs = new JsonObject();
        listArgs.addProperty("kind", "all");
        listArgs.addProperty("limit", 10);
        JsonObject result = toolCall("edenmcp.spark.data.list", listArgs, 610);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject payload = payloadJson(result);
        assertTrue(payload.get("files").isJsonArray(), payload.toString());

        JsonObject traversal = toolCall("edenmcp.spark.data.read", jsonObj("file", "../config.yml"), 611);
        assertTrue(traversal.get("isError").getAsBoolean(), traversal.toString());
    }

    @Test
    @Order(24)
    void toolSparkProfilerInfo() throws Exception {
        JsonObject result = toolCall("edenmcp.spark.profiler.info", new JsonObject(), 620);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject payload = payloadJson(result);
        assertTrue(payload.has("command"), payload.toString());
        assertTrue(payload.has("output"), payload.toString());
    }

    @Test
    @Order(25)
    void toolSparkProfilerControlLifecycle() throws Exception {
        // Spark's test config runs a background profiler; discard it before measuring new files.
        JsonObject cancel = toolCall("edenmcp.spark.profiler.control", jsonObj("action", "cancel"), 621);
        assertFalse(cancel.get("isError").getAsBoolean(), cancel.toString());
        JsonObject cancelPayload = payloadJson(cancel);
        assertEquals("spark profiler cancel", cancelPayload.get("command").getAsString(), cancelPayload.toString());
        assertEquals("local-only", cancelPayload.get("storage").getAsString(), cancelPayload.toString());

        Set<String> before = profileNames(622);

        JsonObject startArgs = new JsonObject();
        startArgs.addProperty("action", "start");
        startArgs.addProperty("timeoutSeconds", 60);
        startArgs.addProperty("comment", "MCP integration profile");
        JsonObject start = toolCall("edenmcp.spark.profiler.control", startArgs, 623);
        assertFalse(start.get("isError").getAsBoolean(), start.toString());
        JsonObject startPayload = payloadJson(start);
        assertEquals("spark profiler start --timeout 60 --comment \"MCP integration profile\" --save-to-file",
                startPayload.get("command").getAsString(), startPayload.toString());
        assertEquals("local-only", startPayload.get("storage").getAsString(), startPayload.toString());

        JsonObject info = toolCall("edenmcp.spark.profiler.info", new JsonObject(), 624);
        assertFalse(info.get("isError").getAsBoolean(), info.toString());
        assertTrue(payloadJson(info).get("output").isJsonArray(), info.toString());

        JsonObject stopArgs = new JsonObject();
        stopArgs.addProperty("action", "stop");
        stopArgs.addProperty("comment", "MCP integration profile");
        JsonObject stop = toolCall("edenmcp.spark.profiler.control", stopArgs, 625);
        assertFalse(stop.get("isError").getAsBoolean(), stop.toString());
        JsonObject stopPayload = payloadJson(stop);
        assertEquals("spark profiler stop --save-to-file --comment \"MCP integration profile\"",
                stopPayload.get("command").getAsString(), stopPayload.toString());

        String profile = waitForNewProfile(before);
        assertNotNull(profile, "Expected a locally saved Spark profile");

        JsonObject readArgs = new JsonObject();
        readArgs.addProperty("file", profile);
        readArgs.addProperty("length", 4096);
        JsonObject read = toolCall("edenmcp.spark.data.read", readArgs, 627);
        assertFalse(read.get("isError").getAsBoolean(), read.toString());
        JsonObject readPayload = payloadJson(read);
        assertTrue(readPayload.get("size").getAsLong() > 0, readPayload.toString());
        assertTrue(readPayload.get("returned").getAsInt() > 0, readPayload.toString());
        assertEquals("base64", readPayload.get("encoding").getAsString(), readPayload.toString());
        String data = readPayload.get("data").getAsString();
        assertFalse(data.isBlank(), readPayload.toString());
        assertEquals(readPayload.get("returned").getAsInt(), Base64.getDecoder().decode(data).length);

        JsonObject inspectArgs = new JsonObject();
        inspectArgs.addProperty("file", profile);
        inspectArgs.addProperty("maxDepth", 4);
        inspectArgs.addProperty("maxNodes", 1000);
        inspectArgs.addProperty("maxBytes", 65536);
        JsonObject inspect = toolCall("edenmcp.spark.data.inspect", inspectArgs, 628);
        assertFalse(inspect.get("isError").getAsBoolean(), inspect.toString());
        JsonObject inspectPayload = payloadJson(inspect);
        assertTrue(inspectPayload.has("summary"), inspectPayload.toString());
        assertTrue(inspectPayload.has("tree"), inspectPayload.toString());
        assertTrue(inspectPayload.has("truncated"), inspectPayload.toString());
    }

    @Test
    @Order(26)
    void toolSparkProfilerControlRejectsUnsafeArguments() throws Exception {
        JsonObject missingAction = toolCall("edenmcp.spark.profiler.control", new JsonObject(), 630);
        assertTrue(missingAction.get("isError").getAsBoolean(), missingAction.toString());

        JsonObject badMode = toolCall("edenmcp.spark.profiler.control",
                args("action", "start", "mode", "upload"), 631);
        assertTrue(badMode.get("isError").getAsBoolean(), badMode.toString());

        JsonObject badTimeout = toolCall("edenmcp.spark.profiler.control",
                args("action", "start", "timeoutSeconds", 10), 632);
        assertTrue(badTimeout.get("isError").getAsBoolean(), badTimeout.toString());

        JsonObject badComment = toolCall("edenmcp.spark.profiler.control",
                args("action", "start", "comment", "$(id)"), 633);
        assertTrue(badComment.get("isError").getAsBoolean(), badComment.toString());

        JsonObject badDiagnostic = toolCall("edenmcp.spark.diagnostics.run",
                jsonObj("command", "upload"), 634);
        assertTrue(badDiagnostic.get("isError").getAsBoolean(), badDiagnostic.toString());
    }

    @Test
    @Order(27)
    void toolSparkDiagnosticsRun() throws Exception {
        JsonObject result = toolCall("edenmcp.spark.diagnostics.run", jsonObj("command", "tps"), 640);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject payload = payloadJson(result);
        assertEquals("spark tps", payload.get("command").getAsString(), payload.toString());
        assertTrue(payload.get("output").isJsonArray(), payload.toString());
    }

    @Test
    @Order(13)
    void toolPluginsList() throws Exception {
        JsonObject res = toolCall("edenmcp.plugins.list", new JsonObject(), 130);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonArray plugins = payloadJson(res).getAsJsonArray("plugins");
        assertNotNull(plugins, res.toString());
        boolean eden = false;
        for (JsonElement e : plugins) {
            if ("EdenMcp".equals(e.getAsJsonObject().get("name").getAsString())) eden = true;
        }
        assertTrue(eden, "EdenMcp plugin expected: " + plugins);
    }

    @Test
    @Order(14)
    void toolPlayersList() throws Exception {
        JsonObject res = toolCall("edenmcp.players.list", new JsonObject(), 140);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("count"), payload.toString());
        assertTrue(payload.has("players"), payload.toString());
        assertEquals(0, payload.get("count").getAsInt(), "No players expected in integration test: " + payload);
    }

    @Test
    @Order(15)
    void toolLogsRead() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("limit", 10);
        JsonObject res = toolCall("edenmcp.logs.read", args, 150);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("lines"), payload.toString());
        assertTrue(payload.has("count"), payload.toString());
        assertTrue(payload.get("count").getAsInt() >= 0, payload.toString());

        JsonObject risky = new JsonObject();
        risky.addProperty("filter", "(a+)+$");
        risky.addProperty("regex", true);
        assertTrue(toolCall("edenmcp.logs.read", risky, 151).get("isError").getAsBoolean());
    }

    @Test
    @Order(16)
    void toolFileList() throws Exception {
        JsonObject root = toolCall("edenmcp.file.list", new JsonObject(), 160);
        assertFalse(root.get("isError").getAsBoolean(), root.toString());
        JsonObject rootPayload = payloadJson(root);
        assertEquals(".", rootPayload.get("path").getAsString(), rootPayload.toString());
        assertTrue(rootPayload.get("entries").toString().contains("server.properties"), rootPayload.toString());

        JsonObject nested = toolCall("edenmcp.file.list", jsonObj("path", "plugins/EdenMcp"), 161);
        assertFalse(nested.get("isError").getAsBoolean(), nested.toString());
        assertTrue(payloadJson(nested).get("entries").toString().contains("config.yml"), nested.toString());

        JsonObject traversal = toolCall("edenmcp.file.list", jsonObj("path", "../"), 162);
        assertTrue(traversal.get("isError").getAsBoolean(), traversal.toString());

        JsonObject recursive = toolCall("edenmcp.file.list",
                args("path", "plugins", "recursive", true, "depth", 3), 163);
        assertFalse(recursive.get("isError").getAsBoolean(), recursive.toString());
        JsonObject recursivePayload = payloadJson(recursive);
        assertTrue(recursivePayload.get("recursive").getAsBoolean(), recursivePayload.toString());
        assertEquals(3, recursivePayload.get("depth").getAsInt(), recursivePayload.toString());
        assertTrue(recursivePayload.get("entries").toString().contains("plugins/EdenMcp/config.yml"),
                recursivePayload.toString());
        assertFalse(recursivePayload.get("truncated").getAsBoolean(), recursivePayload.toString());

        JsonObject globals = toolCall("edenmcp.file.list",
                args("path", "plugins", "recursive", true, "pattern", "**/*.yml"), 164);
        assertFalse(globals.get("isError").getAsBoolean(), globals.toString());
        String globEntries = payloadJson(globals).get("entries").toString();
        assertTrue(globEntries.contains("config.yml"), globEntries);
        assertFalse(globEntries.contains("latest.log"), globEntries);

        JsonObject badGlob = toolCall("edenmcp.file.list",
                args("path", "plugins", "recursive", true, "pattern", "["), 165);
        assertTrue(badGlob.get("isError").getAsBoolean(), badGlob.toString());
        JsonObject badDepth = toolCall("edenmcp.file.list",
                args("path", "plugins", "recursive", true, "depth", 999), 166);
        assertTrue(badDepth.get("isError").getAsBoolean(), badDepth.toString());
    }

    @Test
    @Order(40)
    void toolFileReadWrite() throws Exception {
        JsonObject read = toolCall("edenmcp.file.read", jsonObj("path", "server.properties"), 401);
        assertFalse(read.get("isError").getAsBoolean(), read.toString());
        JsonObject readPayload = payloadJson(read);
        assertTrue(readPayload.get("content").getAsString().contains("server-port=25590"), readPayload.toString());
        assertEquals("server.properties", readPayload.get("path").getAsString(), readPayload.toString());

        JsonObject traversal = toolCall("edenmcp.file.read", jsonObj("path", "../outside.txt"), 402);
        assertTrue(traversal.get("isError").getAsBoolean(), traversal.toString());

        String path = "plugins/EdenMcp/file-tool-test.txt";
        JsonObject written = toolCall("edenmcp.file.write", args("path", path, "content", "hello\n"), 403);
        assertFalse(written.get("isError").getAsBoolean(), written.toString());
        assertEquals("hello\n", Files.readString(serverDir.resolve(path)));

        JsonObject reread = toolCall("edenmcp.file.read", jsonObj("path", path), 404);
        assertEquals("hello\n", payloadJson(reread).get("content").getAsString(), reread.toString());

        JsonObject badWrite = toolCall("edenmcp.file.write",
                args("path", "../outside.txt", "content", "hello"), 405);
        assertTrue(badWrite.get("isError").getAsBoolean(), badWrite.toString());

        JsonObject multiLine = toolCall("edenmcp.file.write",
                args("path", path, "content", "a\nb\nc\n"), 406);
        assertFalse(multiLine.get("isError").getAsBoolean(), multiLine.toString());

        JsonObject page = toolCall("edenmcp.file.read",
                args("path", path, "offset", 1, "limit", 1), 407);
        assertFalse(page.get("isError").getAsBoolean(), page.toString());
        JsonObject pagePayload = payloadJson(page);
        assertEquals("b\n", pagePayload.get("content").getAsString(), pagePayload.toString());
        assertEquals(1, pagePayload.get("lineStart").getAsInt(), pagePayload.toString());
        assertEquals(1, pagePayload.get("lineCount").getAsInt(), pagePayload.toString());
        assertTrue(pagePayload.get("hasMore").getAsBoolean(), pagePayload.toString());

        JsonObject lastPage = toolCall("edenmcp.file.read",
                args("path", path, "offset", 2, "limit", 10), 408);
        assertEquals("c\n", payloadJson(lastPage).get("content").getAsString(), lastPage.toString());
        assertFalse(payloadJson(lastPage).get("hasMore").getAsBoolean(), lastPage.toString());

        JsonObject badOffset = toolCall("edenmcp.file.read",
                args("path", path, "offset", -1), 409);
        assertTrue(badOffset.get("isError").getAsBoolean(), badOffset.toString());
        JsonObject badLimit = toolCall("edenmcp.file.read",
                args("path", path, "limit", 0), 410);
        assertTrue(badLimit.get("isError").getAsBoolean(), badLimit.toString());
    }

    @Test
    @Order(17)
    void toolHooksList() throws Exception {
        JsonObject res = toolCall("edenmcp.hooks.list", new JsonObject(), 170);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonArray hooks = payloadJson(res).getAsJsonArray("hooks");
        boolean lp = false;
        for (JsonElement e : hooks) {
            if ("luckperms".equals(e.getAsJsonObject().get("id").getAsString())) lp = true;
        }
        assertTrue(lp, "LuckPerms hook expected: " + hooks);
    }

    @Test
    @Order(18)
    void toolLuckPermsGroupsList() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.groups.list", new JsonObject(), 180);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("groups"), payload.toString());
        assertTrue(payload.has("count"), payload.toString());
        assertTrue(payload.get("count").getAsInt() >= 1, "Expected at least default group: " + payload);
    }

    @Test
    @Order(19)
    void toolLuckPermsUserLookup() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("uuid", "00000000-0000-0000-0000-000000000000");
        JsonObject res = toolCall("edenmcp.luckperms.user.lookup", args, 190);
        assertNotNull(res, "Missing tool result");
        assertTrue(res.has("isError"), res.toString());
        assertTrue(res.has("content"), res.toString());
    }

    @Test
    @Order(28)
    void toolLuckPermsGroupGet() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.group.get", args("name", "default", "limit", 25), 181);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("default", payload.get("name").getAsString(), payload.toString());
        assertTrue(payload.get("nodes").isJsonArray(), payload.toString());
        assertTrue(payload.get("inheritedGroups").isJsonArray(), payload.toString());
        assertTrue(payload.get("nodeCount").getAsInt() >= payload.getAsJsonArray("nodes").size(), payload.toString());
    }

    @Test
    @Order(29)
    void toolLuckPermsTracksList() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.tracks.list", new JsonObject(), 182);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.get("tracks").isJsonArray(), payload.toString());
        assertEquals(payload.getAsJsonArray("tracks").size(), payload.get("count").getAsInt(), payload.toString());
    }

    @Test
    @Order(30)
    void toolLuckPermsTrackGetMissingTrack() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.track.get", jsonObj("name", "missing-track-it"), 183);
        assertTrue(res.get("isError").getAsBoolean(), res.toString());
        assertEquals("Track not found", payloadJson(res).get("error").getAsString(), res.toString());
    }

    @Test
    @Order(31)
    void toolLuckPermsUserPermissionCheck() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.permission.check",
                args("uuid", "00000000-0000-0000-0000-000000000000", "permission", "edenmcp.it.check"), 184);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("edenmcp.it.check", payload.get("permission").getAsString(), payload.toString());
        assertTrue(Set.of("TRUE", "FALSE", "UNDEFINED").contains(payload.get("value").getAsString()), payload.toString());
        assertTrue(payload.get("contexts").isJsonObject(), payload.toString());
    }

    @Test
    @Order(32)
    void toolLuckPermsUserGroups() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.groups",
                jsonObj("uuid", "00000000-0000-0000-0000-000000000000"), 185);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.get("groups").isJsonArray(), payload.toString());
        assertTrue(payload.get("directGroups").isJsonArray(), payload.toString());
        assertEquals(payload.getAsJsonArray("groups").size(), payload.get("count").getAsInt(), payload.toString());
    }

    @Test
    @Order(33)
    void toolLuckPermsAuditRead() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.audit.read", args("limit", 10), 186);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.get("actions").isJsonArray(), payload.toString());
        assertTrue(payload.getAsJsonArray("actions").size() <= 10, payload.toString());
        assertEquals(payload.getAsJsonArray("actions").size(), payload.get("count").getAsInt(), payload.toString());
        for (JsonElement e : payload.getAsJsonArray("actions")) {
            JsonObject entry = e.getAsJsonObject();
            assertTrue(entry.has("timestamp"), entry.toString());
            assertTrue(entry.has("source"), entry.toString());
            assertTrue(entry.has("target"), entry.toString());
            assertTrue(entry.has("description"), entry.toString());
        }
    }

    @Test
    @Order(34)
    void toolLuckPermsUserPermissionSet() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.permission.set",
                args("uuid", "00000000-0000-0000-0000-000000000000", "permission", "edenmcp.it.check", "value", true),
                197);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("edenmcp.it.check", payload.get("permission").getAsString(), payload.toString());
        assertTrue(payload.get("value").getAsBoolean(), payload.toString());
    }

    @Test
    @Order(35)
    void toolLuckPermsGroupCreate() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.group.create", jsonObj("name", "it-perm-group"), 198);
        if (res.get("isError").getAsBoolean()) {
            // Integration server state can persist between test runs; tolerate an existing group.
            assertEquals("Group already exists", payloadJson(res).get("error").getAsString(), res.toString());
        } else {
            assertEquals("it-perm-group", payloadJson(res).get("name").getAsString(), res.toString());
        }

        JsonObject duplicate = toolCall("edenmcp.luckperms.group.create", jsonObj("name", "it-perm-group"), 199);
        assertTrue(duplicate.get("isError").getAsBoolean(), duplicate.toString());
        assertEquals("Group already exists", payloadJson(duplicate).get("error").getAsString(), duplicate.toString());
    }

    @Test
    @Order(36)
    void toolLuckPermsGroupPermissionSet() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.group.permission.set",
                args("name", "it-perm-group", "permission", "edenmcp.it.group", "value", true), 200);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        assertEquals("edenmcp.it.group", payloadJson(res).get("permission").getAsString(), res.toString());
    }

    @Test
    @Order(37)
    void toolLuckPermsUserGroupAdd() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.group.add",
                args("uuid", "00000000-0000-0000-0000-000000000000", "group", "it-perm-group"), 201);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        assertEquals("it-perm-group", payloadJson(res).get("group").getAsString(), res.toString());
    }

    @Test
    @Order(38)
    void toolLuckPermsUserGroupsAfterAdd() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.groups",
                jsonObj("uuid", "00000000-0000-0000-0000-000000000000"), 202);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.getAsJsonArray("directGroups").toString().contains("it-perm-group"), payload.toString());
    }

    @Test
    @Order(39)
    void toolLuckPermsUserGroupRemove() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.user.group.remove",
                args("uuid", "00000000-0000-0000-0000-000000000000", "group", "it-perm-group"), 203);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        assertEquals(1, payloadJson(res).get("removed").getAsInt(), res.toString());
    }

    @Test
    @Order(40)
    void toolLuckPermsGroupMetaSet() throws Exception {
        JsonObject res = toolCall("edenmcp.luckperms.group.meta.set",
                args("name", "it-perm-group", "key", "it-key", "value", "it-value"), 204);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("META", payload.get("type").getAsString(), payload.toString());
        assertEquals("it-value", payload.get("value").getAsString(), payload.toString());
    }

    @Test
    @Order(41)
    void toolLuckPermsUserTrackMissingTrack() throws Exception {
        JsonObject promote = toolCall("edenmcp.luckperms.user.track.promote",
                args("uuid", "00000000-0000-0000-0000-000000000000", "track", "missing-track-it"), 205);
        assertTrue(promote.get("isError").getAsBoolean(), promote.toString());
        assertEquals("Track not found", payloadJson(promote).get("error").getAsString(), promote.toString());

        JsonObject demote = toolCall("edenmcp.luckperms.user.track.demote",
                args("uuid", "00000000-0000-0000-0000-000000000000", "track", "missing-track-it"), 206);
        assertTrue(demote.get("isError").getAsBoolean(), demote.toString());
        assertEquals("Track not found", payloadJson(demote).get("error").getAsString(), demote.toString());
    }

    @Test
    @Order(20)
    void toolChatSend() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("message", "integration-test");
        JsonObject res = toolCall("edenmcp.chat.send", args, 200);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("broadcast", payload.get("sent").getAsString(), payload.toString());
        assertEquals("integration-test", payload.get("message").getAsString(), payload.toString());
    }

    @Test
    @Order(21)
    void toolCommandDispatch() throws Exception {
        JsonObject res = toolCall("edenmcp.command.dispatch", jsonObj("command", "say edenmcp-integration"), 210);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("dispatched"), payload.toString());
        assertEquals("UNSAFE_ACTION", payload.get("classification").getAsString(), payload.toString());
        assertEquals("say edenmcp-integration", payload.get("command").getAsString(), payload.toString());

        JsonObject chained = toolCall("edenmcp.command.dispatch", jsonObj("command", "say hi; op Player"), 211);
        assertTrue(chained.get("isError").getAsBoolean(), "chained commands must be rejected");
    }

    @Test
    @Order(50)
    void keyRotationInvalidatesSessionsAndIssuesNewKey() throws Exception {
        String oldKey = accessKey;
        String oldSession = sessionId;
        assertNotNull(oldSession, "A session should have been established by initialize");

        OutputStream os = serverProcess.getOutputStream();
        os.write("mcp key rotate\n".getBytes(StandardCharsets.UTF_8));
        os.flush();

        Path keyFile = serverDir.resolve("plugins/EdenMcp/.key");
        String newKey = oldKey;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && newKey.equals(oldKey)) {
            Thread.sleep(200);
            newKey = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        }
        assertNotEquals(oldKey, newKey, "Key file should change after rotation");
        assertTrue(newKey.matches("[0-9a-f]{64}"));
        accessKey = newKey;

        // The session created with the old key must no longer work.
        HttpResponse<String> stale = sendRaw("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/list\"}",
                Map.of("Authorization", "Bearer " + newKey, "Mcp-Session-Id", oldSession));
        JsonObject staleBody = JsonParser.parseString(stale.body()).getAsJsonObject();
        assertTrue(staleBody.has("error"), "old session must be invalidated after rotation: " + staleBody);

        // The old key remains accepted during the in-memory grace window.
        HttpResponse<String> grace = sendRaw("/mcp", pingBody(51),
                Map.of("Authorization", "Bearer " + oldKey));
        assertEquals(200, grace.statusCode(), "old key should still work during rotation grace");

        // A fresh session created with the new key works normally.
        JsonObject init = rpc("initialize", jsonObj("protocolVersion", "2024-11-05"), 52);
        assertNotNull(init.getAsJsonObject("result"), init.toString());
        JsonObject tools = rpc("tools/list", new JsonObject(), 53);
        assertFalse(tools.has("error"), tools.toString());
    }

    @Test
    @Order(100)
    void authFailuresAreThrottled() throws Exception {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("Authorization", "Bearer " + "1".repeat(64));
        for (int i = 0; i < 10; i++) {
            assertEquals(401, sendRaw("/mcp", pingBody(300 + i), bad).statusCode());
        }
        assertEquals(429, sendRaw("/mcp", pingBody(400), Map.of("Authorization", "Bearer " + accessKey)).statusCode());
    }

    private void setUnsafeModeViaConsole() throws Exception {
        String mode = "unsafe";
        OutputStream os = serverProcess.getOutputStream();
        os.write(("mcp mode set " + mode + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                JsonObject res = toolCall("edenmcp.mode.get", new JsonObject(), 999);
                if (mode.equals(payloadJson(res).get("mode").getAsString())) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(500);
        }
        fail("Mode was not changed to " + mode + " via console");
    }

    private void waitForMcpReady(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                JsonObject init = rpc("initialize", jsonObj("protocolVersion", "2024-11-05"), 9999);
                if (init.has("result")) return;
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        if (last != null) throw last;
        fail("MCP not ready within timeout");
    }

    private JsonObject rpc(String method, JsonObject params, int id) throws Exception {
        JsonObject resp = rpcRaw(accessKey, method, params, id, true);
        if (resp.has("error")) fail("RPC error for " + method + ": " + resp);
        return resp;
    }

    private JsonObject toolCall(String name, JsonObject args, int id) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", args);
        JsonObject resp = rpc("tools/call", params, id);
        return resp.getAsJsonObject("result");
    }

    private JsonObject payloadJson(JsonObject toolCallResult) {
        String text = toolCallResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private boolean hasTool(JsonArray tools, String name) {
        for (JsonElement e : tools) {
            if (name.equals(e.getAsJsonObject().get("name").getAsString())) return true;
        }
        return false;
    }

    private boolean hasResource(JsonArray resources, String uri) {
        for (JsonElement e : resources) {
            if (uri.equals(e.getAsJsonObject().get("uri").getAsString())) return true;
        }
        return false;
    }

    private String pingBody(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}";
    }

    private JsonObject rpcRaw(String key, String method, JsonObject params, int id, boolean withAuth) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params == null ? new JsonObject() : params);
        Map<String, String> headers = new LinkedHashMap<>();
        if (withAuth && key != null) headers.put("Authorization", "Bearer " + key);
        if (sessionId != null && !sessionId.isBlank()) headers.put("Mcp-Session-Id", sessionId);
        HttpResponse<String> resp = sendRaw("/mcp", GSON.toJson(req), headers);
        if ("initialize".equals(method) && resp.statusCode() == 200) {
            String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (sid != null && !sid.isBlank()) sessionId = sid;
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** Raw socket request: java.net.http.HttpClient does not allow overriding the Host header. */
    private int rawHttpStatus(String hostHeader, String originHeader) throws Exception {
        String body = pingBody(500);
        StringBuilder req = new StringBuilder()
                .append("POST /mcp HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n");
        if (originHeader != null) req.append("Origin: ").append(originHeader).append("\r\n");
        req.append("Authorization: Bearer ").append(accessKey).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                .append("Connection: close\r\n\r\n")
                .append(body);
        try (Socket socket = new Socket("127.0.0.1", mcpPort)) {
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = br.readLine();
            assertNotNull(statusLine, "No HTTP response");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private HttpResponse<String> sendRaw(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        headers.forEach(b::header);
        b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonObject jsonObj(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    /** Builds a JSON object from alternating string keys and primitive values. */
    private static JsonObject args(Object... keyValues) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof Boolean b) o.addProperty(key, b);
            else if (value instanceof Number n) o.addProperty(key, n);
            else o.addProperty(key, String.valueOf(value));
        }
        return o;
    }

    private Set<String> profileNames(int id) throws Exception {
        Set<String> names = new HashSet<>();
        JsonObject result = toolCall("edenmcp.spark.data.list", args("kind", "profile", "limit", 200), id);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        for (JsonElement e : payloadJson(result).getAsJsonArray("files")) {
            names.add(e.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private String waitForNewProfile(Set<String> before) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int id = 626;
        while (System.nanoTime() < deadline) {
            Set<String> current = profileNames(id++);
            current.removeAll(before);
            if (!current.isEmpty()) return current.iterator().next();
            Thread.sleep(500);
        }
        fail("No new Spark profile was saved locally");
        return null;
    }

    private static void waitForLocalPort(int port, Duration timeout) throws Exception {
        String host = "127.0.0.1";
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket(host, port)) {
                return;
            } catch (Exception ignoredException) {
            }
            Thread.sleep(1000);
        }
        fail("Port not open: " + host + ":" + port);
    }

    private void waitForKey(Duration timeout) throws Exception {
        Path key = serverDir.resolve("plugins/EdenMcp/.key");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(key) && Files.size(key) > 0) return;
            Thread.sleep(500);
        }
        fail("Access key file not generated: " + key);
    }

    private static List<String> buildServerCommand(String javaBin, Path paperJarPath, Path serverDir) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xms1G");
        cmd.add("-Xmx1G");
        cmd.add("-Dcom.mojang.eula.agree=true");

        Path librariesDir = serverDir.resolve("libraries");
        if (Files.isDirectory(librariesDir)) {
            String cp;
            try (Stream<Path> walk = Files.walk(librariesDir)) {
                String libs = walk
                        .filter(p -> p.toString().endsWith(".jar"))
                        .map(p -> p.toAbsolutePath().toString())
                        .sorted()
                        .collect(Collectors.joining(File.pathSeparator));
                cp = paperJarPath.toAbsolutePath() + File.pathSeparator + libs;
            }
            cmd.add("-cp");
            cmd.add(cp);
            String mainClass = readMainClass(paperJarPath);
            if (mainClass == null || mainClass.isBlank()) mainClass = "org.bukkit.craftbukkit.Main";
            cmd.add(mainClass);
        } else {
            cmd.add("-jar");
            cmd.add(paperJarPath.toAbsolutePath().toString());
        }
        cmd.add("nogui");
        return cmd;
    }

    private static String readMainClass(Path jarPath) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath.toFile())) {
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf == null) return null;
            return mf.getMainAttributes().getValue("Main-Class");
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteIfExists(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
