package com.codexgui.changelist.vcs;

import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.codexgui.changelist.core.TextGuard;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class NativeDiffOpener {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private NativeDiffOpener() {}

    public static void open(Project project, Path path, NativeChanges.DiffTarget target, BooleanSupplier expired,
                            Consumer<String> status, Runnable finished) {
        if (NativeDiffTabs.focusExisting(project, path)) {
            status.accept("已切换到此文件的 VCS Diff"); finished.run(); return;
        }
        status.accept("正在读取 VCS Diff…");
        new Task.Backgroundable(project, "读取 VCS Diff", true) {
            private DiffRequest request;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                if (expired.getAsBoolean()) return;
                try {
                    request = prepare(project, target, indicator, expired);
                } catch (com.intellij.openapi.progress.ProcessCanceledException e) { throw e; }
                catch (Exception e) { throw new DiffFailure(e.getMessage(), e); }
            }
            @Override public void onSuccess() {
                if (!expired.getAsBoolean() && request != null) {
                    NativeDiffTabs.show(project, path, request);
                    status.accept("已打开 VCS Diff");
                }
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                if (!expired.getAsBoolean()) status.accept("Diff 失败：" + error.getMessage());
            }
            @Override public void onCancel() { if (!expired.getAsBoolean()) status.accept("已取消读取 diff"); }
            @Override public void onFinished() { finished.run(); }
        }.queue();
    }

    /** Shared by explicitly opened Diff tabs and explicitly started AI batches; never called by list refresh. */
    public static DiffRequest prepare(Project project, NativeChanges.DiffTarget target, ProgressIndicator indicator,
                                      BooleanSupplier expired) throws Exception {
        indicator.checkCanceled();
        Change change = target.resolve(project);
        if (NativeChanges.isBinary(change)) throw new IOException("已跳过二进制文件");
        var producer = ChangeDiffRequestProducer.create(project, change);
        if (producer == null) throw new IOException("IDE 无法为此文件提供 diff");
        if (change.getAfterRevision() != null) {
            Path local = Path.of(change.getAfterRevision().getFile().getPath());
            if (Files.isRegularFile(local)) {
                if (Files.size(local) > MAX_BYTES) throw new IOException("文件超过 20 MiB，已跳过 diff");
                try (var stream = Files.newInputStream(local)) {
                    if (!TextGuard.isProbablyText(stream.readNBytes(8192))) throw new IOException("文件内容为二进制，已跳过 diff");
                }
            }
        }
        indicator.checkCanceled();
        // The native producer controls revisions, encodings, partial changes and unsaved documents.
        DiffRequest candidate = producer.process(new UserDataHolderBase(), indicator);
        boolean textOnly = ReadAction.nonBlocking(() -> textOnly(candidate)).wrapProgress(indicator)
                .expireWhen(expired).executeSynchronously();
        if (!textOnly) throw new IOException("此文件无法作为文本比较，已跳过 diff");
        return candidate;
    }

    private static boolean textOnly(DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contents)) return false;
        return contents.getContents().stream().allMatch(content -> {
            if (content instanceof EmptyContent) return true;
            if (!(content instanceof DocumentContent document)) return false;
            CharSequence chars = document.getDocument().getCharsSequence();
            if (chars.length() > MAX_BYTES) return false;
            return TextGuard.isProbablyText(chars.subSequence(0, Math.min(chars.length(), 8192))
                    .toString().getBytes(StandardCharsets.UTF_8));
        });
    }

    private static final class DiffFailure extends RuntimeException {
        DiffFailure(String message, Throwable cause) { super(message, cause); }
    }
}
