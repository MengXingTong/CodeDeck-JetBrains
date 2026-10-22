package com.codexgui.changelist.vcs;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.vcsUtil.VcsUtil;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.settings.SidebarSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.perforce.perforce.PerforceContentRevision;
import org.jetbrains.idea.perforce.perforce.PerforceRunner;
import org.jetbrains.idea.perforce.perforce.PerforceSettings;
import org.jetbrains.idea.perforce.perforce.PerforcePhysicalConnectionParameters;
import org.jetbrains.idea.perforce.perforce.PerforcePhysicalConnectionParametersI;
import org.jetbrains.idea.perforce.perforce.connections.P4Connection;
import org.jetbrains.idea.perforce.perforce.connections.PerforceConnectionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native credentials/connection, scoped read-only preview, outside every IDE read lock. */
public final class PerforceUnopenedChanges implements UnopenedChanges, Disposable {
    private final Project project;
    private volatile List<Entry> entries = List.of();
    private volatile String status = "";
    private boolean running, forceAgain;
    private long lastAttempt;
    private final LiveChangesOverlay liveUpdates = new LiveChangesOverlay();
    private final PerforceLiveChanges live;
    private volatile ProgressIndicator fullIndicator;
    private volatile boolean disposed;

    public PerforceUnopenedChanges(Project project) {
        this.project = project;
        live = new PerforceLiveChanges(project, this, changes -> {
            liveUpdates.update(changes);
            status = "已自动检测本地变更";
            if (!disposed && !project.isDisposed()) project.getMessageBus().syncPublisher(CHANGED)
                    .pathsChanged(changes.stream().map(LiveChangesOverlay.Update::path).toList());
        }, message -> { status = message; publish(); });
        Disposer.register(this, live);
        project.getMessageBus().connect(this).subscribe(SidebarSettings.CHANGED, () -> {
            if (!enabled()) stopScanning();
        });
    }
    @Override public List<Entry> snapshot() { return liveUpdates.merge(entries); }
    @Override public String status() { return status; }
    @Override public void refreshPaths(List<Path> paths) { live.requestImmediate(paths); }
    @Override public void operationCompleted(FileVcsOperations.Result result) {
        if (disposed || project.isDisposed()) return;
        List<LiveChangesOverlay.Update> updates = new ArrayList<>();
        for (var update : result.updates()) {
            var entry = update.kind() == null || update.target().opened() ? null
                    : new Entry(update.path(), update.kind(), update.binary(), update.target());
            updates.add(new LiveChangesOverlay.Update(update.path(), entry, false));
        }
        liveUpdates.update(updates); publish();
    }

    @Override public void refresh(boolean force) {
        if (disposed || project.isDisposed()) return;
        if (!enabled()) { stopScanning(); return; }
        live.requestUnsaved();
        if (running) { forceAgain |= force; return; }
        long now = System.nanoTime();
        if (!force && lastAttempt != 0 && now - lastAttempt < TimeUnit.SECONDS.toNanos(60)) return;
        lastAttempt = now; running = true;
        long startedVersion = liveUpdates.version();
        status = "正在扫描 P4 本地变更…"; publish();
        new Task.Backgroundable(project, "扫描 Perforce 本地变更（只读）", true) {
            private List<Entry> result;
            private boolean applicable;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                fullIndicator = indicator;
                checkScanning(indicator);
                ProjectScope scope = ProjectScope.forBasePath(project.getBasePath());
                List<Path> roots = ReadAction.nonBlocking(() -> scanRoots(scope)).wrapProgress(indicator)
                        .expireWith(project).executeSynchronously();
                if (roots.isEmpty()) { result = List.of(); return; }
                applicable = true;
                var settings = PerforceSettings.getSettings(project);
                if (!settings.ENABLED) throw new IllegalStateException("Perforce 当前处于离线状态");
                var connections = PerforceConnectionManager.getInstance(project);
                if (!connections.isInitialized()) throw new IllegalStateException("Perforce 连接尚未就绪，请稍后刷新");
                var runner = PerforceRunner.getInstance(project);
                List<Entry> found = new ArrayList<>();
                Set<Path> scanned = new HashSet<>();
                for (Path root : roots) {
                    checkScanning(indicator);
                    var connection = connections.getConnectionForFile(root.toFile());
                    if (connection == null) throw new IllegalStateException("IDE 未配置此目录的 Perforce 连接：" + root);
                    try {
                        var files = PerforcePreview.syncedFiles(execute(settings, runner, connection,
                                PerforcePreview.haveCommand(root)), scope, indicator::checkCanceled);
                        // -x avoids Windows command-line limits and traversing generated/unversioned trees.
                        // Source/config batches go first so large asset scans cannot hide their results.
                        for (int start = 0; start < files.size(); start += 1024) {
                            checkScanning(indicator);
                            var batch = files.subList(start, Math.min(start + 1024, files.size()));
                            Path arguments = Files.createTempFile("vcs-sidebar-p4-preview-", ".txt");
                            try {
                                Files.write(arguments, batch.stream().map(PerforcePreview::encodePath).toList(), StandardCharsets.UTF_8);
                                checkScanning(indicator);
                                var output = execute(settings, runner, connection, PerforcePreview.command(arguments));
                                found.addAll(PerforcePreview.parse(output, scope, indicator::checkCanceled)
                                        .stream().map(entry -> target(entry, connection)).toList());
                            } finally { Files.deleteIfExists(arguments); }
                            scanned.addAll(batch);
                            publishPartial(found, scanned, List.copyOf(batch), startedVersion,
                                    start + batch.size(), files.size(), indicator);
                        }
                    } catch (com.intellij.openapi.vcs.VcsException | IOException error) {
                        throw new IllegalStateException(error.getMessage(), error);
                    }
                }
                indicator.checkCanceled();
                result = List.copyOf(found);
            }
            @Override public void onSuccess() {
                if (!enabled() || result == null) return;
                entries = result; liveUpdates.acknowledgeAll(startedVersion);
                status = applicable ? "P4 本地变更扫描 " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + " · " + entries.size() + " 个文件（过滤前）" : "";
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                status = "P4 扫描失败（保留上次结果）：" + error.getMessage();
            }
            @Override public void onCancel() { status = "P4 扫描已取消（保留上次结果）"; }
            @Override public void onFinished() {
                running = false; fullIndicator = null; lastAttempt = System.nanoTime();
                if (disposed || project.isDisposed()) return;
                if (!enabled()) {
                    forceAgain = false;
                    entries = List.of(); liveUpdates.clear(); status = "";
                }
                publish();
                if (forceAgain) { forceAgain = false; refresh(true); }
            }
        }.queue();
    }

    private boolean enabled() {
        return !disposed && !project.isDisposed() && project.getService(SidebarSettings.class).snapshot().scanUnopened;
    }

    private void checkScanning(ProgressIndicator indicator) {
        if (!enabled()) indicator.cancel();
        indicator.checkCanceled();
    }

    private void stopScanning() {
        forceAgain = false;
        var indicator = fullIndicator; if (indicator != null) indicator.cancel();
        live.stop();
        entries = List.of(); liveUpdates.clear(); status = ""; publish();
    }

    static PerforcePhysicalConnectionParametersI scanParameters(PerforcePhysicalConnectionParametersI source) {
        // Local UE asset hashing routinely exceeds the IDE's 20-second server timeout.
        // This per-command immutable copy has a no-op disable(), preserving the user's global integration.
        return new PerforcePhysicalConnectionParameters(source.getPathToExec(), source.getPathToIgnore(),
                source.getProject(), Math.max(source.getServerTimeout(), 15 * 60 * 1000), source.getCharsetName());
    }

    static String execute(PerforceSettings settings, PerforceRunner runner, P4Connection connection, String[] command)
            throws com.intellij.openapi.vcs.VcsException {
        var output = connection.runP4CommandLine(scanParameters(settings), command, null);
        try {
            var normalized = PerforcePreview.normalizedResult(output, "have".equals(command[command.length - 1]));
            runner.checkError(normalized, connection); return normalized.getStdout();
        }
        finally { output.cleanup(); }
    }

    private void publishPartial(List<Entry> found, Set<Path> scanned, List<Path> batch, long startedVersion,
                                int rootScanned, int rootTotal, ProgressIndicator indicator) {
        Map<Path, Entry> merged = new LinkedHashMap<>();
        for (Entry previous : entries) if (!scanned.contains(previous.path())) merged.put(previous.path(), previous);
        for (Entry entry : found) merged.put(entry.path(), entry);
        List<Entry> partial = List.copyOf(merged.values());
        String progress = "P4 本地变更扫描 · 已检查 " + scanned.size() + " 个文件 · 当前目录 " + rootScanned + " / " + rootTotal;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed() || indicator.isCanceled()
                    || !project.getService(SidebarSettings.class).snapshot().scanUnopened) return;
            entries = partial; liveUpdates.acknowledge(batch, startedVersion); status = progress; publish();
        });
    }

    private List<Path> scanRoots(ProjectScope scope) {
        if (scope.root() == null) return List.of();
        Set<Path> roots = new LinkedHashSet<>();
        for (var root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
            if (root.getVcs() == null || !"Perforce".equals(root.getVcs().getName()) || root.getPath() == null) continue;
            Path path = root.getPath().toNioPath().normalize();
            if (scope.root().startsWith(path)) roots.add(scope.root());
            else if (scope.contains(path)) roots.add(path);
        }
        return List.copyOf(roots);
    }

    Entry target(PerforcePreview.Entry entry, P4Connection connection) {
        var kind = entry.deleted() ? ChangedFile.Kind.DELETED : ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT;
        var target = NativeChanges.DiffTarget.lazy(project -> {
            ProgressManager.checkCanceled();
            FilePath path = VcsUtil.getFilePath(entry.path(), false);
            var virtual = path.getVirtualFile();
            var current = virtual == null ? null : ChangeListManager.getInstance(project).getChange(virtual);
            if (current != null) return current;
            if (!entry.deleted() && (virtual == null || !virtual.isValid())) {
                throw new IOException("文件已不存在，请刷新变更列表");
            }
            // workRev is the synced have revision, even when the server HEAD has moved ahead.
            var before = new PreviewRevision(project, connection, entry.depotPath(), path, entry.revision());
            return new Change(before, entry.deleted() ? null : CurrentContentRevision.create(path));
        });
        return new Entry(entry.path(), kind, entry.binary(), target);
    }

    private void publish() {
        if (!disposed && !project.isDisposed()) project.getMessageBus().syncPublisher(CHANGED).changed();
    }

    @Override public void dispose() {
        disposed = true;
        var indicator = fullIndicator; if (indicator != null) indicator.cancel();
    }

    private static final class PreviewRevision extends PerforceContentRevision {
        PreviewRevision(Project project, P4Connection connection, String depot, FilePath file, long revision) {
            super(project, connection, depot, file, revision, "#" + revision);
        }
    }
}
