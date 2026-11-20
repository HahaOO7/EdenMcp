package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkDataStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsTraversalAndUnsupportedNames() {
        SparkDataStore store = new SparkDataStore(tempDir);

        assertOpenRejected(store, "../config.yml");
        assertOpenRejected(store, "notes.txt");
        assertOpenRejected(store, "bad name.sparkprofile");
    }

    @Test
    void rejectsSymlinkEscapingTheSparkDirectory() throws IOException {
        Path outside = Files.createTempFile(tempDir.getParent(), "outside", ".sparkprofile");
        Files.writeString(outside, "secret");
        Path link = tempDir.resolve("linked.sparkprofile");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        SparkDataStore store = new SparkDataStore(tempDir);
        assertOpenRejected(store, "linked.sparkprofile");
    }

    @Test
    void readsBoundedChunksFromTheValidatedHandle() throws IOException {
        Path file = tempDir.resolve("profile-1.sparkprofile");
        Files.write(file, new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        SparkDataStore store = new SparkDataStore(tempDir);

        try (SparkDataStore.FileHandle handle = store.open("profile-1.sparkprofile")) {
            assertEquals("profile", handle.type());
            assertEquals(10L, handle.size());

            SparkDataStore.Chunk chunk = store.read(handle, 7, 5);
            assertArrayEquals(new byte[]{7, 8, 9}, chunk.bytes());
            assertEquals(3, chunk.returned());

            SparkDataStore.Chunk empty = store.read(handle, 10, 5);
            assertEquals(0, empty.returned());
            assertArrayEquals(new byte[0], empty.bytes());
        }

        try (SparkDataStore.FileHandle handle = store.open("profile-1.sparkprofile")) {
            handle.close();
            assertThrows(ClosedChannelException.class, () -> store.read(handle, 0, 1));
        }
    }

    @Test
    void reportsOnlyBytesActuallyReadWhenFileShrinksAfterOpen() throws IOException {
        Path file = tempDir.resolve("shrink.sparkprofile");
        Files.write(file, new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        SparkDataStore store = new SparkDataStore(tempDir);

        try (SparkDataStore.FileHandle handle = store.open("shrink.sparkprofile")) {
            assertEquals(10L, handle.size());
            try (SeekableByteChannel writer = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
                writer.truncate(4);
            }

            SparkDataStore.Chunk chunk = store.read(handle, 0, 10);
            assertEquals(4, chunk.returned());
            assertArrayEquals(new byte[]{0, 1, 2, 3}, chunk.bytes());
        }
    }

    private static void assertOpenRejected(SparkDataStore store, String name) {
        assertThrows(IllegalArgumentException.class, () -> {
            try (SparkDataStore.FileHandle ignored = store.open(name)) {
                assertNotNull(ignored);
            }
        });
    }

    @Test
    void listsOnlyKnownSparkArtifacts() throws IOException {
        Files.writeString(tempDir.resolve("profile-1.sparkprofile"), "profile");
        Files.writeString(tempDir.resolve("dump-1.hprof"), "dump");
        Files.writeString(tempDir.resolve("notes.txt"), "notes");
        SparkDataStore store = new SparkDataStore(tempDir);

        assertTrue(store.list("all", 10).files().stream()
                .anyMatch(f -> "profile".equals(f.get("type"))));
        assertTrue(store.list("all", 10).files().stream()
                .anyMatch(f -> "heapdump".equals(f.get("type"))));
        assertTrue(store.list("all", 10).files().stream()
                .noneMatch(f -> "notes.txt".equals(f.get("name"))));
    }

    @Test
    void listingKeepsBoundedTopNWhileCountingEveryMatch() throws IOException {
        for (int i = 0; i < 10; i++) {
            Path file = tempDir.resolve("profile-" + i + ".sparkprofile");
            Files.writeString(file, "profile " + i);
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000L + i * 1_000L));
        }
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");

        SparkDataStore.Listing listing = new SparkDataStore(tempDir).list("profile", 3);
        assertEquals(10, listing.total());
        assertEquals(3, listing.files().size());
        assertTrue(listing.hasMore());
        assertEquals(List.of("profile-9.sparkprofile", "profile-8.sparkprofile", "profile-7.sparkprofile"),
                listing.files().stream().map(f -> String.valueOf(f.get("name"))).toList());
        assertEquals(0, new SparkDataStore(tempDir).list("heapdump", 3).total());
    }

    @SuppressWarnings("unchecked")
    @Test
    void readToolHandlesExtremeOffsetsAndRejectsStringNumbers() throws IOException {
        Files.writeString(tempDir.resolve("tiny.sparkprofile"), "abc");
        SparkDataReadTool tool = new SparkDataReadTool(new SparkDataStore(tempDir));

        ToolResult hugeOffset = tool.execute(context(Map.of(
                "file", "tiny.sparkprofile",
                "offset", Long.MAX_VALUE - 1,
                "length", 8)));
        assertTrue(hugeOffset.success(), String.valueOf(hugeOffset.error()));
        Map<String, Object> out = (Map<String, Object>) Objects.requireNonNull(hugeOffset.content());
        assertEquals(0, out.get("returned"));
        assertEquals(false, out.get("truncated"));

        ToolResult stringOffset = tool.execute(context(Map.of(
                "file", "tiny.sparkprofile",
                "offset", "1")));
        assertFalse(stringOffset.success());
    }

    private static ToolContext context(Map<String, Object> arguments) {
        return new ToolContext(OperatingMode.SAFE, "unit-test", arguments);
    }
}
