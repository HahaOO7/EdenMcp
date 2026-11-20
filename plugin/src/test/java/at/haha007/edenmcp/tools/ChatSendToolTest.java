package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.core.config.EdenConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ChatSendToolTest {
    private static ChatSendTool tool(long minIntervalMs) {
        EdenConfig.ToolConfig config = new EdenConfig.ToolConfig(
                minIntervalMs, 5, 10, 100, 500, 1024, 200, 16 * 1024 * 1024,
                4 * 1024 * 1024, 1024 * 1024, 500, 10, 200);
        return new ChatSendTool(null, Logger.getLogger("ChatSendToolTest"), false, config);
    }

    @Test
    void rateLimitIsPerPrincipal() {
        ChatSendTool tool = tool(1000);
        long start = 1_000_000L;
        long interval = TimeUnit.MILLISECONDS.toNanos(1000);

        assertTrue(tool.acquire("alice", start));
        assertFalse(tool.acquire("alice", start + interval / 2));
        // A different caller must not be affected by alice's bucket.
        assertTrue(tool.acquire("bob", start + interval / 2));
        assertTrue(tool.acquire("alice", start + interval));
    }

    @Test
    void zeroIntervalNeverLimits() {
        ChatSendTool tool = tool(0);
        assertTrue(tool.acquire("alice", 1000L));
        assertTrue(tool.acquire("alice", 1000L));
    }

    @Test
    void logValuesAreSanitized() {
        assertEquals("hello??world", ChatSendTool.sanitizeLog("hello\r\nworld"));
        assertEquals("a?b?c?", ChatSendTool.sanitizeLog("a\u0000b\u007fc\u0085"));
        assertEquals("plain", ChatSendTool.sanitizeLog("plain"));
    }
}
