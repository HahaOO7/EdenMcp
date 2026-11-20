package at.haha007.edenmcp.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ServerFileAccessTest {
    @TempDir
    Path serverDir;

    private ServerFileAccess access() throws IOException {
        return new ServerFileAccess(serverDir);
    }

    @Test
    void listsEntriesInRootAndSubdirectories() throws Exception {
        Files.createDirectory(serverDir.resolve("plugins"));
        Files.writeString(serverDir.resolve("server.properties"), "x");
        Files.writeString(serverDir.resolve("plugins/config.yml"), "y");

        List<ServerFileAccess.FileEntry> root = access().list("", 100);
        assertEquals(List.of("plugins", "server.properties"), root.stream().map(ServerFileAccess.FileEntry::name).toList());
        assertEquals("directory", root.getFirst().type());
        assertEquals("plugins", root.getFirst().path());

        assertEquals(root, access().list(".", 100), "'.' and an empty path both mean the server root");

        List<ServerFileAccess.FileEntry> nested = access().list("plugins", 100);
        assertEquals(1, nested.size());
        assertEquals("plugins/config.yml", nested.getFirst().path());
        assertEquals("file", nested.getFirst().type());
    }

    @Test
    void listIsBoundedAndConfined() throws Exception {
        Files.createDirectory(serverDir.resolve("sub"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(serverDir.resolve("sub").resolve("file" + i + ".txt"), "x");
        }
        Path outside = serverDir.resolveSibling("edenmcp-outside-dir");
        Files.createDirectories(outside);
        Path link = serverDir.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported: " + e);
        }

        ServerFileAccess files = access();
        assertEquals(2, files.list("sub", 2).size());
        assertThrows(IOException.class, () -> files.list("../..", 100));
        assertThrows(IOException.class, () -> files.list("outside-link", 100));
        assertThrows(IOException.class, () -> files.list("server.properties", 100));

        Files.deleteIfExists(link);
        Files.deleteIfExists(outside);
    }

    @Test
    void nonRecursiveListingIsBoundedButReportsTotal() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(serverDir.resolve("f%02d.txt".formatted(i)), "x");
        }
        ServerFileAccess files = access();

        ServerFileAccess.Listing limited = files.list("", 3, false, 1, null);
        assertEquals(List.of("f00.txt", "f01.txt", "f02.txt"),
                limited.entries().stream().map(ServerFileAccess.FileEntry::name).toList());
        assertEquals(3, limited.entries().size());
        assertEquals(20, limited.total());
        assertTrue(limited.truncated());

        ServerFileAccess.Listing filtered = files.list("", 2, false, 1, "f1*.txt");
        assertEquals(List.of("f10.txt", "f11.txt"),
                filtered.entries().stream().map(ServerFileAccess.FileEntry::name).toList());
        assertEquals(10, filtered.total());
        assertTrue(filtered.truncated());

        ServerFileAccess.Listing all = files.list("", 100, false, 1, null);
        assertEquals(20, all.entries().size());
        assertEquals(20, all.total());
        assertFalse(all.truncated());
    }

    @Test
    void readWithMaxIntByteLimitDoesNotOverflow() throws Exception {
        Files.writeString(serverDir.resolve("small.txt"), "hello\n");
        ServerFileAccess files = access();

        assertEquals("hello\n", files.read("small.txt", Integer.MAX_VALUE).content());
        assertEquals("hello\n", files.read("small.txt", Integer.MAX_VALUE, 0, 1).content());
    }

    @Test
    void readsFilesAnywhereUnderTheServerRoot() throws Exception {
        Files.writeString(serverDir.resolve("server.properties"), "server-port=25565\n");
        Path nested = serverDir.resolve("plugins").resolve("EdenMcp");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("config.yml"), "enabled: true\n");

        ServerFileAccess.TextFile root = access().read("server.properties", 1024);
        assertEquals("server-port=25565\n", root.content());
        assertEquals("server.properties", root.path());
        assertEquals(18, root.bytes());

        ServerFileAccess.TextFile sub = access().read("plugins/EdenMcp/config.yml", 1024);
        assertEquals("enabled: true\n", sub.content());
        assertEquals("plugins/EdenMcp/config.yml", sub.path());
    }

    @Test
    void writesNewAndExistingFiles() throws Exception {
        Files.createDirectory(serverDir.resolve("plugins"));
        ServerFileAccess files = access();

        ServerFileAccess.TextFile created = files.write("plugins/test.yml", "first\n", 1024);
        assertEquals("plugins/test.yml", created.path());
        assertEquals("first\n", Files.readString(serverDir.resolve("plugins/test.yml")));

        files.write("plugins/test.yml", "second\n", 1024);
        assertEquals("second\n", Files.readString(serverDir.resolve("plugins/test.yml")));
    }

    @Test
    void rejectsTraversalAbsolutePathsAndTheRootItself() throws Exception {
        Path outside = serverDir.resolveSibling("edenmcp-outside.txt");
        Files.writeString(outside, "outside");
        Files.createDirectory(serverDir.resolve("inside"));
        ServerFileAccess files = access();

        for (String bad : new String[]{"../outside.txt", "inside/../../outside.txt", "/etc/passwd", ".", "", " "}) {
            assertThrows(IOException.class, () -> files.read(bad, 1024), "read should reject: " + bad);
        }
        for (String bad : new String[]{"../outside-new.txt", "/tmp/outside-new.txt", "."}) {
            assertThrows(IOException.class, () -> files.write(bad, "x", 1024), "write should reject: " + bad);
        }
        assertFalse(Files.exists(outside.resolveSibling("outside-new.txt")));
        Files.deleteIfExists(outside);
    }

    @Test
    void rejectsDirectoriesAndMissingFiles() throws Exception {
        Files.createDirectory(serverDir.resolve("folder"));
        ServerFileAccess files = access();
        assertThrows(IOException.class, () -> files.read("folder", 1024));
        assertThrows(IOException.class, () -> files.read("missing.txt", 1024));
        assertThrows(IOException.class, () -> files.write("missing-dir/file.txt", "x", 1024));
    }

    @Test
    void rejectsSymlinksThatEscapeTheServerRoot() throws Exception {
        Path outsideDir = serverDir.resolveSibling("outside-dir");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        Path link = serverDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported: " + e);
        }
        ServerFileAccess files = access();

        assertThrows(IOException.class, () -> files.read("link/secret.txt", 1024),
                "reads must not follow a symlink out of the server root");
        assertThrows(IOException.class, () -> files.write("link/new.txt", "x", 1024),
                "writes must not follow a symlink out of the server root");
        assertFalse(Files.exists(outsideDir.resolve("new.txt")));

        Path fileLink = serverDir.resolve("file-link.txt");
        Files.createSymbolicLink(fileLink, outsideDir.resolve("secret.txt"));
        assertThrows(IOException.class, () -> files.read("file-link.txt", 1024));

        Files.deleteIfExists(outsideDir.resolve("secret.txt"));
        Files.deleteIfExists(outsideDir);
    }

    @Test
    void allowsSymlinksThatStayInsideTheServerRoot() throws Exception {
        Path target = serverDir.resolve("target.txt");
        Files.writeString(target, "inside");
        Path link = serverDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported: " + e);
        }
        assertEquals("inside", access().read("link.txt", 1024).content());
    }

    @Test
    void enforcesSizeLimitsAndRejectsBinaryFiles() throws Exception {
        Files.writeString(serverDir.resolve("small.txt"), "abc");
        Files.writeString(serverDir.resolve("large.txt"), "x".repeat(64));
        Files.write(serverDir.resolve("binary.bin"), new byte[]{0, 1, 2});
        ServerFileAccess files = access();

        assertEquals("abc", files.read("small.txt", 3).content());
        assertThrows(IOException.class, () -> files.read("large.txt", 63));
        assertThrows(IOException.class, () -> files.read("binary.bin", 1024));
        assertThrows(IOException.class, () -> files.write("small.txt", "abcd", 3));
        assertThrows(IOException.class, () -> files.write("small.txt", "a\0b", 1024));
    }

    @Test
    void normalizesRelativePathsInResults() throws Exception {
        Files.createDirectory(serverDir.resolve("plugins"));
        ServerFileAccess files = access();
        assertEquals("plugins/normalized.txt", files.write("plugins/../plugins/normalized.txt", "ok", 1024).path());
    }

    @Test
    void rejectsInvalidUtf8WithoutNulBytes() throws Exception {
        Files.write(serverDir.resolve("bad.txt"), new byte[]{(byte) 0xC3, 0x28, 0x41});
        IOException e = assertThrows(IOException.class, () -> access().read("bad.txt", 1024));
        assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    }

    @Test
    void rejectsSpecialFiles() throws Exception {
        Path dir = Files.createDirectory(serverDir.resolve("folder"));
        Path link = serverDir.resolve("dir-link");
        try {
            Files.createSymbolicLink(link, dir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported: " + e);
        }
        ServerFileAccess files = access();
        assertThrows(IOException.class, () -> files.read("dir-link", 1024));
        assertThrows(IOException.class, () -> files.write("dir-link", "x", 1024));
    }

    @Test
    void readsLineAlignedSlices() throws Exception {
        Files.writeString(serverDir.resolve("lines.txt"), "one\r\ntwo\nthree\nfour");
        ServerFileAccess files = access();

        ServerFileAccess.TextFile first = files.read("lines.txt", 1024, 0, 2);
        assertEquals("one\r\ntwo\n", first.content());
        assertEquals(0, first.lineStart());
        assertEquals(2, first.lineCount());
        assertTrue(first.hasMore());

        ServerFileAccess.TextFile middle = files.read("lines.txt", 1024, 1, 2);
        assertEquals("two\nthree\n", middle.content());
        assertEquals(1, middle.lineStart());
        assertTrue(middle.hasMore());

        ServerFileAccess.TextFile last = files.read("lines.txt", 1024, 3, 10);
        assertEquals("four", last.content());
        assertFalse(last.hasMore());

        ServerFileAccess.TextFile whole = files.read("lines.txt", 1024);
        assertEquals("one\r\ntwo\nthree\nfour", whole.content());
        assertEquals(4, whole.lineCount());
        assertFalse(whole.hasMore());

        assertTrue(files.read("lines.txt", 1024, 99, 2).content().isEmpty());
        assertThrows(IOException.class, () -> files.read("lines.txt", 1024, -1, 2));
        assertThrows(IOException.class, () -> files.read("lines.txt", 1024, 0, 0));
    }

    @Test
    void atomicWritePreservesPosixPermissionsAndLeavesNoTempFiles() throws Exception {
        Path file = serverDir.resolve("config.yml");
        Files.writeString(file, "old");
        var permissions = PosixFilePermissions.fromString("rw-r--r--");
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions unsupported: " + e);
            return;
        }

        access().write("config.yml", "new", 1024);

        assertEquals("new", Files.readString(file));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
        try (var children = Files.list(serverDir)) {
            assertTrue(children.noneMatch(p -> p.getFileName().toString().startsWith(".edenmcp-")),
                    "temporary files must be cleaned up");
        }
    }

    @Test
    void recursiveListingHonoursDepthGlobAndEntryLimit() throws Exception {
        Path plugins = Files.createDirectory(serverDir.resolve("plugins"));
        Files.createDirectories(plugins.resolve("EdenMcp").resolve("deep"));
        Files.writeString(plugins.resolve("a.yml"), "a");
        Files.writeString(plugins.resolve("EdenMcp").resolve("config.yml"), "c");
        Files.writeString(plugins.resolve("EdenMcp").resolve("deep").resolve("hidden.txt"), "h");
        Files.writeString(serverDir.resolve("server.properties"), "p");
        ServerFileAccess files = access();

        ServerFileAccess.Listing shallow = files.list("plugins", 100, true, 1, null);
        assertEquals(List.of("plugins/EdenMcp", "plugins/a.yml"),
                shallow.entries().stream().map(ServerFileAccess.FileEntry::path).toList());

        assertEquals(4, files.list("plugins", 100, true, 2, null).entries().size());
        ServerFileAccess.Listing deep = files.list("plugins", 100, true, 5, null);
        assertEquals(5, deep.entries().size());
        assertEquals(5, deep.total());
        assertFalse(deep.truncated());

        ServerFileAccess.Listing yml = files.list("", 100, true, 5, "**/*.yml");
        assertEquals(List.of("plugins/EdenMcp/config.yml", "plugins/a.yml"),
                yml.entries().stream().map(ServerFileAccess.FileEntry::path).sorted().toList());
        assertEquals(2, yml.total());

        ServerFileAccess.Listing limited = files.list("plugins", 2, true, 5, null);
        assertEquals(2, limited.entries().size());
        assertEquals(5, limited.total());
        assertTrue(limited.truncated());

        assertThrows(IOException.class, () -> files.list("", 100, true, 5, "["));
    }
}
