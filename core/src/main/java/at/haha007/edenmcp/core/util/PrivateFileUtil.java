package at.haha007.edenmcp.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Helpers for files that hold secret material (access key, TLS keystore, etc.). */
public final class PrivateFileUtil {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private PrivateFileUtil() {}

    /** True when the path is a regular file with owner-only permissions. Non-POSIX filesystems are best-effort. */
    public static boolean isOwnerOnly(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) return false;
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path);
            return actual.equals(OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: cannot enforce, treat as acceptable (best effort).
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (Exception ignored) {
            // Non-POSIX FS; best effort.
        }
    }

    /** @throws IOException when the file is missing, a symlink, or readable by group/others. */
    public static void requireOwnerOnly(Path path, String description) throws IOException {
        if (!isOwnerOnly(path)) {
            throw new IOException(description + " must be a regular, owner-only file (not a symlink): " + path);
        }
    }
}
