package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.util.ServerFileAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileListToolResultTest {
    @TempDir Path serverDir;

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(FileListTool tool, Object... keyValues) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        ToolResult result = tool.execute(new ToolContext(OperatingMode.SAFE, "test", args));
        assertTrue(result.success(), () -> "Unexpected error: " + result.error());
        return (Map<String, Object>) result.content();
    }

    @Test
    void reportsTotalAndHasMoreAlongsideExistingKeys() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.writeString(serverDir.resolve("file" + i + ".txt"), "x");
        }
        FileListTool tool = new FileListTool(new ServerFileAccess(serverDir), 2, 5);

        Map<String, Object> out = execute(tool, "path", "");
        assertEquals(".", out.get("path"));
        assertEquals(2, out.get("count"));
        assertEquals(5, out.get("total"));
        assertEquals(true, out.get("hasMore"));
        assertEquals(true, out.get("truncated"), "existing key must be preserved");
        assertEquals(false, out.get("recursive"));
        assertEquals(1, out.get("depth"));
    }

    @Test
    void reportsTotalAndHasMoreForFilteredListings() throws Exception {
        Files.writeString(serverDir.resolve("a.yml"), "a");
        Files.writeString(serverDir.resolve("b.txt"), "b");
        Files.writeString(serverDir.resolve("c.yml"), "c");
        FileListTool tool = new FileListTool(new ServerFileAccess(serverDir), 10, 5);

        Map<String, Object> out = execute(tool, "pattern", "*.yml");
        assertEquals(2, out.get("count"));
        assertEquals(2, out.get("total"));
        assertEquals(false, out.get("hasMore"));
        assertEquals(false, out.get("truncated"));
    }
}
