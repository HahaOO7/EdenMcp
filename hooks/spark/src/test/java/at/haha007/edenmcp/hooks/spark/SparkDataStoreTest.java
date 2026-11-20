package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(store.list("all", 10).stream()
                .anyMatch(f -> "profile".equals(f.get("type"))));
        assertTrue(store.list("all", 10).stream()
                .anyMatch(f -> "heapdump".equals(f.get("type"))));
        assertTrue(store.list("all", 10).stream()
                .noneMatch(f -> "notes.txt".equals(f.get("name"))));
    }
}
