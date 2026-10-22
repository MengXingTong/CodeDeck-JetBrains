package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.codexgui.changelist.vcs.NativeDiffOpener;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class AiSessionStore implements Disposable {
    public interface Listener { void changed(String key); }
    public static final Topic<Listener> CHANGED = Topic.create("AI Diff annotations changed", Listener.class);
    static final class Session {
        AiResponse annotations;
        AiTaskState task;
    }
    record FileTask(AiTaskState task, AiOptions options) {}
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Path, FileTask> activeFiles = new LinkedHashMap<>();
    private final Set<AiTaskState> tasks = new LinkedHashSet<>();
    private AiTaskState activeBatch;
    private AiBatchState batch;
    private volatile boolean disposed;

    /** Model/UI state is accessed on the EDT; workers only read cancellation flags. */
    Session session(String key) {
        var session = sessions.computeIfAbsent(key, ignored -> new Session());
        while (sessions.size() > 64) {
            var oldest = sessions.entrySet().stream()
                    .filter(e -> !e.getKey().equals(key) && !isActive(e.getValue().task)).findFirst().orElse(null);
            if (oldest == null) break; // Active files may temporarily exceed the completed-result cache limit.
            sessions.remove(oldest.getKey());
        }
        return session;
    }
    Session displayedSession(AiDiffSnapshot snapshot, AiOptions options) {
        var current = session(options.cacheKey(snapshot));
        if (current.annotations != null) return current;
        // Another Diff may change the shared preferences while this file is still generating.
        // Keep its completed explanation visible; cache reuse for new requests still requires exact options.
        String prefix = snapshot.fingerprint() + ":";
        for (var entry : sessions.reversed().entrySet())
            if (entry.getKey().startsWith(prefix) && entry.getValue().annotations != null) return entry.getValue();
        return current;
    }
    synchronized boolean tryBegin(Path file, AiTaskState task, AiOptions options) {
        Path key = file.toAbsolutePath().normalize();
        if (disposed || task.canceled() || activeFiles.containsKey(key)) return false;
        activeFiles.put(key, new FileTask(task, options)); tasks.add(task); return true;
    }
    synchronized boolean tryBeginBatch(AiTaskState task) {
        if (disposed || activeBatch != null) return false;
        activeBatch = task; tasks.add(task); return true;
    }
    synchronized FileTask activeFile(Path file) { return activeFiles.get(file.toAbsolutePath().normalize()); }
    synchronized AiTaskState activeBatchTask() { return activeBatch; }
    private synchronized boolean isActive(AiTaskState task) { return tasks.contains(task); }
    synchronized void releaseFile(Path file, AiTaskState task) {
        Path key = file.toAbsolutePath().normalize();
        var current = activeFiles.get(key);
        if (current != null && current.task() == task) activeFiles.remove(key);
    }
    synchronized void finished(AiTaskState task) {
        activeFiles.values().removeIf(current -> current.task() == task);
        tasks.remove(task);
        if (activeBatch == task) activeBatch = null;
    }
    synchronized boolean cancel(AiTaskState task) {
        if (!tasks.contains(task)) return false;
        task.cancel(); return true;
    }
    public AiBatchState lastBatch() { return batch; }
    AiBatchState batchFor(List<AiBatchState.Source> sources) {
        if (batch == null || (activeBatchTask() == null && !batch.sameScope(sources) && !sources.isEmpty()))
            batch = new AiBatchState(sources);
        else batch.refreshSources(sources);
        return batch;
    }
    private void changed(Project project, String key) {
        if (!disposed && !project.isDisposed()) project.getMessageBus().syncPublisher(CHANGED).changed(key);
    }
    private void annotated(Project project, String key, AiResponse result) {
        session(key).annotations = result; changed(project, key);
    }
    void cancel(Project project, AiTaskState task) {
        if (cancel(task)) changed(project, "");
    }
    private boolean stopped(Project project, AiTaskState task, ProgressIndicator indicator) {
        return task.canceled() || disposed || project.isDisposed() || indicator.isCanceled();
    }

    boolean startRequest(Project project, ContentDiffRequest request, AiDiffSnapshot snapshot, AiOptions options) {
        var task = new AiTaskState(UiMessages.message("正在生成 AI 注释 · ") + snapshot.file());
        if (!tryBegin(Path.of(project.getBasePath()).resolve(snapshot.file()), task, options)) return false;
        String key = options.cacheKey(snapshot);
        var session = session(key); session.task = task;
        changed(project, key);
        new Task.Backgroundable(project, UiMessages.message("AI Diff 注释 · ") + snapshot.file(), true) {
            private AiResponse response;
            private CodexCli.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                task.onCancel(indicator::cancel);
                try {
                    indicator.checkCanceled();
                    if (snapshot.changes().isEmpty()) throw new IllegalArgumentException(UiMessages.message("当前文件没有内容差异，无需 AI 注释"));
                    result = CodexCli.run(options, Path.of(project.getBasePath()), AiAnnotationPrompt.create(snapshot),
                            () -> stopped(project, task, indicator));
                    response = AiResponse.parse(result.json(), snapshot);
                } catch (ProcessCanceledException e) { throw e; }
                catch (CancellationException e) { throw new ProcessCanceledException(e); }
                catch (Exception e) { throw new AiFailure(e.getMessage(), e); }
            }
            @Override public void onSuccess() {
                if (task.canceled() || disposed || project.isDisposed() || response == null) return;
                if (!AiNativeSnapshot.matches(snapshot, request)) {
                    task.finish(AiTaskState.Outcome.STALE, UiMessages.message("代码已变化，已丢弃旧版本结果；可点击 AI 注释重新生成")); return;
                }
                session.annotations = response;
                task.finish(AiTaskState.Outcome.SUCCEEDED, UiMessages.message("完成 · ") + result.usageText());
            }
            @Override public void onThrowable(@NotNull Throwable error) { task.finish(AiTaskState.Outcome.FAILED, error.getMessage()); }
            @Override public void onCancel() { task.cancel(); }
            @Override public void onFinished() {
                if (task.running()) task.finish(AiTaskState.Outcome.CANCELED, UiMessages.message("已取消；不会自动继续或重试"));
                finished(task); changed(project, key);
            }
        }.queue();
        return true;
    }

    boolean startBatch(Project project, AiBatchState state, AiOptions options) {
        var task = new AiTaskState(UiMessages.message("正在准备批量注释…"));
        if (!tryBeginBatch(task)) return false;
        batch = state; state.start(task, options); changed(project, "");
        new Task.Backgroundable(project, UiMessages.message("批量生成 AI Diff 注释"), true) {
            private int completed;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                task.onCancel(indicator::cancel);
                for (int index = 0; index < state.sources().size(); index++) {
                    if (stopped(project, task, indicator)) break;
                    var source = state.sources().get(index); int row = index;
                    indicator.setFraction((double) index / state.sources().size()); indicator.setText(source.path().toString());
                    var claimed = new AtomicBoolean();
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        if (!stopped(project, task, indicator)) {
                            claimed.set(tryBegin(source.path(), task, options));
                            if (claimed.get()) changed(project, "");
                        }
                    }, ModalityState.any());
                    if (!claimed.get()) {
                        if (stopped(project, task, indicator)) break;
                        update(row, UiMessages.message("已有任务，已跳过"), UiMessages.message("此文件已在其他 Diff 中生成，未重复请求 AI")); continue;
                    }
                    update(row, UiMessages.message("处理中"), UiMessages.message("正在处理 ") + (index + 1) + "/" + state.sources().size());
                    try {
                        var request = NativeDiffOpener.prepare(project, source.target(), indicator, () -> stopped(project, task, indicator));
                        var snapshot = ReadAction.nonBlocking(() -> AiNativeSnapshot.read(Path.of(project.getBasePath()), source.path(), request))
                                .expireWith(project).wrapProgress(indicator).executeSynchronously();
                        if (snapshot.changes().isEmpty()) { update(row, UiMessages.message("无内容差异"), UiMessages.message("已跳过无需解释的文件")); continue; }
                        var cached = new AtomicReference<AiResponse>();
                        ApplicationManager.getApplication().invokeAndWait(() -> cached.set(session(options.cacheKey(snapshot)).annotations), ModalityState.any());
                        if (cached.get() != null) { update(row, UiMessages.message("缓存"), UiMessages.message("复用已有注释，未请求 AI")); continue; }
                        if (stopped(project, task, indicator)) break;
                        var result = CodexCli.run(options, Path.of(project.getBasePath()), AiAnnotationPrompt.create(snapshot),
                                () -> stopped(project, task, indicator));
                        var response = AiResponse.parse(result.json(), snapshot);
                        if (stopped(project, task, indicator)) break;
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            if (stopped(project, task, indicator)) return;
                            if (request instanceof ContentDiffRequest contents && AiNativeSnapshot.matches(snapshot, contents)) {
                                annotated(project, options.cacheKey(snapshot), response); completed++;
                                state.row(row, UiMessages.message("完成")); task.progress(UiMessages.message("已完成 ") + completed + UiMessages.message(" 个文件 · ") + result.usageText());
                            } else { state.row(row, UiMessages.message("已过期")); task.progress(UiMessages.message("代码已变化，已丢弃旧版本结果")); }
                            changed(project, "");
                        }, ModalityState.any());
                    } catch (ProcessCanceledException e) { throw e; }
                    catch (CancellationException e) { throw new ProcessCanceledException(e); }
                    catch (Exception e) {
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            state.row(row, UiMessages.message("失败")); task.finish(AiTaskState.Outcome.FAILED, e.getMessage() + UiMessages.message("；队列已停止，未重试"));
                        }, ModalityState.any());
                        return;
                    } finally {
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            releaseFile(source.path(), task); changed(project, "");
                        }, ModalityState.any());
                    }
                }
            }
            private void update(int row, String value, String message) {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    if (!disposed && task.running()) { state.row(row, value); task.progress(message); changed(project, ""); }
                }, ModalityState.any());
            }
            @Override public void onSuccess() {
                if (task.running()) task.finish(task.canceled() ? AiTaskState.Outcome.CANCELED : AiTaskState.Outcome.SUCCEEDED,
                        UiMessages.message("队列完成 · 新生成 ") + completed + UiMessages.message(" 个文件；打开 Diff 查看注释"));
            }
            @Override public void onThrowable(@NotNull Throwable error) { task.finish(AiTaskState.Outcome.FAILED, error.getMessage()); }
            @Override public void onCancel() { task.cancel(); }
            @Override public void onFinished() {
                if (task.running()) task.finish(AiTaskState.Outcome.CANCELED, UiMessages.message("队列已停止"));
                state.finishRows(); finished(task); changed(project, "");
            }
        }.queue();
        return true;
    }
    @Override public void dispose() {
        List<AiTaskState> running;
        synchronized (this) { disposed = true; running = List.copyOf(tasks); }
        running.forEach(AiTaskState::cancel);
    }
    private static final class AiFailure extends RuntimeException { AiFailure(String message, Throwable cause) { super(message, cause); } }
}
