package at.haha007.edenmcp.core.auth;

import at.haha007.edenmcp.core.util.PrivateFileUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Holds the active MCP access keys. By default there is exactly one key in a
 * single {@code .key} file. An optional directory of additional keys and an
 * in-memory grace period after rotation are opt-in hardening features.
 */
public final class AccessKeyManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern KEY_FORMAT = Pattern.compile("[0-9a-f]{64}");

    private final Path keyFile;
    @Nullable private final Path keysDir;
    private final int graceSeconds;
    private final Logger logger;
    private final Map<String, KeyEntry> keys = new ConcurrentHashMap<>();

    public AccessKeyManager(Path keyFile, Logger logger) {
        this(keyFile, logger, 0, null);
    }

    public AccessKeyManager(Path keyFile, Logger logger, int graceSeconds, @Nullable Path keysDir) {
        this.keyFile = keyFile;
        this.logger = logger;
        this.graceSeconds = Math.max(0, graceSeconds);
        this.keysDir = keysDir;
    }

    /** Result of a rotation. The new key is returned once; it is also written to {@code .key}. */
    public record RotationResult(String newKey, String invalidatedPrincipalId,
                                 String graceKeyId, int graceSeconds) {}

    public void loadOrCreate() throws IOException {
        Path parent = keyFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        if (Files.exists(keyFile)) {
            String primary;
            try {
                PrivateFileUtil.requireOwnerOnly(keyFile, "Access key file");
                primary = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                if (!KEY_FORMAT.matcher(primary).matches()) {
                    throw new IOException("unexpected format (expected 64 lowercase hex characters)");
                }
            } catch (Exception e) {
                regenerate("Access key file rejected: " + e.getMessage());
                primary = null;
            }
            if (primary != null) {
                keys.put(Principal.PRIMARY_ID,
                        new KeyEntry(primary.getBytes(StandardCharsets.UTF_8), Principal.primary(), 0));
                logger.info("Loaded MCP access key from " + keyFile.getFileName());
            }
        } else {
            generate();
        }
        loadAdditionalKeys();
        logger.info("MCP access keys active: " + keys.size());
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
        String key = randomKey();
        writeKey(key);
        keys.put(Principal.PRIMARY_ID,
                new KeyEntry(key.getBytes(StandardCharsets.UTF_8), Principal.primary(), 0));
        logger.info("Generated new MCP access key at " + keyFile.toAbsolutePath() + " (distribute out-of-band)");
    }

    private static String randomKey() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /** Atomically replace the key file, keeping it owner-only. */
    private void writeKey(String key) throws IOException {
        Path tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
        Files.writeString(tmp, key + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        PrivateFileUtil.setOwnerOnly(tmp);
        try {
            Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
        }
        PrivateFileUtil.setOwnerOnly(keyFile);
    }

    private void loadAdditionalKeys() {
        if (keysDir == null || keysDir.toString().isBlank()) return;
        Path dir = keysDir.isAbsolute() ? keysDir
                : (keyFile.getParent() != null ? keyFile.getParent().resolve(keysDir) : keysDir);
        if (!Files.isDirectory(dir)) return;

        int loaded = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                String id = uniqueId(sanitizeId(stripExtension(p.getFileName().toString())));
                try {
                    PrivateFileUtil.requireOwnerOnly(p, "Additional key file");
                    String key = Files.readString(p, StandardCharsets.UTF_8).trim();
                    if (!KEY_FORMAT.matcher(key).matches()) {
                        logger.warning("[SECURITY] Ignoring additional key file " + p.getFileName()
                                + ": expected 64 lowercase hex characters");
                        continue;
                    }
                    keys.put(id, new KeyEntry(key.getBytes(StandardCharsets.UTF_8), new Principal(id, id), 0));
                    loaded++;
                } catch (Exception e) {
                    logger.warning("[SECURITY] Ignoring additional key file " + p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("[SECURITY] Could not read additional key directory " + dir + ": " + e.getMessage());
        }
        if (loaded > 0) logger.info("Loaded " + loaded + " additional MCP key(s) from " + dir);
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (keys.containsKey(id)) id = base + "_" + n++;
        return id;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitizeId(String raw) {
        if (raw == null || raw.isBlank()) return "key";
        String s = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) s = "k-" + s;
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /**
     * Constant-time lookup across all active keys. Returns the identity of the
     * matched key, or {@code null} when no key matches. Expired grace keys are
     * pruned before matching.
     */
    public @Nullable Principal authenticate(@Nullable String presented) {
        if (presented == null) return null;
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        pruneExpired();
        for (Map.Entry<String, KeyEntry> e : keys.entrySet()) {
            if (MessageDigest.isEqual(e.getValue().material(), p)) return e.getValue().principal();
        }
        return null;
    }

    /** Backwards-compatible boolean form. */
    public boolean verify(@Nullable String presented) {
        return authenticate(presented) != null;
    }

    /**
     * Rotate the primary key. The old primary key stays valid for the configured
     * grace period (in memory only) so clients can be updated without downtime.
     * The returned {@code invalidatedPrincipalId} identifies sessions that must
     * be closed by the transport.
     */
    public synchronized RotationResult rotate() throws IOException {
        KeyEntry current = keys.get(Principal.PRIMARY_ID);
        if (current == null) throw new IOException("No primary access key is loaded");

        String oldKey = new String(current.material(), StandardCharsets.UTF_8);
        String newKey = randomKey();
        // Write first: if this fails, the old key remains fully active.
        writeKey(newKey);

        String graceKeyId = "retired-" + System.nanoTime();
        if (graceSeconds > 0) {
            long expires = System.currentTimeMillis() + graceSeconds * 1000L;
            keys.put(graceKeyId,
                    new KeyEntry(oldKey.getBytes(StandardCharsets.UTF_8),
                            new Principal(graceKeyId, "retired-primary"), expires));
        }
        keys.put(Principal.PRIMARY_ID,
                new KeyEntry(newKey.getBytes(StandardCharsets.UTF_8), Principal.primary(), 0));

        logger.info("[SECURITY] MCP access key rotated"
                + (graceSeconds > 0 ? "; previous key valid for " + graceSeconds + "s (in-memory grace)" : "")
                + ". Update clients using the new value in " + keyFile.getFileName() + ".");
        return new RotationResult(newKey, Principal.PRIMARY_ID, graceKeyId, graceSeconds);
    }

    /** Revoke an additional/grace key by id. The primary key cannot be removed this way. */
    public boolean revoke(@Nullable String id) {
        if (id == null || Principal.PRIMARY_ID.equals(id)) return false;
        return keys.remove(id) != null;
    }

    public int activeKeyCount() {
        pruneExpired();
        return keys.size();
    }

    /** Log-safe descriptions of active keys; never includes key material. */
    public List<String> describeKeys() {
        pruneExpired();
        return keys.values().stream().map(e -> e.principal().display()).sorted().toList();
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        keys.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    private record KeyEntry(byte[] material, Principal principal, long expiresAt) {
        boolean expired(long now) {
            return expiresAt > 0 && now >= expiresAt;
        }
    }
}
