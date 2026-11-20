package at.haha007.edenMcp.hooks.spark;

import at.haha007.edenMcp.core.capability.CapabilityLevel;
import at.haha007.edenMcp.core.capability.SideEffects;
import at.haha007.edenMcp.core.mode.OperatingMode;
import at.haha007.edenMcp.core.tool.ToolContext;
import at.haha007.edenMcp.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkProfilerToolTest {
    private final SparkProfilerTool tool = new SparkProfilerTool(null, null);

    @Test
    void toolIsAModerateObservableAction() {
        assertEquals("eden.spark.profiler.control", tool.name());
        assertEquals(CapabilityLevel.MODERATE_ACTION, tool.level());
        assertEquals(SideEffects.OBSERVABLE, tool.sideEffects());
    }

    @Test
    void startAlwaysSavesLocally() {
        assertEquals("spark profiler start --save-to-file", tool.buildCommand("start", Map.of()));
    }

    @Test
    void startBuildsCpuOptionsInStableOrder() {
        String command = tool.buildCommand("start", args(
                "mode", "cpu",
                "intervalMillis", 5,
                "onlyTicksOverMillis", 50,
                "ignoreSleeping", true,
                "forceJavaSampler", true,
                "timeoutSeconds", 30,
                "thread", "Server thread",
                "threadRegex", true,
                "combineAll", true,
                "comment", "test run"
        ));

        assertEquals("spark profiler start --interval 5 --only-ticks-over 50 --ignore-sleeping"
                + " --force-java-sampler --timeout 30 --thread \"Server thread\" --regex"
                + " --combine-all --comment \"test run\" --save-to-file", command);
    }

    @Test
    void startBuildsAllocationOptions() {
        String command = tool.buildCommand("start", args(
                "mode", "alloc",
                "allocationIntervalBytes", 2048,
                "allocLiveOnly", true,
                "timeoutSeconds", 20
        ));

        assertEquals("spark profiler start --alloc --interval 2048 --alloc-live-only"
                + " --timeout 20 --save-to-file", command);
    }

    @Test
    void startBuildsLockOptions() {
        String command = tool.buildCommand("start", args(
                "mode", "lock",
                "intervalMillis", 2,
                "timeoutSeconds", 20
        ));

        assertEquals("spark profiler start --lock --interval 2 --timeout 20 --save-to-file", command);
    }

    @Test
    void stopSavesWithOptionalComment() {
        assertEquals("spark profiler stop --save-to-file", tool.buildCommand("stop", Map.of()));
        assertEquals("spark profiler stop --save-to-file --comment \"note\"",
                tool.buildCommand("stop", args("comment", "note")));
    }

    @Test
    void cancelIgnoresStartOptions() {
        assertEquals("spark profiler cancel", tool.buildCommand("cancel", args(
                "mode", "alloc",
                "timeoutSeconds", 30,
                "comment", "ignored",
                "combineAll", true
        )));
    }

    @Test
    void commandBuilderRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args("mode", "upload")));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args("timeoutSeconds", 10)));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args("intervalMillis", 5001)));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args("thread", "bad{thread}")));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args("comment", "$(id)")));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("stop", args("comment", "x$(id)")));
    }

    @Test
    void executeReturnsErrorsBeforeDispatchingACommand() {
        ToolResult missingAction = tool.execute(context(Map.of()));
        assertFalse(missingAction.success());
        assertTrue(missingAction.error().contains("action"));

        ToolResult badMode = tool.execute(context(args("action", "start", "mode", "upload")));
        assertFalse(badMode.success());
        assertTrue(badMode.error().contains("mode"));

        ToolResult badComment = tool.execute(context(args("action", "stop", "comment", "$(id)")));
        assertFalse(badComment.success());
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
