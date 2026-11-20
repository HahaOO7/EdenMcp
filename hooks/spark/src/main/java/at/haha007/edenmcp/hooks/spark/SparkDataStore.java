package at.haha007.edenmcp.hooks.spark;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Restricted reader for raw files spark saved under its own plugin directory. */
final class SparkDataStore {
    static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,180}");
    private static final String PROFILE_EXT = ".sparkprofile";
    private static final String HEAP_EXT = ".hprof";

    private final Path root;

    SparkDataStore(Path root) {
        this.root = root;
    }

    static Optional<SparkDataStore> create(Server server) {
        try {
            Plugin spark = server.getPluginManager().getPlugin("spark");
            Path candidate = spark != null
                    ? spark.getDataFolder().toPath()
                    : server.getWorldContainer().toPath().resolveSibling("plugins").resolve("spark");
            // Inspection is read-only: never create the plugin directory just to make this
            // hook available. Real Spark installs already have it; any future write path can
            // create the directory lazily when it actually has something to store.
            if (!Files.exists(candidate)) return Optional.empty();
            Path real = candidate.toRealPath();
            if (!Files.isDirectory(real)) return Optional.empty();
            return Optional.of(new SparkDataStore(real));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** A bounded listing: at most {@code limit} newest entries plus truncation metadata. */
    record Listing(List<Map<String, Object>> files, int total, boolean hasMore) {}

    private record ListingCandidate(Map<String, Object> entry, FileTime modified) {}

    Listing list(String kind, int limit) throws IOException {
        // Compare instants numerically, never their rendered strings: ISO-8601 text is not
        // lexicographically ordered across seconds/minutes (e.g. "00:00:10Z" < "00:00:09Z").
        Comparator<ListingCandidate> oldestFirst = Comparator.comparing(ListingCandidate::modified);
        Comparator<ListingCandidate> newestFirst = oldestFirst.reversed();
        // Keep only the newest `limit` entries instead of materializing and sorting every file.
        PriorityQueue<ListingCandidate> newest = new PriorityQueue<>(oldestFirst);
        int total = 0;
        try (Stream<Path> paths = Files.list(root)) {
            // Traverse lazily: filtering by type and kind while walking keeps memory bounded to
            // the top-N queue instead of materializing every regular file first.
            Iterator<Path> iterator = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String name = path.getFileName().toString();
                String type = typeOf(name);
                if (type == null) continue;
                if (!"all".equals(kind) && !kind.equals(type)) continue;
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                FileTime modified = attrs.lastModifiedTime();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("type", type);
                entry.put("mimeType", mimeOf(type));
                entry.put("size", attrs.size());
                entry.put("lastModified", modified.toInstant().toString());
                total++;
                ListingCandidate candidate = new ListingCandidate(entry, modified);
                if (newest.size() < limit) {
                    newest.add(candidate);
                } else if (oldestFirst.compare(candidate, newest.peek()) > 0) {
                    newest.poll();
                    newest.add(candidate);
                }
            }
        }
        List<Map<String, Object>> files = newest.stream()
                .sorted(newestFirst)
                .map(ListingCandidate::entry)
                .toList();
        return new Listing(files, total, total > files.size());
    }

    FileHandle open(String name) throws IOException {
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid 'file' name");
        }
        String type = typeOf(name);
        if (type == null) {
            throw new IllegalArgumentException("Only spark profile and heap dump files can be read");
        }
        Path path = root.resolve(name).toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid 'file' name");
        }
        // Keep the validated channel open so a later read cannot follow a replaced path.
        try {
            SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
            // Take the size from the validated channel so it cannot disagree with the file we read.
            return new FileHandle(path, name, type, mimeOf(type), channel.size(), channel);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to open Spark data", e);
        }
    }

    Chunk read(FileHandle file, long offset, int length) throws IOException {
        int maxAvailable = (int) Math.clamp(file.size() - offset, 0L, Integer.MAX_VALUE);
        int size = Math.clamp(length, 0, maxAvailable);
        if (size == 0) {
            return new Chunk(new byte[0], sha256(new byte[0]), 0);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        SeekableByteChannel channel = file.channel();
        channel.position(offset);
        int returned = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) break;
            returned += read;
        }
        // Report only the bytes actually read: the file may have shrunk since the handle opened.
        byte[] bytes = returned == size ? buffer.array() : Arrays.copyOf(buffer.array(), returned);
        return new Chunk(bytes, sha256(bytes), returned);
    }

    byte[] readUpTo(FileHandle file, int maxBytes) throws IOException {
        return read(file, 0, maxBytes).bytes();
    }

    record FileHandle(Path path, String name, String type, String mimeType, long size,
                      SeekableByteChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    record Chunk(byte[] bytes, String sha256, int returned) {
    }

    private static @Nullable String typeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(PROFILE_EXT)) return "profile";
        if (lower.endsWith(HEAP_EXT)) return "heapdump";
        return null;
    }

    private static String mimeOf(String type) {
        return "profile".equals(type) ? "application/x-spark-sampler" : "application/octet-stream";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
