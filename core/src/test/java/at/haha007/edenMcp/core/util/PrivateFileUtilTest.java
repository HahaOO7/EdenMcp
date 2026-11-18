package at.haha007.edenMcp.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrivateFileUtilTest {
    @TempDir
    Path dir;

    @Test
    void ownerOnlyRegularFileIsAccepted() throws Exception {
        Path file = dir.resolve("secret");
        Files.writeString(file, "secret");
        PrivateFileUtil.setOwnerOnly(file);

        assertTrue(PrivateFileUtil.isOwnerOnly(file));
        assertDoesNotThrow(() -> PrivateFileUtil.requireOwnerOnly(file, "Secret file"));
    }

    @Test
    void groupOrWorldReadableFileIsRejected() throws Exception {
        Path file = dir.resolve("loose");
        Files.writeString(file, "secret");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertFalse(PrivateFileUtil.isOwnerOnly(file));
        IOException e = assertThrows(IOException.class,
                () -> PrivateFileUtil.requireOwnerOnly(file, "Access key file"));
        assertTrue(e.getMessage().contains("Access key file"), e.getMessage());
        assertTrue(e.getMessage().contains("owner-only"), e.getMessage());

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        assertFalse(PrivateFileUtil.isOwnerOnly(file), "group write access must also be rejected");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        assertFalse(PrivateFileUtil.isOwnerOnly(file), "owner read-only is not the expected mode either");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertTrue(PrivateFileUtil.isOwnerOnly(file));
    }

    @Test
    void symlinkToAnOwnerOnlyFileIsRejected() throws Exception {
        Path target = dir.resolve("target");
        Files.writeString(target, "secret");
        PrivateFileUtil.setOwnerOnly(target);
        Path link = dir.resolve("link");

        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks are not supported on this filesystem: " + e);
        }

        assertFalse(PrivateFileUtil.isOwnerOnly(link), "secret files must not be accepted through a symlink");
        assertThrows(IOException.class, () -> PrivateFileUtil.requireOwnerOnly(link, "Access key file"));
    }

    @Test
    void directoriesAndMissingFilesAreRejected() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("keys"));
        assertFalse(PrivateFileUtil.isOwnerOnly(sub), "a directory is not a regular secret file");
        assertThrows(IOException.class, () -> PrivateFileUtil.requireOwnerOnly(sub, "Keys directory"));

        Path missing = dir.resolve("missing.key");
        assertFalse(PrivateFileUtil.isOwnerOnly(missing));
        assertThrows(IOException.class, () -> PrivateFileUtil.requireOwnerOnly(missing, "Access key file"));
    }

    @Test
    void setOwnerOnlyIsBestEffortAndNeverThrows() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("keystore"));

        assertDoesNotThrow(() -> PrivateFileUtil.setOwnerOnly(sub));
        assertDoesNotThrow(() -> PrivateFileUtil.setOwnerOnly(dir.resolve("does-not-exist")));
        assertFalse(PrivateFileUtil.isOwnerOnly(sub), "permission fixes must not make a directory acceptable");
    }
}
