package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkDiagnosticsToolTest {
    private final SparkDiagnosticsTool tool = new SparkDiagnosticsTool(null, null);

    @Test
    void toolIsAModerateObservableAction() {
        assertEquals("eden.spark.diagnostics.run", tool.name());
        assertEquals(CapabilityLevel.MODERATE_ACTION, tool.level());
        assertEquals(SideEffects.OBSERVABLE, tool.sideEffects());
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

    private static ToolContext context(Map<String, Object> arguments) {
        return new ToolContext(OperatingMode.MODERATE, "unit-test", arguments);
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return args;
    }
}
