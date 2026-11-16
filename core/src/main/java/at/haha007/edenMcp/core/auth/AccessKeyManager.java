package at.haha007.edenMcp.core.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.logging.Logger;

public final class AccessKeyManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyFile;
    private final Logger logger;
    private volatile byte[] keyBytes;

    public AccessKeyManager(Path keyFile, Logger logger) {
        this.keyFile = keyFile;
        this.logger = logger;
    }

    public void loadOrCreate() throws IOException {
        if (Files.exists(keyFile)) {
            String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                throw new IOException("Access key file is empty: " + keyFile);
            }
            this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
            logger.info("Loaded MCP access key from " + keyFile.getFileName());
            return;
        }
        Files.createDirectories(keyFile.getParent());
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = HexFormat.of().formatHex(raw);
        Files.writeString(keyFile, key + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        trySetOwnerOnlyPermissions(keyFile);
        this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
        logger.info("Generated new MCP access key at " + keyFile.toAbsolutePath() + " (distribute out-of-band)");
    }

    public boolean verify(String presented) {
        if (presented == null || keyBytes == null) return false;
        byte[] p = presented.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(keyBytes, p);
    }

    public boolean isLoaded() {
        return keyBytes != null && keyBytes.length > 0;
    }

    private void trySetOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (Exception ignored) {
            // non-POSIX FS; best effort
        }
    }
}
