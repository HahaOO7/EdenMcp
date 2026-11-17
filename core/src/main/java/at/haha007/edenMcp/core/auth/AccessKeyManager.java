package at.haha007.edenMcp.core.auth;

import at.haha007.edenMcp.core.util.PrivateFileUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class AccessKeyManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern KEY_FORMAT = Pattern.compile("[0-9a-f]{64}");
    private final Path keyFile;
    private final Logger logger;
    private volatile byte[] keyBytes;

    public AccessKeyManager(Path keyFile, Logger logger) {
        this.keyFile = keyFile;
        this.logger = logger;
    }

    public void loadOrCreate() throws IOException {
        Path parent = keyFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        if (Files.exists(keyFile)) {
            String key;
            try {
                PrivateFileUtil.requireOwnerOnly(keyFile, "Access key file");
                key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                regenerate("Access key file rejected: " + e.getMessage());
                return;
            }
            if (!KEY_FORMAT.matcher(key).matches()) {
                regenerate("Access key file has an unexpected format (expected 64 lowercase hex characters)");
                return;
            }
            this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
            logger.info("Loaded MCP access key from " + keyFile.getFileName());
            return;
        }
        generate();
    }

    private void regenerate(String reason) throws IOException {
        Path quarantine = keyFile.resolveSibling(keyFile.getFileName() + ".invalid");
        int n = 1;
        while (Files.exists(quarantine)) {
            quarantine = keyFile.resolveSibling(keyFile.getFileName() + ".invalid." + n++);
        }
        try {
            Files.move(keyFile, quarantine, StandardCopyOption.REPLACE_EXISTING);
            PrivateFileUtil.setOwnerOnly(quarantine);
        } catch (Exception e) {
            logger.warning("[SECURITY] Could not quarantine invalid key file: " + e.getMessage());
        }
        logger.warning("[SECURITY] " + reason + ". Moved to " + quarantine.getFileName() + " and generated a new key.");
        generate();
    }

    private void generate() throws IOException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = HexFormat.of().formatHex(raw);
        Files.writeString(keyFile, key + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        PrivateFileUtil.setOwnerOnly(keyFile);
        this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
        logger.info("Generated new MCP access key at " + keyFile.toAbsolutePath() + " (distribute out-of-band)");
    }

    public boolean verify(String presented) {
        if (presented == null || keyBytes == null) return false;
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(keyBytes, p);
    }

    public boolean isLoaded() {
        return keyBytes != null && keyBytes.length > 0;
    }
}
