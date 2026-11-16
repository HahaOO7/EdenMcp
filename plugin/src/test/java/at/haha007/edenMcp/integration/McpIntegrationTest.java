package at.haha007.edenMcp.integration;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class McpIntegrationTest {
    private static final Gson GSON = new Gson();

    private Process serverProcess;
    private Thread outputDrainer;
    private Path serverDir;
    private String baseUrl;
    private String accessKey;
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
            fail("edenmcp.it.paperJar not set and default Paper jar not found. Run runServer once or pass -Pintegration.paperJar=/path/to/paper.jar");
        }
        Path paperJarPath = Path.of(paperJar);
        assertTrue(Files.exists(paperJarPath), "Paper jar not found: " + paperJarPath);

        // Clean dynamic state from previous runs but keep prepared files
        deleteIfExists(serverDir.resolve("plugins/EdenMcp/.key"));
        deleteIfExists(serverDir.resolve("plugins/EdenMcp/mcp-keystore.p12"));
        deleteRecursively(serverDir.resolve("world"));
        deleteRecursively(serverDir.resolve("world_nether"));
        deleteRecursively(serverDir.resolve("world_the_end"));
        deleteIfExists(serverDir.resolve("usercache.json"));
        deleteIfExists(serverDir.resolve("banned-ips.json"));
        deleteIfExists(serverDir.resolve("banned-players.json"));
        deleteIfExists(serverDir.resolve("ops.json"));
        deleteIfExists(serverDir.resolve("whitelist.json"));

        List<String> cmd = buildServerCommand(javaBin, paperJarPath, serverDir);
        System.out.println("[IT] Starting server: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(serverDir.toFile());
        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        outputDrainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[SERVER] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "it-server-log");
        outputDrainer.setDaemon(true);
        outputDrainer.start();

        boolean isTls = "true".equalsIgnoreCase(tls);
        baseUrl = (isTls ? "https" : "http") + "://127.0.0.1:" + port;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        waitForPort("127.0.0.1", Integer.parseInt(port), Duration.ofMinutes(3));
        waitForKey(Duration.ofMinutes(2));
        accessKey = Files.readString(serverDir.resolve("plugins/EdenMcp/.key"), StandardCharsets.UTF_8).trim();
        assertFalse(accessKey.isBlank(), "Access key is blank");

        // Wait until MCP responds
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
        if (outputDrainer != null) {
            outputDrainer.join(2000);
        }
    }

    @Test
    @Order(1)
    void initializeAndAuth() throws Exception {
        // unauthenticated should fail
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
        assertTrue(hasTool(tools, "eden.server.status"));
        assertTrue(hasTool(tools, "eden.plugins.list"));
        assertTrue(hasTool(tools, "eden.players.list"));
        assertTrue(hasTool(tools, "eden.logs.read"));
        assertTrue(hasTool(tools, "eden.config.get"));
        assertTrue(hasTool(tools, "eden.mode.get"));
        assertTrue(hasTool(tools, "eden.mode.set"));
        assertTrue(hasTool(tools, "eden.hooks.list"));
        assertTrue(hasTool(tools, "eden.chat.send"));
        assertTrue(hasTool(tools, "eden.command.dispatch"));
        assertTrue(hasTool(tools, "eden.luckperms.groups.list"), "LuckPerms hook should be available: " + tools);
        assertTrue(hasTool(tools, "eden.luckperms.user.lookup"), "LuckPerms hook should be available: " + tools);
    }

    @Test
    @Order(10)
    void toolModeGet() throws Exception {
        JsonObject res = toolCall("eden.mode.get", new JsonObject(), 100);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("unsafe", payload.get("mode").getAsString(), payload.toString());
    }

    @Test
    @Order(11)
    void toolModeSet() throws Exception {
        JsonObject toModerate = toolCall("eden.mode.set", jsonObj("mode", "moderate"), 110);
        assertFalse(toModerate.get("isError").getAsBoolean(), toModerate.toString());
        assertEquals("moderate", payloadJson(toModerate).get("mode").getAsString());
        assertEquals("moderate", payloadJson(toolCall("eden.mode.get", new JsonObject(), 111)).get("mode").getAsString());

        JsonObject toSafe = toolCall("eden.mode.set", jsonObj("mode", "safe"), 112);
        assertFalse(toSafe.get("isError").getAsBoolean(), toSafe.toString());
        assertEquals("safe", payloadJson(toSafe).get("mode").getAsString());

        // dispatch is visible in safe mode but defaults to unsafe classification and should be denied
        JsonObject denied = toolCall("eden.command.dispatch", jsonObj("command", "say hello"), 113);
        assertTrue(denied.get("isError").getAsBoolean(), "Expected denial in safe mode: " + denied);

        // recover to unsafe via classified-safe command dispatch (mode.set tool is not callable in safe mode)
        JsonObject recover = toolCall("eden.command.dispatch", jsonObj("command", "mcp mode set unsafe"), 114);
        assertFalse(recover.get("isError").getAsBoolean(), recover.toString());
        assertEquals("unsafe", payloadJson(toolCall("eden.mode.get", new JsonObject(), 115)).get("mode").getAsString());
    }

    @Test
    @Order(12)
    void toolServerStatus() throws Exception {
        JsonObject res = toolCall("eden.server.status", new JsonObject(), 120);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("version"), payload.toString());
        assertTrue(payload.has("bukkitVersion"), payload.toString());
        assertTrue(payload.has("onlinePlayers"), payload.toString());
        assertTrue(payload.has("worlds"), payload.toString());
    }

    @Test
    @Order(13)
    void toolPluginsList() throws Exception {
        JsonObject res = toolCall("eden.plugins.list", new JsonObject(), 130);
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
        JsonObject res = toolCall("eden.players.list", new JsonObject(), 140);
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
        JsonObject res = toolCall("eden.logs.read", args, 150);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("lines"), payload.toString());
        assertTrue(payload.has("count"), payload.toString());
        assertTrue(payload.get("count").getAsInt() >= 0, payload.toString());
    }

    @Test
    @Order(16)
    void toolConfigGet() throws Exception {
        JsonObject res = toolCall("eden.config.get", new JsonObject(), 160);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("mcp"), payload.toString());
        assertTrue(payload.has("hooks"), payload.toString());
    }

    @Test
    @Order(17)
    void toolHooksList() throws Exception {
        JsonObject res = toolCall("eden.hooks.list", new JsonObject(), 170);
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
        JsonObject res = toolCall("eden.luckperms.groups.list", new JsonObject(), 180);
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
        JsonObject res = toolCall("eden.luckperms.user.lookup", args, 190);
        assertNotNull(res, "Missing tool result");
        assertTrue(res.has("isError"), res.toString());
        // Tool may load/create or return not-found depending on LuckPerms/storage behavior; either is a valid exercised call.
        assertTrue(res.has("content"), res.toString());
    }

    @Test
    @Order(20)
    void toolChatSend() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("message", "integration-test");
        JsonObject res = toolCall("eden.chat.send", args, 200);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertEquals("broadcast", payload.get("sent").getAsString(), payload.toString());
        assertEquals("integration-test", payload.get("message").getAsString(), payload.toString());
    }

    @Test
    @Order(21)
    void toolCommandDispatch() throws Exception {
        JsonObject res = toolCall("eden.command.dispatch", jsonObj("command", "say edenmcp-integration"), 210);
        assertFalse(res.get("isError").getAsBoolean(), res.toString());
        JsonObject payload = payloadJson(res);
        assertTrue(payload.has("dispatched"), payload.toString());
        assertEquals("UNSAFE_ACTION", payload.get("classification").getAsString(), payload.toString());
        assertEquals("say edenmcp-integration", payload.get("command").getAsString(), payload.toString());
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
        if (resp.has("error")) {
            fail("RPC error for " + method + ": " + resp);
        }
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

    private JsonObject rpcRaw(String key, String method, JsonObject params, int id, boolean withAuth) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params == null ? new JsonObject() : params);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(req)));
        if (withAuth && key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static JsonObject jsonObj(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    private static void waitForPort(String host, int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket(host, port)) {
                return;
            } catch (Exception ignored) {
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
