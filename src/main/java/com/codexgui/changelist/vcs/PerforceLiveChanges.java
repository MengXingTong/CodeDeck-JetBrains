package com.codexgui.changelist.vcs;

import com.codexgui.i18n.UiMessages;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.Alarm;
import com.intellij.vcsUtil.VcsUtil;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.settings.SidebarSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.perforce.perforce.PerforceContentRevision;
import org.jetbrains.idea.perforce.perforce.PerforceRunner;
import org.jetbrains.idea.perforce.perforce.PerforceSettings;
import org.jetbrains.idea.perforce.perforce.connections.PerforceConnectionManager;
import org.jetbrains.idea.perforce.perforce.connections.P4Connection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Small, debounced checks run independently of a slow full scan; checkout is never a prerequisite. */
final class PerforceLiveChanges implements Disposable {
    private final Project project;
    private final PerforceUnopenedChanges owner;
    private final Consumer<List<LiveChangesOverlay.Update>> updated;
    private final Consumer<String> failed;
    private final Alarm alarm = new Alarm(this);
    private final Set<Path> pending = new LinkedHashSet<>();
    private final Set<Path> urgent = new LinkedHashSet<>();
    private final Set<Path> immediate = new LinkedHashSet<>();
    private final Map<Baseline, String> baselines = new LinkedHashMap<>(); // Worker thread only, bounded below.
    private volatile ProgressIndicator active;
    private volatile boolean preempt;
    private volatile boolean disposed;
    private boolean running, activeUrgent;
    private record Baseline(Path path, String depot, long revision) {}
    private record Unsaved(Document document, long stamp, String text) {}

    PerforceLiveChanges(Project project, PerforceUnopenedChanges owner,
                        Consumer<List<LiveChangesOverlay.Update>> updated, Consumer<String> failed) {
        this.project = project; this.owner = owner; this.updated = updated; this.failed = failed;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                var file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null && file.isInLocalFileSystem()) request(List.of(file.toNioPath()), true);
            }
        }, this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES,
                new BulkFileListener() {
                    @Override public void after(@NotNull List<? extends VFileEvent> events) {
                        Set<Path> changed = new HashSet<>();
                        for (var event : events) {
                            if (!"file".equals(event.getFileSystem().getProtocol())) continue;
                            if (event instanceof VFileCreateEvent create && create.isDirectory()) continue;
                            if (event instanceof VFileDeleteEvent delete && delete.getFile().isDirectory()) {
                                var scope = ProjectScope.forBasePath(project.getBasePath());
                                if (enabled() && scope.contains(event.getPath())) ApplicationManager.getApplication().invokeLater(() -> owner.refresh(true));
                                continue;
                            }
                            // Contents, creation, deletion and both sides of a rename/move matter.
                            // Writable/read-only property changes do not imply a content change.
                            if (event instanceof VFileContentChangeEvent || event instanceof VFileCreateEvent
                                    || event instanceof VFileDeleteEvent) add(changed, event.getPath());
                            else if (event instanceof VFileMoveEvent move) {
                                add(changed, move.getOldPath()); add(changed, move.getNewPath());
                            } else if (event instanceof VFilePropertyChangeEvent property && property.isRename()) {
                                add(changed, property.getOldPath()); add(changed, property.getNewPath());
                            }
                        }
                        request(changed);
                    }
                });
    }

    private static void add(Set<Path> paths, String path) {
        try { paths.add(Path.of(path)); } catch (IllegalArgumentException ignored) { }
    }

    private boolean enabled() {
        return !disposed && !project.isDisposed() && project.getService(SidebarSettings.class).snapshot().scanUnopened;
    }

    void request(Collection<Path> paths) {
        request(paths, false);
    }

    /** Explicit file operations outrank queued disk scans and bypass the editor debounce delay. */
    void requestImmediate(Collection<Path> paths) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            List<Path> copy = List.copyOf(paths);
            ApplicationManager.getApplication().invokeLater(() -> requestImmediate(copy)); return;
        }
        request(paths, true);
        if (!enabled()) return;
        for (Path path : paths) if (pending.contains(path.normalize())) immediate.add(path.normalize());
        if (immediate.isEmpty()) return;
        if (running) {
            preempt = true;
            var indicator = active; if (indicator != null) indicator.cancel();
        } else {
            alarm.cancelAllRequests(); alarm.addRequest(this::checkPending, 0);
        }
    }

    private void request(Collection<Path> paths, boolean prioritize) {
        if (disposed || project.isDisposed() || paths.isEmpty()) return;
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            List<Path> copy = List.copyOf(paths);
            ApplicationManager.getApplication().invokeLater(() -> request(copy, prioritize)); return;
        }
        if (!enabled()) return;
        var scope = ProjectScope.forBasePath(project.getBasePath());
        for (Path path : paths) if (scope.contains(path)) {
            pending.add(path.normalize());
            if (prioritize) urgent.add(path.normalize());
        }
        if (pending.isEmpty()) return;
        if (running) {
            if (prioritize && !activeUrgent) {
                preempt = true;
                var indicator = active; if (indicator != null) indicator.cancel();
            }
            return;
        }
        alarm.cancelAllRequests(); alarm.addRequest(this::checkPending, immediate.isEmpty() ? 400 : 0);
    }

    void requestUnsaved() {
        if (!enabled()) return;
        List<Path> files = new ArrayList<>();
        var manager = FileDocumentManager.getInstance();
        for (Document document : manager.getUnsavedDocuments()) {
            var file = manager.getFile(document);
            if (file != null && file.isInLocalFileSystem()) files.add(file.toNioPath());
        }
        request(files, true);
    }

    private void checkPending() {
        if (!enabled() || running || pending.isEmpty()) return;
        boolean immediateBatch = !immediate.isEmpty();
        List<Path> paths = nextBatch(immediate, urgent, pending);
        activeUrgent = paths.stream().anyMatch(urgent::contains);
        pending.removeAll(paths); urgent.removeAll(paths); immediate.removeAll(paths);
        preempt = false; running = true;
        new Task.Backgroundable(project, UiMessages.message("检测本地文件变更"), true) {
            private final List<LiveChangesOverlay.Update> results = new ArrayList<>();
            private Map<Path, Unsaved> documents = Map.of();
            @Override public void run(@NotNull ProgressIndicator indicator) {
                active = indicator;
                if (preempt) indicator.cancel();
                indicator.checkCanceled();
                if (!enabled()) return;
                var scope = ProjectScope.forBasePath(project.getBasePath());
                var ignores = project.getService(SidebarSettings.class).snapshot().ignoreRules(scope);
                var vcs = ProjectLevelVcsManager.getInstance(project);
                var candidates = ReadAction.nonBlocking(() -> paths.stream().filter(path -> {
                    if (ignores.isIgnored(path)) return false;
                    var mapped = vcs.getVcsFor(VcsUtil.getFilePath(path, false));
                    return mapped != null && "Perforce".equals(mapped.getName());
                }).toList()).wrapProgress(indicator).expireWith(project).executeSynchronously();
                documents = ReadAction.nonBlocking(() -> unsaved(candidates)).wrapProgress(indicator)
                        .expireWith(project).executeSynchronously();
                var settings = PerforceSettings.getSettings(project);
                var runner = PerforceRunner.getInstance(project);
                var connections = PerforceConnectionManager.getInstance(project);
                if (!settings.ENABLED || !connections.isInitialized()) return;
                Map<P4Connection, List<Path>> groups = new LinkedHashMap<>();
                for (Path path : candidates) {
                    var connection = connections.getConnectionForFile(path.toFile());
                    if (connection != null) groups.computeIfAbsent(connection, key -> new ArrayList<>()).add(path);
                }
                // Bound each check and batch disk events, so generated files cannot delay an editor change.
                for (var group : groups.entrySet()) {
                    checkScanning(indicator);
                    var connection = group.getKey();
                    Path arguments = null;
                    try {
                        arguments = Files.createTempFile("vcs-sidebar-p4-live-", ".txt");
                        Files.write(arguments, group.getValue().stream().map(PerforcePreview::encodePath).toList(), StandardCharsets.UTF_8);
                        Map<Path, PerforcePreview.Entry> have = new HashMap<>();
                        checkScanning(indicator);
                        for (var entry : PerforcePreview.haveEntries(PerforceUnopenedChanges.execute(settings, runner,
                                connection, PerforcePreview.haveFilesCommand(arguments)), scope, indicator::checkCanceled)) {
                            have.put(entry.path(), entry);
                        }
                        Map<Path, PerforcePreview.Entry> diskChanges = new HashMap<>();
                        var saved = have.keySet().stream().filter(path -> !documents.containsKey(path)).toList();
                        if (!saved.isEmpty()) {
                            Files.write(arguments, saved.stream().map(PerforcePreview::encodePath).toList(), StandardCharsets.UTF_8);
                            checkScanning(indicator);
                            var output = PerforceUnopenedChanges.execute(settings, runner, connection, PerforcePreview.command(arguments));
                            for (var entry : PerforcePreview.parse(output, scope, indicator::checkCanceled)) diskChanges.put(entry.path(), entry);
                        }
                        for (Path path : group.getValue()) {
                            indicator.checkCanceled();
                            Unsaved document = documents.get(path);
                            UnopenedChanges.Entry entry = null;
                            if (have.containsKey(path)) {
                                var baseline = have.get(path);
                                if (document != null) {
                                    Baseline key = new Baseline(path, baseline.depotPath(), baseline.revision());
                                    String before = baselines.get(key);
                                    if (before == null) {
                                        checkScanning(indicator);
                                        before = new PerforceContentRevision(project, VcsUtil.getFilePath(path, false), baseline.revision()).getContent();
                                        if (before == null) throw new IllegalStateException(UiMessages.message("无法读取工作区基准版本：") + path);
                                        // Cache only small editor baselines, never the entire project's contents.
                                        if (before.length() <= 1024 * 1024) {
                                            if (baselines.size() >= 16) baselines.remove(baselines.keySet().iterator().next());
                                            baselines.put(key, before);
                                        }
                                    }
                                    if (documentDiffers(document.text(), before)) entry = owner.target(baseline, connection);
                                } else {
                                    var changed = diskChanges.get(path);
                                    if (changed != null) entry = owner.target(changed, connection);
                                }
                            } else if (Files.isRegularFile(path)) {
                                // Native VCS status can lag a just-created file; show it immediately as unversioned.
                                var filePath = VcsUtil.getFilePath(path, false);
                                boolean ignored = ReadAction.nonBlocking(() -> {
                                    var file = filePath.getVirtualFile();
                                    return file != null && com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).isIgnoredFile(file);
                                }).wrapProgress(indicator).expireWith(project).executeSynchronously();
                                if (!ignored) entry = new UnopenedChanges.Entry(path, ChangedFile.Kind.UNVERSIONED, false,
                                        new NativeChanges.DiffTarget(null, filePath));
                            }
                            results.add(new LiveChangesOverlay.Update(path, entry, document != null));
                        }
                    } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) { throw canceled; }
                    catch (Exception error) { throw new IllegalStateException(error.getMessage(), error); }
                    finally {
                        if (arguments != null) try { Files.deleteIfExists(arguments); } catch (java.io.IOException ignored) { }
                    }
                }
            }
            @Override public void onSuccess() {
                if (!enabled()) return;
                List<LiveChangesOverlay.Update> current = new ArrayList<>();
                for (var result : results) {
                    var document = documents.get(result.path());
                    boolean stale = pending.contains(result.path()) || (document != null
                            && (document.document().getModificationStamp() != document.stamp()
                            || !FileDocumentManager.getInstance().isDocumentUnsaved(document.document())));
                    if (stale) {
                        pending.add(result.path());
                        if (immediateBatch) immediate.add(result.path());
                    } else current.add(result);
                }
                if (!current.isEmpty()) updated.accept(current);
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                if (enabled() && preempt) { requeue(); return; }
                if (enabled()) failed.accept(UiMessages.message("自动检测本地修改失败（保留上次结果）：") + error.getMessage());
            }
            @Override public void onCancel() {
                if (enabled() && preempt) requeue();
            }
            private void requeue() {
                pending.addAll(paths);
                if (immediateBatch) immediate.addAll(paths);
            }
            @Override public void onFinished() {
                active = null; activeUrgent = false; preempt = false; running = false;
                if (enabled() && !pending.isEmpty()) alarm.addRequest(PerforceLiveChanges.this::checkPending,
                        !immediate.isEmpty() ? 0 : urgent.isEmpty() ? 100 : 400);
            }
        }.queue();
    }

    /** Explicit file commands get a separate batch; unrelated editor/disk events keep their own queue. */
    static List<Path> nextBatch(Collection<Path> immediate, Collection<Path> urgent, Collection<Path> pending) {
        if (!immediate.isEmpty()) return immediate.stream().distinct().limit(64).toList();
        LinkedHashSet<Path> chosen = new LinkedHashSet<>(urgent);
        chosen.addAll(pending);
        return chosen.stream().limit(64).toList();
    }

    private Map<Path, Unsaved> unsaved(List<Path> candidates) {
        Set<Path> requested = new HashSet<>(candidates);
        Map<Path, Unsaved> result = new HashMap<>();
        var manager = FileDocumentManager.getInstance();
        for (Document document : manager.getUnsavedDocuments()) {
            var file = manager.getFile(document);
            if (file != null && file.isInLocalFileSystem() && requested.contains(file.toNioPath())) {
                result.put(file.toNioPath(), new Unsaved(document, document.getModificationStamp(), document.getText()));
            }
        }
        return result;
    }

    static boolean documentDiffers(String current, String baseline) {
        return !current.replace("\r\n", "\n").replace('\r', '\n')
                .equals(baseline.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private void checkScanning(ProgressIndicator indicator) {
        if (!enabled()) indicator.cancel();
        indicator.checkCanceled();
    }

    void stop() {
        alarm.cancelAllRequests();
        preempt = false;
        pending.clear(); urgent.clear(); immediate.clear();
        var indicator = active; if (indicator != null) indicator.cancel();
    }

    @Override public void dispose() {
        disposed = true;
        stop();
    }
}
