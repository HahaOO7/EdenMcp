package at.haha007.edenmcp.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Safe access to plain and compressed log files inside the server's logs directory. */
final class LogFileAccess {
    /** Simple file names only: no path separators, no {@code ..}, no control characters. */
    static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,180}");
    private static final String GZ_SUFFIX = ".gz";
    private static final String ZIP_SUFFIX = ".zip";

    private final Path root;
    private final int maxDecompressedBytes;

    LogFileAccess(Path logsDir, int maxDecompressedBytes) {
        this.root = canonicalRoot(logsDir);
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    /** Use the real path when available so a symlinked logs/ directory still resolves inside the root. */
    private static Path canonicalRoot(Path logsDir) {
        if (Files.exists(logsDir)) {
            try {
                return logsDir.toRealPath();
            } catch (IOException ignored) {
                // Fall through to the absolute normalized path.
            }
        }
        return logsDir.toAbsolutePath().normalize();
    }

    enum Compression { PLAIN, GZIP, ZIP }

    /** Resolves a user-supplied file name to a regular file inside the logs directory. */
    Path resolve(String name) throws IOException {
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw new IOException("Invalid log file name");
        }
        Path path = root.resolve(name).toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid log file name");
        }
        return path;
    }

    static Compression compressionOf(String name) {
        if (name.endsWith(GZ_SUFFIX)) return Compression.GZIP;
        if (name.endsWith(ZIP_SUFFIX)) return Compression.ZIP;
        return Compression.PLAIN;
    }

    /** Only expose files that look like logs (plain, gzip or zip archives). */
    private static boolean isLogFile(String name) {
        return name.endsWith(".log") || name.endsWith(GZ_SUFFIX) || name.endsWith(ZIP_SUFFIX);
    }

    /** Lists log files in the logs directory, newest first, bounded to {@code limit} entries. */
    record Listing(List<Map<String, Object>> files, int total, boolean hasMore) {}

    Listing list(int limit) throws IOException {
        List<Map<String, Object>> all;
        try (Stream<Path> paths = Files.list(root)) {
            all = paths
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> isLogFile(p.getFileName().toString()))
                    .map(p -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        String name = p.getFileName().toString();
                        entry.put("name", name);
                        entry.put("compression", compressionOf(name).name().toLowerCase());
                        try {
                            var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            entry.put("size", attrs.size());
                            entry.put("lastModified", attrs.lastModifiedTime().toInstant().toString());
                        } catch (IOException e) {
                            entry.put("size", null);
                            entry.put("lastModified", null);
                        }
                        return entry;
                    })
                    .sorted(Comparator.comparing(
                            m -> String.valueOf(m.get("lastModified")), Comparator.reverseOrder()))
                    .toList();
        }
        List<Map<String, Object>> files = all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
        return new Listing(files, all.size(), all.size() > files.size());
    }

    /**
     * Decompresses a log while retaining only the last {@code maxTailBytes} of data. Total
     * decompression is still capped at {@code maxDecompressedBytes} to prevent bombs; the
     * returned window always starts at a line boundary so strict UTF-8 decoding stays safe.
     */
    Decompressed readCompressed(Path path, Compression compression, int maxTailBytes) throws IOException {
        int tailCap = Math.clamp(maxTailBytes, 0, Math.max(0, maxDecompressedBytes));
        ByteArrayOutputStream tail = new ByteArrayOutputStream(Math.min(tailCap, 8192));
        long total = 0;
        boolean decompressionCapped = false;
        try (InputStream raw = Files.newInputStream(path);
             InputStream decoded = switch (compression) {
                 case GZIP -> new GZIPInputStream(raw);
                 case ZIP -> firstZipEntry(raw);
                 case PLAIN -> raw;
             }) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = decoded.read(buf)) >= 0) {
                if (n == 0) continue;
                long allowed = maxDecompressedBytes - total;
                if (allowed <= 0) {
                    decompressionCapped = true;
                    break;
                }
                int take = (int) Math.min(n, allowed);
                tail.write(buf, 0, take);
                total += take;
                if (take < n) {
                    decompressionCapped = true;
                    break;
                }
                // Compact occasionally so the rolling buffer never grows far past the window.
                if (tail.size() > tailCap + buf.length) {
                    byte[] buffered = tail.toByteArray();
                    int drop = buffered.length - tailCap;
                    tail.reset();
                    tail.write(buffered, drop, buffered.length - drop);
                }
            }
        }
        byte[] buffered = tail.toByteArray();
        int start = Math.max(0, buffered.length - tailCap);
        boolean skippedBeforeWindow = total > buffered.length - start;
        byte[] window = start == 0 ? buffered : Arrays.copyOfRange(buffered, start, buffered.length);
        if (skippedBeforeWindow) window = dropPartialFirstLine(window);
        return new Decompressed(window, decompressionCapped || skippedBeforeWindow);
    }

    /** Drops a leading partial line so the window begins at a UTF-8 character boundary. */
    private static byte[] dropPartialFirstLine(byte[] window) {
        for (int i = 0; i < window.length; i++) {
            byte b = window[i];
            if (b == '\n' || b == '\r') {
                int cut = (b == '\r' && i + 1 < window.length && window[i + 1] == '\n') ? i + 2 : i + 1;
                return Arrays.copyOfRange(window, cut, window.length);
            }
        }
        // No line break in the window: skip leading UTF-8 continuation bytes, if any.
        int start = 0;
        while (start < window.length && (window[start] & 0xC0) == 0x80) start++;
        return start == 0 ? window : Arrays.copyOfRange(window, start, window.length);
    }

    record Decompressed(byte[] bytes, boolean truncated) {}

    private static InputStream firstZipEntry(InputStream raw) throws IOException {
        ZipInputStream zip = new ZipInputStream(raw);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.isDirectory()) return zip;
        }
        throw new IOException("Zip archive contains no file entries");
    }
}
