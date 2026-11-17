package at.haha007.edenMcp.core.auth;

import at.haha007.edenMcp.core.util.PrivateFileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessKeyManagerTest {
    @TempDir
    Path dir;

    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("AccessKeyManagerTest");

    private String readKey(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    @Test
    void generatesValidOwnerOnlyKey() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = readKey(keyFile);
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertTrue(PrivateFileUtil.isOwnerOnly(keyFile));
        assertTrue(manager.verify(key));
        assertEquals(Principal.primary(), manager.authenticate(key));
    }

    @Test
    void rejectsPresentedKeysWithWhitespace() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = readKey(keyFile);
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

        String key = readKey(keyFile);
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertTrue(manager.verify(key));
        assertFalse(manager.verify("weak-key"));
        assertTrue(Files.exists(dir.resolve(".key.invalid")));
        assertEquals("weak-key", readKey(dir.resolve(".key.invalid")));
    }

    @Test
    void worldReadableKeyFileIsReplaced() throws Exception {
        Path keyFile = dir.resolve(".key");
        Files.writeString(keyFile, "a".repeat(64), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        AccessKeyManager manager = new AccessKeyManager(keyFile, logger);
        manager.loadOrCreate();

        String key = readKey(keyFile);
        assertFalse(key.equals("a".repeat(64)));
        assertTrue(PrivateFileUtil.isOwnerOnly(keyFile));
    }

    @Test
    void rotationWritesNewKeyAndKeepsOldKeyDuringGrace() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger, 300, null);
        manager.loadOrCreate();
        String oldKey = readKey(keyFile);

        AccessKeyManager.RotationResult result = manager.rotate();
        String newKey = readKey(keyFile);

        assertEquals(newKey, result.newKey());
        assertNotEquals(oldKey, newKey);
        assertTrue(PrivateFileUtil.isOwnerOnly(keyFile));
        assertEquals(Principal.primary(), manager.authenticate(newKey));
        assertEquals(Principal.PRIMARY_ID, result.invalidatedPrincipalId());
        assertTrue(result.graceSeconds() > 0);

        Principal retired = manager.authenticate(oldKey);
        assertNotNull(retired, "old key should still work during grace");
        assertEquals(result.graceKeyId(), retired.id());
        assertEquals(2, manager.activeKeyCount());
    }

    @Test
    void zeroGraceExpiresOldKeyImmediately() throws Exception {
        Path keyFile = dir.resolve(".key");
        AccessKeyManager manager = new AccessKeyManager(keyFile, logger, 0, null);
        manager.loadOrCreate();
        String oldKey = readKey(keyFile);

        manager.rotate();

        assertNull(manager.authenticate(oldKey));
        assertEquals(1, manager.activeKeyCount());
    }

    @Test
    void additionalKeysFromDirectoryAreAccepted() throws Exception {
        Path keyFile = dir.resolve(".key");
        Path keysDir = dir.resolve("keys");
        Files.createDirectories(keysDir);
        Path secondary = keysDir.resolve("ci-runner.key");
        String secondaryKey = "b".repeat(64);
        Files.writeString(secondary, secondaryKey + "\n", StandardCharsets.UTF_8);
        PrivateFileUtil.setOwnerOnly(secondary);

        AccessKeyManager manager = new AccessKeyManager(keyFile, logger, 0, keysDir);
        manager.loadOrCreate();

        Principal p = manager.authenticate(secondaryKey);
        assertNotNull(p);
        assertEquals("ci-runner", p.id());
        assertEquals(2, manager.activeKeyCount());
        assertTrue(manager.describeKeys().contains("ci-runner"));
    }

    @Test
    void invalidAdditionalKeysAreIgnoredAndNeverBreakStartup() throws Exception {
        Path keyFile = dir.resolve(".key");
        Path keysDir = dir.resolve("keys");
        Files.createDirectories(keysDir);
        Path bad = keysDir.resolve("bad.key");
        Files.writeString(bad, "short\n", StandardCharsets.UTF_8);

        AccessKeyManager manager = new AccessKeyManager(keyFile, logger, 0, keysDir);
        assertDoesNotThrow(manager::loadOrCreate);
        assertEquals(1, manager.activeKeyCount());
        assertFalse(manager.revoke("bad"), "invalid key file must never be loaded, so there is nothing to revoke");
        assertFalse(manager.revoke(Principal.PRIMARY_ID));
        List<String> described = manager.describeKeys();
        assertEquals(List.of(Principal.PRIMARY_ID), described);
    }
}
