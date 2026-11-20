package at.haha007.edenmcp.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
        this.root = logsDir;
        this.maxDecompressedBytes = maxDecompressedBytes;
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
     * Reads the entire decompressed content of a compressed log, capped at
     * {@code maxDecompressedBytes} to prevent decompression bombs.
     */
    Decompressed readCompressed(Path path, Compression compression) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream raw = Files.newInputStream(path);
             InputStream decoded = switch (compression) {
                 case GZIP -> new GZIPInputStream(raw);
                 case ZIP -> firstZipEntry(raw);
                 case PLAIN -> raw;
             }) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = decoded.read(buf)) >= 0) {
                if (out.size() + n > maxDecompressedBytes) {
                    int remaining = maxDecompressedBytes - out.size();
                    if (remaining > 0) out.write(buf, 0, remaining);
                    return new Decompressed(out.toByteArray(), true);
                }
                out.write(buf, 0, n);
            }
            return new Decompressed(out.toByteArray(), false);
        }
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
