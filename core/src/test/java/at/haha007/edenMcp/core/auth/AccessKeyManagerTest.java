package at.haha007.edenMcp.core.auth;

import at.haha007.edenMcp.core.util.PrivateFileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccessKeyManagerTest {
    @TempDir
    Path dir;

    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("AccessKeyManagerTest");

    @Test
    void generatesValidOwnerOnlyKey() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertTrue(PrivateFileUtil.isOwnerOnly(keyFile));
        assertTrue(manager.verify(key));
    }

    @Test
    void rejectsPresentedKeysWithWhitespace() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        assertFalse(manager.verify(" " + key));
        assertFalse(manager.verify(key + " "));
        assertFalse(manager.verify(key.toUpperCase()));
    }

    @Test
    void invalidKeyFileIsQuarantinedAndReplaced() throws Exception {
        Path keyFile = dir.resolve(".key");
        Files.writeString(keyFile, "weak-key\n", StandardCharsets.UTF_8);

        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertTrue(manager.verify(key));
        assertFalse(manager.verify("weak-key"));
        assertTrue(Files.exists(dir.resolve(".key.invalid")));
        assertEquals("weak-key", Files.readString(dir.resolve(".key.invalid"), StandardCharsets.UTF_8).trim());
    }

    @Test
    void worldReadableKeyFileIsReplaced() throws Exception {
        Path keyFile = dir.resolve(".key");
        Files.writeString(keyFile, "a".repeat(64), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        assertFalse(key.equals("a".repeat(64)));
        assertTrue(PrivateFileUtil.isOwnerOnly(keyFile));
    }
}
