package at.haha007.edenmcp.core.util;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Bounded text-file access confined to the server working directory.
 *
 * <p>Paths supplied by an MCP client are relative to the server directory. Both the
 * lexical path and the real path of every existing component are checked so that
 * neither {@code ..} traversal nor a symlink can reach outside that directory.
 */
public final class ServerFileAccess {
    /** A file read from, or written to, the server directory. */
    public record TextFile(String path, String content, int bytes,
                           int lineStart, int lineCount, boolean hasMore) {
        public TextFile(String path, String content, int bytes) {
            this(path, content, bytes, 0, splitLines(content).size(), false);
        }
    }

    /** A single entry returned by a directory listing. */
    public record FileEntry(String name, String path, String type, long size) {}

    /** A listing result that reports whether the configured limits hid further entries. */
    public record Listing(List<FileEntry> entries, boolean truncated) {}

    private final Path root;
    private final Path realRoot;

    public ServerFileAccess(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("Server directory is not a directory: " + normalized);
        }
        this.root = normalized;
        this.realRoot = normalized.toRealPath();
    }

    /** Lists direct children of a directory under the server root. */
    public List<FileEntry> list(String rawPath, int maxEntries) throws IOException {
        return list(rawPath, maxEntries, false, 1, null).entries();
    }

    /**
     * Lists entries below a directory under the server root.
     *
     * @param recursive whether to descend into subdirectories (symlinks are never followed)
     * @param maxDepth  maximum directory depth (1 = direct children only)
     * @param glob      optional glob pattern matched against server-relative paths
     */
    public Listing list(String rawPath, int maxEntries, boolean recursive, int maxDepth,
                        @Nullable String glob) throws IOException {
        if (maxEntries < 1) throw new IOException("Invalid entry limit");
        if (maxDepth < 1) throw new IOException("Depth must be at least 1");
        Path directory = resolve(rawPath, true);
        if (!Files.isDirectory(directory)) throw new IOException("Path is not a directory: " + rawPath);

        PathMatcher matcher = null;
        if (glob != null && !glob.isBlank()) {
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (PatternSyntaxException e) {
                throw new IOException("Invalid glob pattern", e);
            }
        }

        boolean[] truncated = {false};
        List<FileEntry> entries = new ArrayList<>();
        if (recursive) {
            walk(directory, maxDepth, matcher, maxEntries, entries, truncated);
        } else {
            try (Stream<Path> children = Files.list(directory)) {
                List<Path> sorted = children.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (Path child : sorted) {
                    if (!matches(matcher, child)) continue;
                    if (entries.size() >= maxEntries) {
                        truncated[0] = true;
                        break;
                    }
                    entries.add(describe(child));
                }
            }
        }
        entries.sort(Comparator.comparing(FileEntry::path));
        return new Listing(List.copyOf(entries), truncated[0]);
    }

    private void walk(Path directory, int maxDepth, @Nullable PathMatcher matcher, int maxEntries,
                      List<FileEntry> entries, boolean[] truncated) throws IOException {
        Files.walkFileTree(directory, Set.of(), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(directory)) return FileVisitResult.CONTINUE;
                return add(dir) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return add(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // skip unreadable entries
            }

            private boolean add(Path path) {
                if (!matches(matcher, path)) return true;
                if (entries.size() >= maxEntries) {
                    truncated[0] = true;
                    return false;
                }
                entries.add(describe(path));
                return true;
            }
        });
    }

    private boolean matches(@Nullable PathMatcher matcher, Path path) {
        return matcher == null || matcher.matches(Path.of(displayPath(path)));
    }

    private FileEntry describe(Path child) {
        String type;
        if (Files.isSymbolicLink(child)) type = "symlink";
        else if (Files.isDirectory(child)) type = "directory";
        else if (Files.isRegularFile(child)) type = "file";
        else type = "other";
        long size = (type.equals("file")) ? sizeOf(child) : 0;
        return new FileEntry(child.getFileName().toString(), displayPath(child), type, size);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Reads a whole UTF-8 text file, rejecting binary content and files larger than {@code maxBytes}. */
    public TextFile read(String rawPath, int maxBytes) throws IOException {
        return read(rawPath, maxBytes, 0, Integer.MAX_VALUE);
    }

    /**
     * Reads a line-aligned slice of a UTF-8 text file.
     *
     * @param offset zero-based index of the first line to return
     * @param limit  maximum number of lines to return ({@link Integer#MAX_VALUE} for the rest)
     */
    public TextFile read(String rawPath, int maxBytes, int offset, int limit) throws IOException {
        if (maxBytes < 1) throw new IOException("Invalid read limit");
        if (offset < 0) throw new IOException("offset must not be negative");
        if (limit < 1) throw new IOException("limit must be at least 1");
        Path file = resolve(rawPath);
        if (!Files.isRegularFile(file)) throw new IOException("Path is not a regular file: " + rawPath);

        byte[] bytes = readBounded(file, maxBytes);
        for (byte b : bytes) {
            if (b == 0) throw new IOException("File does not appear to be text (contains NUL bytes)");
        }
        String text = decodeUtf8(bytes);
        List<String> lines = splitLines(text);
        int from = Math.min(offset, lines.size());
        int to = limit == Integer.MAX_VALUE ? lines.size()
                : (int) Math.min((long) from + limit, lines.size());
        String content = (from == 0 && to == lines.size()) ? text : String.join("", lines.subList(from, to));
        return new TextFile(displayPath(file), content, content.getBytes(StandardCharsets.UTF_8).length,
                from, to - from, to < lines.size());
    }

    /** Reads at most {@code maxBytes + 1} bytes so a file growing mid-read cannot bypass the cap. */
    private static byte[] readBounded(Path file, int maxBytes) throws IOException {
        int toRead = maxBytes == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxBytes + 1;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes(toRead);
            if (data.length > maxBytes) {
                throw new IOException("File is too large to read (max " + maxBytes + " bytes)");
            }
            return data;
        }
    }

    /** Decodes UTF-8 strictly so malformed byte sequences are rejected instead of replaced. */
    private static String decodeUtf8(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("File is not valid UTF-8 text", e);
        }
    }

    /** Splits text into lines, keeping each line's terminator so slices reproduce the original bytes. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }

    /** Creates or overwrites a UTF-8 text file atomically. Parent directories must already exist. */
    public TextFile write(String rawPath, String content, int maxBytes) throws IOException {
        if (content == null) throw new IOException("Content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IOException("Content is too large to write (max " + maxBytes + " bytes)");
        }
        for (byte b : bytes) {
            if (b == 0) throw new IOException("Content does not appear to be text (contains NUL bytes)");
        }
        Path file = resolve(rawPath);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file)) {
            throw new IOException("Path is not a regular file: " + rawPath);
        }
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Parent directory does not exist: " + rawPath);
        }

        Path temp = Files.createTempFile(parent, ".edenmcp-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            copyPosixPermissions(file, temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return new TextFile(displayPath(file), content, bytes.length);
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private static void copyPosixPermissions(Path original, Path temp) {
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(original);
            Files.setPosixFilePermissions(temp, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem: keep the temp file's secure default permissions.
        }
    }

    /**
     * Resolves a client-supplied relative path inside the server directory.
     * Missing tail components are allowed so new files can be created, but every
     * existing component (including the target itself) must resolve inside the root.
     */
    public Path resolve(String rawPath) throws IOException {
        return resolve(rawPath, false);
    }

    private Path resolve(String rawPath, boolean allowRoot) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            if (allowRoot) return realRoot;
            throw new IOException("Path is required");
        }
        Path supplied;
        try {
            supplied = Path.of(rawPath);
        } catch (InvalidPathException e) {
            throw new IOException("Invalid path", e);
        }
        if (supplied.isAbsolute()) {
            throw new IOException("Absolute paths are not allowed; use a path relative to the server directory");
        }
        Path candidate = root.resolve(supplied).normalize();
        if (candidate.equals(root)) {
            if (allowRoot) return realRoot;
            throw new IOException("Path must be a file inside the server directory");
        }
        if (!candidate.startsWith(root)) {
            throw new IOException("Path must be inside the server directory");
        }

        Path existing = candidate;
        Deque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null) throw new IOException("Path must be a file inside the server directory");
            missing.push(name);
            existing = existing.getParent();
        }
        Path realBase;
        try {
            realBase = existing.toRealPath();
        } catch (IOException e) {
            throw new IOException("Path cannot be resolved inside the server directory", e);
        }
        if (!realBase.startsWith(realRoot)) {
            throw new IOException("Path escapes the server directory (symlink)");
        }
        Path result = realBase;
        while (!missing.isEmpty()) result = result.resolve(missing.pop());
        return result;
    }

    private String displayPath(Path file) {
        return realRoot.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }
}
