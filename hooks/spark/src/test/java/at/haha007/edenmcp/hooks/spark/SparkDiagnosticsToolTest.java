package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkDiagnosticsToolTest {
    private final SparkDiagnosticsTool tool = recordingTool(new ArrayList<>());

    @Test
    void toolIsAModerateObservableAction() {
        assertEquals("edenmcp.spark.diagnostics.run", tool.name());
        assertEquals(CapabilityLevel.MODERATE_ACTION, tool.level());
        assertEquals(SideEffects.OBSERVABLE, tool.sideEffects());
    }

    @Test
    void descriptionMentionsHeapDumpModeAndStorage() {
        String description = tool.description();
        assertTrue(description.contains("UNSAFE"), description);
        assertTrue(description.contains("local disk"), description);
    }

    @Test
    void tickMonitorOptionsAreAllowlistedAndOrdered() {
        assertEquals("spark tickmonitor", tool.buildCommand("tickmonitor", Map.of()));
        assertEquals("spark tickmonitor --threshold 20",
                tool.buildCommand("tickmonitor", args("thresholdPercent", 20)));
        assertEquals("spark tickmonitor --threshold-tick 100 --without-gc",
                tool.buildCommand("tickmonitor", args(
                        "thresholdTickMillis", 100,
                        "thresholdPercent", 20,
                        "withoutGc", true
                )));
    }

    @Test
    void heapDumpOptionsAreAllowlisted() {
        assertEquals("spark heapdump --compress gzip --include-non-live --run-gc-before",
                tool.buildCommand("heapdump", args(
                        "compress", "gzip",
                        "includeNonLive", true,
                        "runGcBefore", true
                )));
    }

    @Test
    void simpleDiagnosticsTakeNoOptions() {
        assertEquals("spark gcmonitor", tool.buildCommand("gcmonitor", Map.of()));
        assertEquals("spark tps", tool.buildCommand("tps", Map.of()));
        assertEquals("spark gc", tool.buildCommand("gc", Map.of()));
    }

    @Test
    void commandBuilderRejectsUnsupportedCommandsAndValues() {
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("upload", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> tool.buildCommand("heapdump", args("compress", "zip")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.buildCommand("tickmonitor", args("thresholdPercent", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.buildCommand("tickmonitor", args("thresholdTickMillis", 60001)));
    }

    @Test
    void executeReturnsErrorsBeforeDispatchingACommand() {
        ToolResult missingCommand = tool.execute(context(Map.of()));
        assertFalse(missingCommand.success());
        assertTrue(Objects.requireNonNull(missingCommand.error()).contains("command"));

        ToolResult badCommand = tool.execute(context(args("command", "upload")));
        assertFalse(badCommand.success());
    }

    @Test
    void heapDumpRequiresUnsafeMode() {
        for (OperatingMode mode : List.of(OperatingMode.SAFE, OperatingMode.MODERATE)) {
            List<String> dispatched = new ArrayList<>();
            ToolResult result = recordingTool(dispatched)
                    .execute(context(mode, args("command", "heapdump")));
            assertFalse(result.success());
            assertEquals("heapdump requires UNSAFE mode", result.error());
            assertTrue(dispatched.isEmpty(), "heapdump must not be dispatched in " + mode);
        }
    }

    @Test
    void repeatedHeapDumpsAreRateLimitedInUnsafeMode() {
        List<String> dispatched = new ArrayList<>();
        SparkDiagnosticsTool unsafeTool = recordingTool(dispatched);
        Map<String, Object> arguments = args("command", "heapdump");

        ToolResult first = unsafeTool.execute(context(OperatingMode.UNSAFE, arguments));
        assertTrue(first.success(), String.valueOf(first.error()));
        assertEquals(List.of("spark heapdump"), dispatched);

        ToolResult second = unsafeTool.execute(context(OperatingMode.UNSAFE, arguments));
        assertFalse(second.success());
        assertEquals("heapdump rate limited; try again later", second.error());
        assertEquals(List.of("spark heapdump"), dispatched, "rate limited dump must not be dispatched");
    }

    @Test
    void heapDumpResultCarriesDangerMetadata() {
        ToolResult result = recordingTool(new ArrayList<>())
                .execute(context(OperatingMode.UNSAFE, args("command", "heapdump")));
        Map<String, Object> out = content(result);
        assertEquals("DANGEROUS", out.get("sideEffects"));
        assertEquals("UNSAFE", out.get("requiresMode"));
        String storage = String.valueOf(out.get("storage"));
        assertTrue(storage.contains("local") && storage.contains("disk"), storage);
        assertTrue(storage.contains("large"), storage);
    }

    @Test
    void nonHeapDumpCommandsAreNeitherGatedNorRateLimited() {
        List<String> dispatched = new ArrayList<>();
        SparkDiagnosticsTool safeTool = recordingTool(dispatched);
        Map<String, Object> arguments = args("command", "tps");

        for (int i = 0; i < 2; i++) {
            ToolResult result = safeTool.execute(context(OperatingMode.SAFE, arguments));
            Map<String, Object> out = content(result);
            assertEquals("spark tps", out.get("command"));
            assertFalse(out.containsKey("sideEffects"));
            assertFalse(out.containsKey("requiresMode"));
            assertFalse(out.containsKey("storage"));
            assertFalse(out.containsKey("recentHeapDumps"));
        }
        assertEquals(List.of("spark tps", "spark tps"), dispatched);
    }

    @SuppressWarnings("unused")
    private static SparkDiagnosticsTool recordingTool(List<String> dispatched) {
        return new SparkDiagnosticsTool((command, action, context) -> {
            dispatched.add(command);
            return new SparkCommandRunner.Result(command, true, List.of("ok"), false);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(ToolResult result) {
        assertTrue(result.success(), String.valueOf(result.error()));
        return (Map<String, Object>) Objects.requireNonNull(result.content());
    }

    private static ToolContext context(Map<String, Object> arguments) {
        return context(OperatingMode.MODERATE, arguments);
    }

    private static ToolContext context(OperatingMode mode, Map<String, Object> arguments) {
        return new ToolContext(mode, "unit-test", arguments);
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return args;
    }
}
