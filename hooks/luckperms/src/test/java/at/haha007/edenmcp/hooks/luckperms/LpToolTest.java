package at.haha007.edenmcp.hooks.luckperms;

import at.haha007.edenmcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LpToolTest {
    @Test
    void timeoutIsReportedAsCleanToolError() {
        ToolResult result = LpTool.errorResult(new LpSupport.LuckPermsTimeoutException("loadGroup", null));
        assertFalse(result.success());
        assertEquals("LuckPerms operation timed out", result.error());
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() {
        ToolResult result = LpTool.errorResult(new IllegalStateException("secret backend detail"));
        assertFalse(result.success());
        assertEquals("LuckPerms error", result.error());
    }

    @Test
    void invalidArgumentsKeepTheirMessage() {
        ToolResult result = LpTool.errorResult(new LpSupport.InvalidArgument("Provide username or uuid"));
        assertFalse(result.success());
        assertEquals("Provide username or uuid", result.error());
    }
}
