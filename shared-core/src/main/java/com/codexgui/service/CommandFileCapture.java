package com.codexgui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded, command-local fallback for shell edits that have no provider fileChange event. */
public final class CommandFileCapture {
    private static final int MAX_PATHS = 128;
    private static final int MAX_COMMANDS = 16;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_CAPTURE_BYTES = 16L * 1024 * 1024;
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"\\r\\n]+)['\"]");
    private static final Pattern TOKEN = Pattern.compile("[^\\s'\"`$<>|*?=,;()\\[\\]{}]+");
    private static final Pattern FILE_NAME = Pattern.compile(".*\\.[\\p{L}\\p{N}_+-]{1,32}");

    public record Before(Path path, boolean exists, byte[] bytes) { }
    private final WorkspacePathPolicy paths;
    private final Map<String, List<Before>> pending = new LinkedHashMap<>();
    private long retainedBytes;

    public CommandFileCapture(Path root) { paths = new WorkspacePathPolicy(root); }

    public synchronized void started(String id, String command, String cwd) {
        if (id == null || id.isBlank() || pending.containsKey(id) || pending.size() >= MAX_COMMANDS) return;
        var before = new ArrayList<Before>();
        for (var path : candidates(command, cwd)) {
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
        pending.put(id, before);
    }

    public synchronized List<Before> completed(String id) {
        var before = pending.remove(id);
        if (before == null) return List.of();
        var changed = new ArrayList<Before>();
        for (var entry : before) {
            if (entry.bytes() != null) retainedBytes -= entry.bytes().length;
            // Revalidate paths: a command could have replaced a parent with a symlink.
            if (paths.normalize(entry.path()) == null || Files.isSymbolicLink(entry.path())) continue;
            try {
                boolean exists = Files.exists(entry.path(), LinkOption.NOFOLLOW_LINKS);
                if (exists && !Files.isRegularFile(entry.path())) continue;
                if (exists != entry.exists()) changed.add(entry);
                else if (exists) {
                    if (Files.size(entry.path()) != entry.bytes().length) changed.add(entry);
                    else try (var input = Files.newInputStream(entry.path())) {
                        if (!Arrays.equals(entry.bytes(), input.readNBytes(MAX_FILE_BYTES + 1))) changed.add(entry);
                    }
                }
            } catch (IOException | SecurityException ignored) { }
        }
        return List.copyOf(changed);
    }

    public synchronized List<Before> finish() {
        var changed = new ArrayList<Before>();
        for (var id : List.copyOf(pending.keySet())) changed.addAll(completed(id));
        return List.copyOf(changed);
    }

    public synchronized void clear() { pending.clear(); retainedBytes = 0; }

    public synchronized void forget(Path path) {
        pending.replaceAll((id, entries) -> entries.stream().filter(entry -> {
            if (!entry.path().equals(path)) return true;
            if (entry.bytes() != null) retainedBytes -= entry.bytes().length;
            return false;
        }).toList());
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
        var quoted = QUOTED.matcher(command);
        while (quoted.find() && found.size() < MAX_PATHS) add(found, base, quoted.group(1));
        var tokens = TOKEN.matcher(command);
        while (tokens.find() && found.size() < MAX_PATHS) add(found, base, tokens.group());
        return List.copyOf(found);
    }

    private void add(LinkedHashSet<Path> found, Path base, String candidate) {
        if (candidate.isBlank() || candidate.startsWith("-") || candidate.length() > 4096
            || candidate.matches(".*[\\r\\n\\t$`<>|*?=;(){}\\[\\]].*")) return;
        try {
            var path = Path.of(candidate.replace('\\', '/'));
            path = paths.normalize(path.isAbsolute() ? path : base.resolve(path));
            if (path != null && (FILE_NAME.matcher(candidate).matches() || Files.isRegularFile(path))) found.add(path);
        } catch (RuntimeException ignored) { }
    }
}
