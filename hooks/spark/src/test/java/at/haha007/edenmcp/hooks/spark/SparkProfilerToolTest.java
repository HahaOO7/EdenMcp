package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.*;

import at.haha007.edenmcp.api.CapabilityLevel;
import at.haha007.edenmcp.api.SideEffects;
import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkProfilerToolTest {
    private final SparkProfilerTool tool = new SparkProfilerTool((SparkCommandRunner) null, null);

    @Test
    void toolIsAModerateObservableAction() {
        assertEquals("edenmcp.spark.profiler.control", tool.name());
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
        assertThrows(IllegalArgumentException.class,
                () -> tool.buildCommand("start", args("timeoutSeconds", "30")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.buildCommand("start", args("ignoreSleeping", "true")));
    }

    @Test
    void catastrophicThreadRegexIsRejectedBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args(
                "thread", "(a+)+", "threadRegex", true)));
        assertThrows(IllegalArgumentException.class, () -> tool.buildCommand("start", args(
                "thread", "(a|aa)+", "threadRegex", true)));

        assertEquals("spark profiler start --thread \"Server.*\" --regex --save-to-file",
                tool.buildCommand("start", args("thread", "Server.*", "threadRegex", true)));
        // The same text is harmless when it is a literal thread name rather than a regex.
        assertEquals("spark profiler start --thread \"(a+)+\" --save-to-file",
                tool.buildCommand("start", args("thread", "(a+)+")));
    }

    @Test
    void executeReturnsErrorsBeforeDispatchingACommand() {
        ToolResult missingAction = tool.execute(context(Map.of()));
        assertFalse(missingAction.success());
        assertTrue(Objects.requireNonNull(missingAction.error()).contains("action"));

        ToolResult badMode = tool.execute(context(args("action", "start", "mode", "upload")));
        assertFalse(badMode.success());
        assertTrue(Objects.requireNonNull(badMode.error()).contains("mode"));

        ToolResult badComment = tool.execute(context(args("action", "stop", "comment", "$(id)")));
        assertFalse(badComment.success());
    }

    @Test
    void repeatedStartsAreRateLimited() {
        List<String> dispatched = new ArrayList<>();
        SparkProfilerTool gated = recordingTool(dispatched);

        ToolResult first = gated.execute(context(args("action", "start")));
        assertTrue(first.success(), String.valueOf(first.error()));

        ToolResult second = gated.execute(context(args("action", "start")));
        assertFalse(second.success());
        assertTrue(Objects.requireNonNull(second.error()).contains("rate limited"), second.error());
        assertEquals(List.of("spark profiler start --save-to-file"), dispatched);
    }

    @Test
    void onlyOneProfilerSessionCanBeActive() throws Exception {
        List<String> dispatched = new ArrayList<>();
        SparkProfilerTool gated = recordingTool(dispatched);
        assertTrue(gated.execute(context(args("action", "start"))).success());

        // Bypass the interval so only the active-session guard can reject the second start.
        Field lastStart = SparkProfilerTool.class.getDeclaredField("lastStartNanos");
        lastStart.setAccessible(true);
        lastStart.set(gated, null);

        ToolResult second = gated.execute(context(args("action", "start")));
        assertFalse(second.success());
        assertTrue(Objects.requireNonNull(second.error()).contains("already active"), second.error());
        assertEquals(1, dispatched.size());

        assertTrue(gated.execute(context(args("action", "stop"))).success());
        assertTrue(gated.execute(context(args("action", "start"))).success(),
                "stop releases the active session");
        assertEquals(3, dispatched.size());
    }

    @Test
    void cancelReleasesActiveSession() throws Exception {
        List<String> dispatched = new ArrayList<>();
        SparkProfilerTool gated = recordingTool(dispatched);
        assertTrue(gated.execute(context(args("action", "start"))).success());
        assertTrue(gated.execute(context(args("action", "cancel"))).success());

        Field lastStart = SparkProfilerTool.class.getDeclaredField("lastStartNanos");
        lastStart.setAccessible(true);
        lastStart.set(gated, null);

        assertTrue(gated.execute(context(args("action", "start"))).success());
        assertEquals(3, dispatched.size());
    }

    @Test
    void resultNotesLocalOnlyStorageAndDiskUsage() {
        ToolResult result = recordingTool(new ArrayList<>()).execute(context(args("action", "start")));
        Map<String, Object> out = content(result);
        assertEquals("local-only", out.get("storage"));
        String note = String.valueOf(out.get("storageNote"));
        assertTrue(note.contains("local") && note.contains("disk"), note);
    }

    @SuppressWarnings("unused")
    private static SparkProfilerTool recordingTool(List<String> dispatched) {
        return new SparkProfilerTool((command, action, context) -> {
            dispatched.add(command);
            return new SparkCommandRunner.Result(command, true, List.of("ok"), false);
        }, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(ToolResult result) {
        assertTrue(result.success(), String.valueOf(result.error()));
        return (Map<String, Object>) Objects.requireNonNull(result.content());
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
