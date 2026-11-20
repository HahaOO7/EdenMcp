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
        assertEquals(false, out.get("truncated"));
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
        // JSON types are strict: numeric strings and string booleans are no longer coerced.
        assertFalse(tool.execute(context("limit", "5")).success());
        assertFalse(tool.execute(context("limit", 2.5d)).success());
        assertFalse(tool.execute(context("regex", "true")).success());
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
    void rejectsCatastrophicAlternationPatterns() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "aaaaaaaaaa\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        for (String filter : java.util.List.of("(a|aa)+$", "(a|a?)+$", "(a|aa)*$")) {
            ToolResult result = tool.execute(context("filter", filter, "regex", true));
            assertFalse(result.success(), filter + " should be rejected");
            String error = String.valueOf(result.error());
            assertTrue(error.contains("Regex filter rejected"), error);
        }
    }

    @Test
    void benignRegexFiltersLines() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "error one\ninfo two\nERROR three\nerror four\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context("filter", "^error", "regex", true)));
        assertEquals(2, out.get("count"));
        assertEquals(java.util.List.of("error one", "error four"), out.get("lines"));
    }

    @Test
    void substringFilterStillWorks() throws Exception {
        Files.writeString(tempDir.resolve("latest.log"), "error one\ninfo two\nerror four\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context("filter", "error")));
        assertEquals(java.util.List.of("error one", "error four"), out.get("lines"));
    }

    @Test
    void longLinesAreMatchedOnBoundedPrefix() throws Exception {
        String longError = "error " + "x".repeat(9000);
        Files.writeString(tempDir.resolve("latest.log"), longError + "\ninfo line\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context("filter", "^error", "regex", true)));
        assertEquals(java.util.List.of(longError), out.get("lines"));
    }

    @Test
    void catastrophicPatternEvadingHeuristicsTimesOut() throws Exception {
        // Nested quantifier hidden behind an extra group: neither static guard matches this.
        Files.writeString(tempDir.resolve("latest.log"), "a".repeat(2000) + "b\n");
        LogsReadTool tool = new LogsReadTool(tempDir, false, EdenConfig.ToolConfig.defaults());

        ToolResult result = tool.execute(context("filter", "((a+))+$", "regex", true));
        assertFalse(result.success());
        assertEquals("Regex filter timed out", result.error());
    }

    @Test
    void budgetedCharSequenceThrowsAfterDeadline() {
        LogsReadTool.BudgetedCharSequence seq =
                new LogsReadTool.BudgetedCharSequence("error", System.nanoTime() - 1);
        assertThrows(LogsReadTool.RegexBudgetExceeded.class, seq::length);
        assertThrows(LogsReadTool.RegexBudgetExceeded.class, () -> seq.charAt(0));
        assertThrows(LogsReadTool.RegexBudgetExceeded.class, () -> seq.subSequence(0, 1));
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

    @Test
    void symlinkedLogsRootResolvesFiles() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("real-logs"));
        Path link = tempDir.resolve("logs");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return; // Symbolic links are not available on every platform.
        }
        Files.writeString(real.resolve("latest.log"), "symlink-line\n");
        LogsReadTool tool = new LogsReadTool(link, false, EdenConfig.ToolConfig.defaults());

        Map<String, Object> out = content(tool.execute(context()));
        assertEquals(java.util.List.of("symlink-line"), out.get("lines"));
        assertEquals(false, out.get("truncated"));
    }

    @Test
    void compressedTailOnlyDecodesLastWindow() throws Exception {
        Path gz = tempDir.resolve("tail.gz");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append("zeile-").append(String.format("%03d", i)).append("-ä\n");
        }
        try (GZIPOutputStream os = new GZIPOutputStream(Files.newOutputStream(gz))) {
            os.write(content.toString().getBytes(StandardCharsets.UTF_8));
        }
        EdenConfig.ToolConfig bounded = new EdenConfig.ToolConfig(
                250, 5, 10, 100, 500, 200, 200, 100_000,
                1024 * 1024, 1024 * 1024, 500, 10, 200);
        LogsReadTool tool = new LogsReadTool(tempDir, false, bounded);

        Map<String, Object> out = content(tool.execute(context("file", "tail.gz", "limit", 5)));
        assertEquals(true, out.get("truncated"));
        assertEquals(java.util.List.of("zeile-195-ä", "zeile-196-ä", "zeile-197-ä",
                "zeile-198-ä", "zeile-199-ä"), out.get("lines"));
    }
}
