package com.codexgui.service;

import com.codexgui.i18n.UiMessages;

import com.codexgui.model.ChangeEntry;
import com.codexgui.settings.CodexSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

@Service(Service.Level.PROJECT)
public final class ConversationChangeTracker implements Disposable {
    static final long MAX_BASELINE_BYTES = 5L * 1024L * 1024L;

    public record ChangeSummary(
        Path path,
        ChangeEntry.Kind kind,
        boolean reversible,
        int addedLines,
        int deletedLines
    ) {
        public String displayName(Path root) {
            try {
                return root.relativize(path).toString();
            } catch (IllegalArgumentException ignored) {
                return path.toString();
            }
        }
    }

    public record ChangeUpdate(String sessionId, List<ChangeSummary> changes) {}

    private enum BaselineKind { PRESENT, ABSENT }

    private record Baseline(BaselineKind kind, byte[] bytes, byte[] hash, String providerTurnKey) {
        private boolean reversible() { return kind == BaselineKind.ABSENT || bytes != null; }
    }

    private record CurrentContent(boolean present, byte[] bytes, byte[] hash) {}
    private record LineStats(int added, int deleted) {}

    private final Project project;
    private final Path root;
    private final WorkspacePathPolicy pathPolicy;
    private final BooleanSupplier preserveLineEndings;
    private final Map<String, LinkedHashMap<Path, Baseline>> baselinesBySession = new LinkedHashMap<>();
    private final Map<String, CommandFileCapture> commandsBySession = new LinkedHashMap<>();
    private final Map<String, Map<String, Map<Path, Baseline>>> pendingCommands = new LinkedHashMap<>();
    private final Map<String, Map<String, Map<Path, Baseline>>> pendingPatches = new LinkedHashMap<>();
    private final Map<String, Map<Path, Baseline>> pendingWrites = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ChangeUpdate>> listeners = new CopyOnWriteArrayList<>();

    public ConversationChangeTracker(Project project) {
        this.project = project;
        this.root = Path.of(project.getBasePath() == null ? "." : project.getBasePath()).toAbsolutePath().normalize();
        this.pathPolicy = new WorkspacePathPolicy(root);
        this.preserveLineEndings = () -> CodexSettingsState.getInstance().getState().preserveFileLineEndings;
        installListeners();
    }

    ConversationChangeTracker(Path root) {
        this(root, () -> true);
    }

    ConversationChangeTracker(Path root, BooleanSupplier preserveLineEndings) {
        this.project = null;
        this.root = root.toAbsolutePath().normalize();
        this.pathPolicy = new WorkspacePathPolicy(this.root);
        this.preserveLineEndings = preserveLineEndings;
    }

    public static ConversationChangeTracker getInstance(@NotNull Project project) {
        return project.getService(ConversationChangeTracker.class);
    }

    public Path getRoot() { return root; }

    public void addListener(Consumer<ChangeUpdate> listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Consumer<ChangeUpdate> listener) { listeners.remove(listener); }

    public synchronized boolean trackBeforeWrite(String sessionId, String reportedPath) {
        var target = resolveReportedPath(reportedPath);
        if (target == null) return false;
        var current = readCurrent(target);
        if (current.present() && current.hash() == null) return false;
        // File tools with a pre-write hook (Claude) are finalized when the live turn ends.
        var writes = pendingWrites.computeIfAbsent(sessionKey(sessionId), ignored -> new LinkedHashMap<>());
        if (writes.size() < 128 && current.bytes() != null
            && writes.values().stream().mapToLong(value -> value.bytes() == null ? 0 : value.bytes().length).sum()
                + current.bytes().length <= 16L * 1024 * 1024)
            writes.putIfAbsent(target, new Baseline(BaselineKind.PRESENT, current.bytes(), current.hash(), null));
        return trackBaseline(sessionKey(sessionId), target, current);
    }

    Path resolveWorkspacePath(String reportedPath) { return resolveReportedPath(reportedPath); }

    public synchronized void prepareCommands(String sessionId) {
        var repositories = new ArrayList<Path>(); repositories.add(root);
        if (project != null) {
            try {
                var manager = com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project);
                if (manager != null) for (var entry : manager.getAllVersionedRoots()) repositories.add(entry.toNioPath());
            } catch (RuntimeException ignored) { /* Optional metadata capture must not prevent sending. */ }
        }
        prepareCommands(sessionId, repositories);
    }

    public synchronized void prepareCommands(String sessionId, List<Path> repositories) {
        commandsBySession.computeIfAbsent(sessionKey(sessionId), ignored -> new CommandFileCapture(root)).prepare(repositories);
    }

    public synchronized void commandStarted(String sessionId, String id, String command, String cwd) {
        commandsStarted(sessionId, id, List.of(new ShellCommandInput(command, cwd)));
    }

    public synchronized void commandsStarted(String sessionId, String id, List<ShellCommandInput> inputs) {
        if (inputs.isEmpty()) return;
        var key = sessionKey(sessionId);
        var pending = pendingCommands.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        if (pending.containsKey(id) || pending.size() >= 16) return;
        var capture = commandsBySession.computeIfAbsent(key, ignored -> new CommandFileCapture(root));
        capture.started(id, inputs);
        var reserved = new LinkedHashMap<Path, Baseline>();
        var baselines = baselinesBySession.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        for (var before : capture.snapshot(id)) {
            var baseline = new Baseline(before.exists() ? BaselineKind.PRESENT : BaselineKind.ABSENT,
                before.bytes(), hash(before.bytes()), null);
            baselines.putIfAbsent(before.path(), baseline);
            reserved.put(before.path(), baseline);
        }
        pending.put(id, reserved);
    }

    public synchronized void commandCompleted(String sessionId, String id) {
        var capture = commandsBySession.get(sessionKey(sessionId));
        if (capture != null) importCommandChanges(sessionKey(sessionId), capture.completed(id));
        var key = sessionKey(sessionId);
        var pending = pendingCommands.get(key);
        var reserved = pending == null ? null : pending.remove(id);
        var baselines = baselinesBySession.get(key);
        if (reserved != null && baselines != null) for (var entry : reserved.entrySet())
            if (baselines.get(entry.getKey()) == entry.getValue() && createChange(entry.getKey(), entry.getValue()) == null)
                baselines.remove(entry.getKey());
        if (pending != null && pending.isEmpty()) pendingCommands.remove(key);
    }

    public synchronized void finishCommands(String sessionId) {
        var pending = pendingCommands.get(sessionKey(sessionId));
        if (pending != null) for (var id : List.copyOf(pending.keySet())) commandCompleted(sessionId, id);
        var capture = commandsBySession.remove(sessionKey(sessionId));
        if (capture != null) importCommandChanges(sessionKey(sessionId), capture.finish());
        var patches = pendingPatches.get(sessionKey(sessionId));
        if (patches != null) for (var id : List.copyOf(patches.keySet())) patchCompleted(sessionId, id);
        var writes = pendingWrites.remove(sessionKey(sessionId));
        if (writes != null) writes.forEach((path, before) -> preserveFormat(path, before.bytes()));
    }

    public synchronized void patchStarted(String sessionId, String id, List<String> reportedPaths) {
        if (reportedPaths.isEmpty()) return;
        var key = sessionKey(sessionId);
        var patches = pendingPatches.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        if (patches.containsKey(id) || patches.size() >= 16) return;
        var reserved = new LinkedHashMap<Path, Baseline>(); long bytes = 0;
        var baselines = baselinesBySession.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        for (var reported : reportedPaths) {
            var path = resolveReportedPath(reported);
            if (path == null || reserved.size() >= 128) continue;
            var current = readDiskCurrent(path);
            if (current.bytes() != null && current.bytes().length > 16L * 1024 * 1024 - bytes) continue;
            if (current.bytes() != null) bytes += current.bytes().length;
            var baseline = new Baseline(current.present() ? BaselineKind.PRESENT : BaselineKind.ABSENT,
                current.bytes(), current.hash(), null);
            baselines.putIfAbsent(path, baseline); reserved.put(path, baseline);
        }
        // Reserve silently on the protocol reader, before tools run; publish only on completion.
        patches.put(id, reserved);
    }

    public synchronized void patchCompleted(String sessionId, String id) {
        var key = sessionKey(sessionId); var patches = pendingPatches.get(key);
        if (patches == null) return;
        var reserved = patches.remove(id);
        if (patches.isEmpty()) pendingPatches.remove(key);
        if (reserved == null) return;
        var baselines = baselinesBySession.get(key);
        if (baselines == null) return;
        reserved.forEach((path, before) -> preserveFormat(path, before.bytes()));
        for (var entry : reserved.entrySet()) if (baselines.get(entry.getKey()) == entry.getValue()
            && createChange(entry.getKey(), entry.getValue()) == null) baselines.remove(entry.getKey());
        fireChanged(key);
    }

    private void importCommandChanges(String sessionId, List<CommandFileCapture.Before> changes) {
        if (changes.isEmpty()) return;
        var baselines = baselinesBySession.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        for (var before : changes) {
            if (before.preserveFormat()) preserveFormat(before.path(), before.bytes());
            baselines.putIfAbsent(before.path(), new Baseline(
                before.exists() ? BaselineKind.PRESENT : BaselineKind.ABSENT,
                before.bytes(), hash(before.bytes()), null));
        }
        // Already tracked files need an update too, even when their first baseline is retained.
        fireChanged(sessionId);
    }

    public synchronized void trackProviderDiff(String sessionId, String unifiedDiff) {
        trackProviderDiff(sessionId, null, unifiedDiff);
    }

    public synchronized void trackProviderDiff(String sessionId, String providerTurnKey, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return;
        var currentContents = new LinkedHashMap<String, CurrentContent>();
        var afterContents = new LinkedHashMap<String, byte[]>();
        for (var reportedPath : UnifiedDiffParser.split(unifiedDiff).keySet()) {
            var target = resolveReportedPath(reportedPath);
            if (target == null) continue;
            // Provider 直接写磁盘，IDE Document 可能尚未收到外部文件刷新。
            var current = readDiskCurrent(target);
            currentContents.put(reportedPath, current);
            afterContents.put(reportedPath, current.bytes());
        }
        for (var fileDiff : UnifiedDiffParser.parse(unifiedDiff, afterContents)) {
            var target = resolveReportedPath(fileDiff.path());
            var current = currentContents.get(fileDiff.path());
            if (target == null || current == null || !matchesKind(fileDiff.kind(), current.present())) continue;
            // 可读取文本必须匹配补丁 after 侧；过期事件留给同回合的后续累计 diff 处理。
            if (!fileDiff.binary() && current.bytes() != null && !fileDiff.matchesCurrent()) continue;
            var before = fileDiff.beforeContent();
            if (!fileDiff.binary() && current.bytes() != null && fileDiff.kind() != ChangeEntry.Kind.ADDED
                && before == null) continue;
            var baseline = fileDiff.kind() == ChangeEntry.Kind.ADDED
                ? new Baseline(BaselineKind.ABSENT, null, null, providerTurnKey)
                : new Baseline(BaselineKind.PRESENT, copy(before), hash(before), providerTurnKey);
            trackProviderBaseline(sessionKey(sessionId), target, baseline);
        }
    }

    public synchronized void trackProviderFile(
        String sessionId,
        String reportedPath,
        String kind,
        String diff
    ) {
        trackProviderFile(sessionId, reportedPath, kind, diff, null);
    }

    public synchronized void trackProviderFile(String sessionId, String reportedPath, String kind, String diff, String movePath) {
        if (reportedPath == null || reportedPath.isBlank()) return;
        var target = resolveReportedPath(reportedPath);
        if (target == null) return;
        if (movePath != null && !movePath.isBlank()) {
            var destination = resolveReportedPath(movePath);
            if (destination == null || destination.equals(target) || Files.exists(target) || !Files.isRegularFile(destination)) return;
            var after = readDiskCurrent(destination);
            var hunks = Objects.requireNonNullElse(diff, "").replaceFirst("(?s)\\n\\nMoved to: .*\\z", "");
            byte[] before = null;
            if (hunks.isBlank()) before = after.bytes();
            else {
                var header = "diff --git a/" + reportedPath + " b/" + movePath + "\n--- a/" + reportedPath + "\n+++ b/" + movePath + "\n";
                var contents = new LinkedHashMap<String, byte[]>(); contents.put(movePath, after.bytes());
                var parsed = UnifiedDiffParser.parse(header + hunks, contents);
                if (!parsed.isEmpty() && parsed.getFirst().matchesCurrent()) before = parsed.getFirst().beforeContent();
            }
            var key = sessionKey(sessionId);
            trackProviderBaseline(key, target, new Baseline(BaselineKind.PRESENT, copy(before), hash(before), null));
            // A cumulative diff can reach us first and describe the destination as a modification.
            // Correct that derived baseline, while preserving a command's real first-touch snapshot.
            var previous = baseline(key, destination);
            // Without a pre-tool snapshot, history cannot tell whether Move to overwrote a file.
            // Keep its destination visible but prohibit destructive rollback from invented bytes.
            trackProviderBaseline(key, destination, new Baseline(BaselineKind.PRESENT, null, null,
                previous == null ? null : previous.providerTurnKey()));
            return;
        }
        // Codex sends whole file contents for add/delete, and unified hunks for update.
        // An explicit addition needs only the absent baseline, including an empty new file.
        if ("add".equals(kind)) {
            if (Files.isRegularFile(target)) trackProviderBaseline(sessionKey(sessionId), target,
                new Baseline(BaselineKind.ABSENT, null, null, null));
            return;
        }
        if (diff == null) return;
        if ("delete".equals(kind) && !diff.startsWith("diff --git ") && !diff.startsWith("@@ ")) {
            if (Files.exists(target)) return;
            var before = diff.getBytes(StandardCharsets.UTF_8);
            trackProviderBaseline(sessionKey(sessionId), target, new Baseline(BaselineKind.PRESENT,
                before.length <= MAX_BASELINE_BYTES ? before : null, hash(before), null));
            return;
        }
        if (diff.isBlank()) return;
        var header = "diff --git a/" + reportedPath + " b/" + reportedPath + "\n";
        var normalizedDiff = diff.startsWith("diff --git ") ? diff : switch (kind) {
            case "delete" -> header + "deleted file mode 100644\n--- a/" + reportedPath + "\n+++ /dev/null\n" + diff;
            default -> header + "--- a/" + reportedPath + "\n+++ b/" + reportedPath + "\n" + diff;
        };
        trackProviderDiff(sessionId, normalizedDiff);
    }

    public synchronized List<ChangeSummary> listSummaries(String sessionId) {
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        if (baselines == null || baselines.isEmpty()) return List.of();
        var summaries = new ArrayList<ChangeSummary>();
        for (var entry : baselines.entrySet()) {
            var change = createChange(entry.getKey(), entry.getValue());
            if (change == null) continue;
            var stats = lineStats(change.unifiedDiff());
            summaries.add(new ChangeSummary(
                change.path(), change.kind(), change.reversible(), stats.added(), stats.deleted()));
        }
        return List.copyOf(summaries);
    }

    public synchronized ChangeEntry readDetails(String sessionId, Path path) {
        var target = normalizePath(path);
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        if (target == null || baselines == null) return null;
        var baseline = baselines.get(target);
        return baseline == null ? null : createChange(target, baseline);
    }

    public synchronized void accept(String sessionId, Path path) {
        removeBaseline(sessionKey(sessionId), normalizePath(path));
    }

    public synchronized void acceptAll(String sessionId) {
        var key = sessionKey(sessionId);
        pendingWrites.remove(key);
        pendingPatches.remove(key);
        pendingCommands.remove(key);
        var capture = commandsBySession.get(key);
        if (capture != null) capture.clear();
        baselinesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized void revert(String sessionId, Path path) throws IOException {
        var key = sessionKey(sessionId);
        var target = normalizePath(path);
        var baseline = target == null ? null : baseline(key, target);
        if (baseline == null) return;
        restore(target, baseline);
        removeBaseline(key, target);
        refresh(target);
    }

    public synchronized void revertAll(String sessionId) throws IOException {
        var key = sessionKey(sessionId);
        var baselines = baselinesBySession.get(key);
        if (baselines == null || baselines.isEmpty()) return;
        var failures = new ArrayList<String>();
        for (var entry : List.copyOf(baselines.entrySet())) {
            try {
                if (createChange(entry.getKey(), entry.getValue()) != null) restore(entry.getKey(), entry.getValue());
                var capture = commandsBySession.get(key);
                if (capture != null) capture.forget(entry.getKey());
                baselines.remove(entry.getKey());
                refresh(entry.getKey());
            } catch (IOException error) {
                failures.add(entry.getKey() + "（" + error.getMessage() + "）");
            }
        }
        if (baselines.isEmpty()) baselinesBySession.remove(key);
        fireChanged(key);
        if (!failures.isEmpty()) throw new IOException(UiMessages.message("以下文件无法撤销：\n") + String.join("\n", failures));
    }

    public synchronized void clearSession(String sessionId) {
        pendingWrites.remove(sessionKey(sessionId));
        var key = sessionKey(sessionId);
        pendingPatches.remove(key);
        pendingCommands.remove(key);
        commandsBySession.remove(key);
        baselinesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized boolean isTracked(String sessionId, Path path) {
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        var target = normalizePath(path);
        return target != null && baselines != null && baselines.containsKey(target);
    }

    public void refreshSession(String sessionId) {
        var key = sessionKey(sessionId);
        List<Path> paths;
        synchronized (this) {
            var baselines = baselinesBySession.get(key);
            if (baselines == null || baselines.isEmpty()) return;
            paths = List.copyOf(baselines.keySet());
        }

        // 外部 CLI 写盘后主动刷新已登记文件，避免依赖 IDE 是否恰好收到 VFS 通知。
        if (project == null || ApplicationManager.getApplication() == null) {
            fireChanged(key);
            return;
        }
        LocalFileSystem.getInstance().refreshNioFiles(paths, true, false, () -> fireChanged(key));
    }

    private boolean trackBaseline(String sessionId, Path target, CurrentContent current) {
        var baseline = current.present()
            ? new Baseline(BaselineKind.PRESENT, copy(current.bytes()), copy(current.hash()), null)
            : new Baseline(BaselineKind.ABSENT, null, null, null);
        return trackBaseline(sessionId, target, baseline);
    }

    private boolean trackProviderBaseline(String sessionId, Path target, Baseline baseline) {
        var baselines = baselinesBySession.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        var current = baselines.get(target);
        if (current != null && (baseline.providerTurnKey() == null
            || !Objects.equals(current.providerTurnKey(), baseline.providerTurnKey()))) return false;
        baselines.put(target, baseline);
        fireChanged(sessionId);
        return true;
    }

    private boolean trackBaseline(String sessionId, Path target, Baseline baseline) {
        var baselines = baselinesBySession.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        if (baselines.putIfAbsent(target, baseline) != null) return false;
        fireChanged(sessionId);
        return true;
    }

    private Baseline baseline(String sessionId, Path path) {
        var baselines = baselinesBySession.get(sessionId);
        return baselines == null ? null : baselines.get(path);
    }

    private void removeBaseline(String sessionId, Path path) {
        if (path == null) return;
        var writes = pendingWrites.get(sessionId);
        if (writes != null) writes.remove(path);
        var patches = pendingPatches.get(sessionId);
        if (patches != null) patches.values().forEach(values -> values.remove(path));
        var commands = pendingCommands.get(sessionId);
        if (commands != null) commands.values().forEach(values -> values.remove(path));
        var capture = commandsBySession.get(sessionId);
        if (capture != null) capture.forget(path);
        var baselines = baselinesBySession.get(sessionId);
        if (baselines == null || baselines.remove(path) == null) return;
        if (baselines.isEmpty()) baselinesBySession.remove(sessionId);
        fireChanged(sessionId);
    }

    private ChangeEntry createChange(Path path, Baseline baseline) {
        var current = readCurrent(path);
        if (baseline.kind() == BaselineKind.ABSENT && !current.present()) return null;
        if (baseline.kind() == BaselineKind.PRESENT && current.present()
            && Arrays.equals(baseline.hash(), current.hash())) return null;

        var kind = baseline.kind() == BaselineKind.ABSENT
            ? ChangeEntry.Kind.ADDED
            : current.present() ? ChangeEntry.Kind.MODIFIED : ChangeEntry.Kind.DELETED;
        var relativePath = root.relativize(path).toString().replace('\\', '/');
        var before = baseline.kind() == BaselineKind.PRESENT ? copy(baseline.bytes()) : null;
        var after = current.present() ? copy(current.bytes()) : null;
        var diff = UnifiedDiffBuilder.create(relativePath, kind, before, after, baseline.hash(), current.hash());
        return new ChangeEntry(path, kind, before, after, baseline.reversible(), diff);
    }

    private CurrentContent readCurrent(Path path) {
        // VFS can still hold the old Document after a shell deletion; disk existence is authoritative.
        if (!Files.isRegularFile(path)) return new CurrentContent(false, null, null);
        var documentBytes = readDocument(path);
        if (documentBytes != null) return content(documentBytes);
        return readDiskCurrent(path);
    }

    private CurrentContent readDiskCurrent(Path path) {
        if (!Files.isRegularFile(path)) return new CurrentContent(false, null, null);
        try {
            if (Files.size(path) <= MAX_BASELINE_BYTES) return content(Files.readAllBytes(path));
            return new CurrentContent(true, null, hash(path));
        } catch (IOException error) {
            return new CurrentContent(true, null, null);
        }
    }

    private byte[] readDocument(Path path) {
        if (project == null || ApplicationManager.getApplication() == null) return null;
        var file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file == null) return null;
        var manager = FileDocumentManager.getInstance();
        var document = manager.getCachedDocument(file);
        // Saved Documents can lag behind a CLI write. Only unsaved editor text overrides disk contents.
        if (document == null || !manager.isDocumentUnsaved(document)) return null;
        return ApplicationManager.getApplication().runReadAction(
            (Computable<byte[]>) () -> document.getText().getBytes(StandardCharsets.UTF_8));
    }

    private CurrentContent content(byte[] bytes) {
        return bytes.length <= MAX_BASELINE_BYTES
            ? new CurrentContent(true, copy(bytes), hash(bytes))
            : new CurrentContent(true, null, hash(bytes));
    }

    private void restore(Path path, Baseline baseline) throws IOException {
        if (!baseline.reversible()) throw new IOException(UiMessages.message("文件超过 5 MiB，未保存可撤销基线"));
        if (baseline.kind() == BaselineKind.ABSENT) {
            delete(path);
            return;
        }
        var parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        write(path, baseline.bytes());
    }

    private void write(Path path, byte[] content) throws IOException {
        if (project != null && ApplicationManager.getApplication() != null) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) {
                var manager = FileDocumentManager.getInstance();
                var document = manager.getCachedDocument(file);
                // Restore original bytes through VFS. Document.setText accepts LF only and saving it
                // would re-encode BOM / mixed separators using the current file's metadata.
                WriteAction.run(() -> {
                    if (!file.isWritable()) file.setWritable(true);
                    file.setBinaryContent(content);
                    if (document != null) manager.reloadFromDisk(document);
                });
                return;
            }
        }
        makeDiskWritable(path);
        Files.write(path, content);
    }

    private void makeDiskWritable(Path path) throws IOException {
        var attributes = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (attributes != null && Files.exists(path) && attributes.readAttributes().isReadOnly()) attributes.setReadOnly(false);
    }

    private void preserveFormat(Path path, byte[] before) {
        if (before == null || !preserveLineEndings.getAsBoolean() || normalizePath(path) == null
            || Files.isSymbolicLink(path) || !Files.isRegularFile(path) || readDocument(path) != null) return;
        try {
            byte[] after;
            try (var stream = Files.newInputStream(path)) { after = stream.readNBytes((int) MAX_BASELINE_BYTES + 1); }
            if (after.length > MAX_BASELINE_BYTES) return;
            var preserved = FileLineEndings.preserve(before, after);
            if (preserved == after || preserved.length > MAX_BASELINE_BYTES) return;
            // Do not replace an intervening edit or a path redirected by an external command.
            if (normalizePath(path) == null || Files.isSymbolicLink(path) || readDocument(path) != null) return;
            try (var stream = Files.newInputStream(path)) {
                if (!Arrays.equals(after, stream.readNBytes((int) MAX_BASELINE_BYTES + 1))) return;
            }
            Files.write(path, preserved);
            refresh(path);
        } catch (IOException error) {
            java.util.logging.Logger.getLogger(ConversationChangeTracker.class.getName())
                .warning(UiMessages.message("无法保持文件换行格式: ") + path + " (" + error.getMessage() + ")");
        }
    }

    private void delete(Path path) throws IOException {
        if (project != null && ApplicationManager.getApplication() != null) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) {
                final IOException[] failure = new IOException[1];
                WriteAction.run(() -> {
                    try {
                        if (!file.isWritable()) file.setWritable(true);
                        file.delete(this);
                    } catch (IOException error) {
                        failure[0] = error;
                    }
                });
                if (failure[0] != null) throw failure[0];
                return;
            }
        }
        makeDiskWritable(path);
        Files.deleteIfExists(path);
    }

    private void installListeners() {
        if (ApplicationManager.getApplication() == null) return;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (var event : events) {
                    var file = event.getFile();
                    // ChangeSet 只处理能够映射到本地工作区的 VFS 事件。
                    if (file == null || !file.isInLocalFileSystem()) continue;
                    refreshTracked(file.toNioPath());
                }
            }
        });
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                var file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null) refreshTracked(file.toNioPath());
            }
        }, this);
    }

    private synchronized void refreshTracked(Path changedPath) {
        var target = normalizePath(changedPath);
        if (target == null) return;
        for (var entry : baselinesBySession.entrySet()) {
            if (entry.getValue().containsKey(target)) fireChanged(entry.getKey());
        }
    }

    private void refresh(Path path) {
        if (ApplicationManager.getApplication() == null) return;
        LocalFileSystem.getInstance().refreshNioFiles(List.of(path), true, false, null);
    }

    private Path resolveReportedPath(String reportedPath) {
        return pathPolicy.resolve(reportedPath);
    }

    private Path normalizePath(Path path) {
        return pathPolicy.normalize(path);
    }

    private boolean matchesKind(ChangeEntry.Kind kind, boolean currentPresent) {
        // 删除补丁只能对应已不存在的文件。
        if (kind == ChangeEntry.Kind.DELETED) return !currentPresent;
        // 新增和修改补丁必须对应当前存在的文件。
        return currentPresent;
    }

    private LineStats lineStats(String diff) {
        var added = 0;
        var deleted = 0;
        var inHunk = false;
        for (var line : diff.split("\\R")) {
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk) continue;
            if (line.startsWith("+")) added++;
            if (line.startsWith("-")) deleted++;
        }
        return new LineStats(added, deleted);
    }

    private void fireChanged(String sessionId) {
        var update = new ChangeUpdate(sessionId, listSummaries(sessionId));
        listeners.forEach(listener -> listener.accept(update));
    }

    private String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }

    private static byte[] hash(byte[] content) {
        if (content == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] copy(byte[] content) {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    @Override
    public synchronized void dispose() {
        pendingWrites.clear();
        pendingPatches.clear();
        pendingCommands.clear();
        commandsBySession.clear();
        baselinesBySession.clear();
        listeners.clear();
    }
}
