package com.codexgui.changelist.ui;

import com.codexgui.i18n.UiMessages;

import com.intellij.history.LocalHistory;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.ui.RollbackChangesDialog;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.vcsUtil.VcsUtil;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.vcs.FileVcsOperations;
import com.codexgui.changelist.vcs.NativeChanges;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class NodeVcsActions {
    private NodeVcsActions() {}
    private record SavedDocument(Document document, long stamp) {}

    static boolean available(Project project, FileVcsOperations.Operation operation, ChangedFile file,
                             NativeChanges.DiffTarget target) {
        if (!ProjectScope.forBasePath(project.getBasePath()).contains(file.path())) return false;
        var vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(VcsUtil.getFilePath(file.path(), false));
        boolean perforce = vcs != null && "Perforce".equals(vcs.getName())
                && project.getService(FileVcsOperations.class) != null;
        return FileVcsOperations.available(operation, file, target, perforce,
                vcs != null && vcs.getRollbackEnvironment() != null);
    }

    static List<Path> paths(ProjectScope scope, ChangedFile file, NativeChanges.DiffTarget target) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(file.path().normalize());
        if (target.change() != null) {
            var before = target.change().getBeforeRevision();
            var after = target.change().getAfterRevision();
            if (before != null) paths.add(Path.of(before.getFile().getPath()).normalize());
            if (after != null) paths.add(Path.of(after.getFile().getPath()).normalize());
        }
        if (paths.stream().anyMatch(path -> !scope.contains(path))) {
            throw new IllegalArgumentException(UiMessages.message("已跳过项目目录外的文件或移动来源"));
        }
        return List.copyOf(paths);
    }

    static void perform(Project project, FileVcsOperations.Operation operation, ChangedFile file,
                        NativeChanges.DiffTarget target, BooleanSupplier expired, Consumer<String> status,
                        BiConsumer<List<Path>, FileVcsOperations.Result> changed, Runnable done) {
        if (expired.getAsBoolean() || !available(project, operation, file, target)) { done.run(); return; }
        final List<Path> paths;
        try { paths = paths(ProjectScope.forBasePath(project.getBasePath()), file, target); }
        catch (IllegalArgumentException error) { status.accept(error.getMessage()); done.run(); return; }
        var vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(VcsUtil.getFilePath(file.path(), false));
        if (vcs == null || !"Perforce".equals(vcs.getName())) {
            try { RollbackChangesDialog.rollbackChanges(project, List.of(target.change())); }
            finally { done.run(); }
            return;
        }
        List<SavedDocument> saved = new ArrayList<>();
        if (operation == FileVcsOperations.Operation.REVERT) {
            String detail = file.kind() == ChangedFile.Kind.ADDED
                    ? UiMessages.message("撤销新增登记，保留本地文件。") : UiMessages.message("将文件恢复到工作区基准版本，丢弃本地修改（含未保存内容）。");
            if (paths.size() > 1) detail += UiMessages.message("\n移动或重命名的两个路径会一并还原。");
            if (Messages.showOkCancelDialog(project, detail + "\n\n"
                            + String.join("\n", paths.stream().map(Path::toString).toList()), operation.label(),
                    UiMessages.message("撤销修改"), UiMessages.message("取消"), Messages.getWarningIcon()) != Messages.OK) { done.run(); return; }
            // Save only these documents, so Local History also contains their unsaved edits.
            var documents = FileDocumentManager.getInstance();
            for (Path path : paths) {
                var virtual = LocalFileSystem.getInstance().findFileByNioFile(path);
                var document = virtual == null ? null : documents.getCachedDocument(virtual);
                if (document == null) continue;
                if (documents.isDocumentUnsaved(document)) documents.saveDocument(document);
                if (documents.isDocumentUnsaved(document)) {
                    status.accept(UiMessages.message("无法保存当前文件，已取消撤销修改")); done.run(); return;
                }
                saved.add(new SavedDocument(document, document.getModificationStamp()));
            }
            LocalHistory.getInstance().putSystemLabel(project, UiMessages.message("撤销修改前：") + file.path().getFileName());
        }
        status.accept(UiMessages.message("正在") + operation.label() + "：" + file.path().getFileName());
        new Task.Backgroundable(project, operation.label(), false) {
            private FileVcsOperations.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                if (expired.getAsBoolean()) return;
                var history = operation == FileVcsOperations.Operation.REVERT
                        ? LocalHistory.getInstance().startAction(UiMessages.message("撤销修改")) : null;
                try { result = project.getService(FileVcsOperations.class).perform(operation, paths, () -> {
                    if (expired.getAsBoolean()) throw new com.intellij.openapi.progress.ProcessCanceledException();
                    if (ReadAction.nonBlocking(() -> saved.stream().anyMatch(item ->
                            item.document().getModificationStamp() != item.stamp()))
                            .expireWith(project).executeSynchronously()) {
                        throw new IllegalStateException(UiMessages.message("等待服务器期间文件又有新编辑，已取消撤销修改，请重试"));
                    }
                }); }
                catch (com.intellij.openapi.vcs.VcsException error) { throw new IllegalStateException(error.getMessage(), error); }
                finally {
                    // Never wait on VFS refresh or enumerate a large parent directory after a file command.
                    // Undo needs content refresh; checkout/cancel only need the selected files' attributes.
                    LocalFileSystem.getInstance().refreshNioFiles(paths, true, false,
                            history == null ? null : history::finish);
                }
            }
            @Override public void onSuccess() {
                if (!expired.getAsBoolean()) status.accept(operation == FileVcsOperations.Operation.CANCEL_CHECKOUT
                        ? UiMessages.message("已取消迁出，保留本地修改") : UiMessages.message("已") + operation.label());
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                if (!expired.getAsBoolean()) {
                    status.accept(operation.label() + UiMessages.message("失败：") + error.getMessage());
                    Messages.showErrorDialog(project, error.getMessage(), operation.label() + UiMessages.message("失败"));
                }
            }
            @Override public void onFinished() {
                done.run();
                if (!expired.getAsBoolean()) changed.accept(paths, result);
            }
        }.queue();
    }
}
