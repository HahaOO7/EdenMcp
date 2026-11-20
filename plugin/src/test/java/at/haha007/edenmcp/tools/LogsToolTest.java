package at.haha007.edenmcp.tools;

import at.haha007.edenmcp.api.OperatingMode;
import at.haha007.edenmcp.api.ToolContext;
import at.haha007.edenmcp.api.ToolResult;
import at.haha007.edenmcp.core.config.EdenConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LogsToolTest {
    @TempDir Path tempDir;

    private ToolContext context(Object... keyValues) {
        Map<String, Object> args = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new ToolContext(OperatingMode.UNSAFE, "test", args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> content(ToolResult result) {
        assertTrue(result.success(), () -> "Unexpected error: " + result.error());
        return (Map<String, Object>) result.content();
    }

    @Test
    void readsPlainLogByDefault() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "alpha\nbeta\ngamma\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context()));
        assertEquals("latest.log", out.get("file"));
        assertEquals("plain", out.get("compression"));
        assertEquals(3, out.get("count"));
        assertEquals(java.util.List.of("alpha", "beta", "gamma"), out.get("lines"));
        assertEquals(false, out.get("truncated"));
    }

    @Test
    void readsGzipLogWithFileArgument() throws Exception {
        Path gz = tempDir.resolve("latest.log.1.gz");
        try (GZIPOutputStream os = new GZIPOutputStream(Files.newOutputStream(gz))) {
            os.write("one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));
        }
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context("file", "latest.log.1.gz")));
        assertEquals("gzip", out.get("compression"));
        assertEquals(3, out.get("count"));
        assertEquals(java.util.List.of("one", "two", "three"), out.get("lines"));
    }

    @Test
    void readsFirstZipEntry() throws Exception {
        Path zip = tempDir.resolve("archive.zip");
        try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(zip))) {
            os.putNextEntry(new ZipEntry("server.log"));
            os.write("zip-line-1\nzip-line-2\n".getBytes(StandardCharsets.UTF_8));
            os.closeEntry();
        }
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context("file", "archive.zip")));
        assertEquals("zip", out.get("compression"));
        assertEquals(java.util.List.of("zip-line-1", "zip-line-2"), out.get("lines"));
    }

    @Test
    void rejectsTraversalAndUnexpectedTypes() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "ok\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        assertFalse(tool.execute(context("file", "../secret.txt")).success());
        assertFalse(tool.execute(context("file", 42)).success());
        assertFalse(tool.execute(context("limit", "abc")).success());
    }

    @Test
    void decompressionIsCapped() throws Exception {
        Path gz = tempDir.resolve("huge.gz");
        String huge = "x".repeat(200_000) + "\n";
        try (GZIPOutputStream os = new GZIPOutputStream(Files.newOutputStream(gz))) {
            os.write(huge.getBytes(StandardCharsets.UTF_8));
        }
        EdenConfig.ToolConfig bounded = new EdenConfig.ToolConfig(
                250, 5, 10, 100, 500, 1024, 200, 1024,
                1024 * 1024, 1024 * 1024, 500, 10, 200);
        LogsReadTool tool = new LogsReadTool(tempDir, false, bounded);

        Map<String, Object> out = content(tool.execute(context("file", "huge.gz")));
        assertEquals(true, out.get("truncated"));
        assertEquals(1, out.get("count"));
    }

    @Test
    void listsAvailableLogsWithMetadata() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "log\n");
        Files.writeString(tempDir.resolve("old.log.gz"), "old\n");
        Files.writeString(tempDir.resolve("notes.txt"), "skip\n");
        LogsListTool tool = new LogsListTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context()));
        assertEquals(2, out.get("total"));
        assertEquals(2, out.get("count"));
        assertEquals(false, out.get("hasMore"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) out.get("files");
        assertTrue(files.stream().anyMatch(f -> "latest.log".equals(f.get("name")) && "plain".equals(f.get("compression"))));
        assertTrue(files.stream().anyMatch(f -> "old.log.gz".equals(f.get("name")) && "gzip".equals(f.get("compression"))));
        assertTrue(files.stream().noneMatch(f -> "notes.txt".equals(f.get("name"))));
    }

    @Test
    void logFileAccessRejectsInvalidNames() {
        LogFileAccess access = new LogFileAccess(tempDir, 1024);
        assertThrows(IOException.class, () -> access.resolve("../escape"));
        assertThrows(IOException.class, () -> access.resolve("sub/file.log"));
        assertThrows(IOException.class, () -> access.resolve(".."));
    }
}
