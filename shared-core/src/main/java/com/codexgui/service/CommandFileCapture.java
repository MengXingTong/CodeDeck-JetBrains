package com.codexgui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, command-local fallback for shell edits that have no provider fileChange event. */
public final class CommandFileCapture {
    private static final int MAX_PATHS = 128;
    private static final int MAX_COMMANDS = 16;
    private static final int MAX_SCOPE_ENTRIES = 512;
    private static final int MAX_SCOPE_DEPTH = 16;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_CAPTURE_BYTES = 16L * 1024 * 1024;
    private static final Pattern QUOTED = Pattern.compile("(?=(['\"])([^'\"\\r\\n]+)\\1)");
    private static final Pattern TOKEN = Pattern.compile("[^\\s'\"`$<>|*?=,;()\\[\\]{}]+");
    private static final Pattern FILE_NAME = Pattern.compile(".*\\.[\\p{L}\\p{N}_+-]{1,32}");
    private static final Pattern SCRIPT = Pattern.compile("(?i).*\\.(?:mjs|cjs|js|py|ps1|sh|cmd|bat)$");
    private static final Pattern RENAME = Pattern.compile("(?i)\\b(?:Rename-Item|ren|rename)\\s+(?:(?:-LiteralPath|-Path)\\s+)?('[^'\\r\\n]+'|\"[^\"\\r\\n]+\"|[^\\s;]+)\\s+(?:-NewName\\s+)?('[^'\\r\\n]+'|\"[^\"\\r\\n]+\"|[^\\s;]+)");

    public record Before(Path path, boolean exists, byte[] bytes) { }
    private record Pending(List<Before> files, Map<Path, Set<Path>> directories, Set<Path> forgotten) { }
    private final WorkspacePathPolicy paths;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long retainedBytes;

    public CommandFileCapture(Path root) { paths = new WorkspacePathPolicy(root); }

    public synchronized void started(String id, String command, String cwd) {
        if (id == null || id.isBlank() || pending.containsKey(id) || pending.size() >= MAX_COMMANDS) return;
        var candidates = new LinkedHashSet<>(candidates(command, cwd));
        var directories = new LinkedHashMap<Path, Set<Path>>();
        var scopeBudget = new int[]{MAX_SCOPE_ENTRIES};
        // Scope computed paths to explicitly mentioned subdirectories or an executed script's directory.
        // Never enumerate the workspace root or follow links; oversized scopes are left to explicit paths.
        for (var path : List.copyOf(candidates)) {
            var directory = Files.isDirectory(path) || !Files.exists(path) && !path.getFileName().toString().contains(".")
                ? path : SCRIPT.matcher(path.toString()).matches() ? path.getParent() : null;
            if (directory == null || paths.normalize(directory) == null || directories.keySet().stream().anyMatch(directory::startsWith)) continue;
            var names = directoryFiles(directory, scopeBudget);
            if (names == null || names.size() > MAX_PATHS - directories.values().stream().mapToInt(Set::size).sum()) continue;
            directories.put(directory, names);
            candidates.addAll(names);
        }
        var before = new ArrayList<Before>();
        for (var path : candidates) {
            if (before.size() >= MAX_PATHS) break;
            try {
                // A missing regular file is a possible addition. Directories and links are never snapshotted.
                if (Files.isSymbolicLink(path) || Files.isDirectory(path)) continue;
                boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
                byte[] bytes = null;
                if (exists) {
                    if (!Files.isRegularFile(path)) continue;
                    long size = Files.size(path);
                    if (size > MAX_FILE_BYTES || size > MAX_CAPTURE_BYTES - retainedBytes) continue;
                    try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
                    if (bytes.length > MAX_FILE_BYTES || bytes.length > MAX_CAPTURE_BYTES - retainedBytes) continue;
                    retainedBytes += bytes.length;
                }
                before.add(new Before(path, exists, bytes));
            } catch (IOException | SecurityException ignored) { }
        }
        pending.put(id, new Pending(before, directories, new LinkedHashSet<>()));
    }

    public synchronized List<Before> completed(String id) {
        var snapshot = pending.remove(id);
        if (snapshot == null) return List.of();
        var changed = new LinkedHashMap<Path, Before>();
        for (var entry : snapshot.files()) {
            if (entry.bytes() != null) retainedBytes -= entry.bytes().length;
            // Revalidate paths: a command could have replaced a parent with a symlink.
            if (paths.normalize(entry.path()) == null || Files.isSymbolicLink(entry.path())) continue;
            try {
                boolean exists = Files.exists(entry.path(), LinkOption.NOFOLLOW_LINKS);
                if (exists && !Files.isRegularFile(entry.path())) continue;
                if (exists != entry.exists()) changed.put(entry.path(), entry);
                else if (exists) {
                    if (Files.size(entry.path()) != entry.bytes().length) changed.put(entry.path(), entry);
                    else try (var input = Files.newInputStream(entry.path())) {
                        if (!Arrays.equals(entry.bytes(), input.readNBytes(MAX_FILE_BYTES + 1))) changed.put(entry.path(), entry);
                    }
                }
            } catch (IOException | SecurityException ignored) { }
        }
        var scopeBudget = new int[]{MAX_SCOPE_ENTRIES};
        for (var directory : snapshot.directories().entrySet()) {
            var after = directoryFiles(directory.getKey(), scopeBudget);
            if (after == null) continue;
            for (var path : after) {
                if (changed.size() >= MAX_PATHS) break;
                if (!directory.getValue().contains(path) && !snapshot.forgotten().contains(path))
                    changed.putIfAbsent(path, new Before(path, false, null));
            }
        }
        return List.copyOf(changed.values());
    }

    public synchronized List<Before> finish() {
        var changed = new ArrayList<Before>();
        for (var id : List.copyOf(pending.keySet())) changed.addAll(completed(id));
        return List.copyOf(changed);
    }

    public synchronized void clear() { pending.clear(); retainedBytes = 0; }

    public synchronized void forget(Path path) {
        pending.replaceAll((id, snapshot) -> {
            snapshot.forgotten().add(path);
            return new Pending(snapshot.files().stream().filter(entry -> {
                if (!entry.path().equals(path)) return true;
                if (entry.bytes() != null) retainedBytes -= entry.bytes().length;
                return false;
            }).toList(), snapshot.directories(), snapshot.forgotten());
        });
    }

    private Set<Path> directoryFiles(Path directory, int[] budget) {
        if (paths.normalize(directory) == null || Files.isSymbolicLink(directory)) return null;
        if (!Files.exists(directory)) return Set.of();
        var found = new LinkedHashSet<Path>(); var complete = new boolean[]{true};
        try {
            Files.walkFileTree(directory, Set.of(), MAX_SCOPE_DEPTH, new SimpleFileVisitor<>() {
                private FileVisitResult count() {
                    if (budget[0]-- > 0) return FileVisitResult.CONTINUE;
                    complete[0] = false; return FileVisitResult.TERMINATE;
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (paths.normalize(dir) == null || Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    return count();
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isDirectory()) complete[0] = false; // depth bound reached
                    if (attributes.isRegularFile() && paths.normalize(file) != null) found.add(file);
                    return count();
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    complete[0] = false; return FileVisitResult.CONTINUE;
                }
            });
            return complete[0] ? found : null;
        } catch (IOException | SecurityException ignored) { return null; }
    }

    private List<Path> candidates(String command, String cwd) {
        if (command == null || command.isBlank()) return List.of();
        Path base;
        try {
            base = cwd == null || cwd.isBlank() ? paths.root() : Path.of(cwd);
            if (!base.isAbsolute()) base = paths.root().resolve(base);
            base = base.toAbsolutePath().normalize();
        } catch (RuntimeException ignored) { return List.of(); }
        var found = new LinkedHashSet<Path>();
        var readable = command.replace("\\\"", "\"").replace("\\'", "'");
        var quoted = QUOTED.matcher(readable);
        while (quoted.find() && found.size() < MAX_PATHS) add(found, base, quoted.group(2), true);
        var tokens = TOKEN.matcher(readable);
        while (tokens.find() && found.size() < MAX_PATHS) add(found, base, tokens.group());
        var rename = RENAME.matcher(readable);
        while (rename.find() && found.size() < MAX_PATHS) {
            try {
                var source = base.resolve(unquote(rename.group(1)).replace('\\', '/')).normalize();
                add(found, base, unquote(rename.group(1)));
                add(found, source.getParent(), unquote(rename.group(2)));
            } catch (RuntimeException ignored) { }
        }
        return List.copyOf(found);
    }

    private static String unquote(String value) {
        return value.length() > 1 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
            ? value.substring(1, value.length() - 1) : value;
    }

    private void add(LinkedHashSet<Path> found, Path base, String candidate) {
        add(found, base, candidate, false);
    }

    private void add(LinkedHashSet<Path> found, Path base, String candidate, boolean quoted) {
        if (candidate.isBlank() || candidate.startsWith("-") || candidate.length() > 4096
            || candidate.matches(".*[\\r\\n\\t$`<>|*?=;(){}\\[\\]].*")) return;
        try {
            var path = Path.of(candidate.replace('\\', '/'));
            path = paths.normalize(path.isAbsolute() ? path : base.resolve(path));
            if (path != null && (quoted || FILE_NAME.matcher(candidate).matches() || candidate.contains("/") || candidate.contains("\\")
                || Files.isRegularFile(path) || Files.isDirectory(path))) found.add(path);
        } catch (RuntimeException ignored) { }
    }
}
