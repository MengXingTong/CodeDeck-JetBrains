package com.codexgui.ui;

import com.codexgui.i18n.UiMessages;

import com.codexgui.service.ConversationChangeTracker;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.editor.DiffEditorTabFilesManager;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class ChangeReviewCoordinator implements Disposable {
    private final Project project;
    private final ConversationChangeTracker changeService;
    private final Consumer<String> toast;
    private final Map<ChangeDiffKey, SimpleDiffVirtualFile> changeDiffFiles = new LinkedHashMap<>();

    ChangeReviewCoordinator(Project project, ConversationChangeTracker changeService, Consumer<String> toast) {
        this.project = project;
        this.changeService = changeService;
        this.toast = toast;
    }

    void acceptAll(String sessionId) {
        // 空列表直接结束，避免把无意义的全量操作提交到界面线程。
        if (changeService.listSummaries(sessionId).isEmpty()) return;
        changeService.acceptAll(sessionId);
        closeSession(sessionId);
    }

    void accept(String sessionId, int index) {
        var change = changeAt(sessionId, index);
        if (change == null) return;
        changeService.accept(sessionId, change.path());
        closeDiff(sessionId, change.path());
    }

    void revert(String sessionId, int index) {
        var change = changeAt(sessionId, index);
        if (change == null) return;
        // 单文件撤销需要用户确认，避免误删本次会话建立基线后的手动编辑。
        if (Messages.showYesNoDialog(project, UiMessages.message("撤销 Codex 对该文件的全部修改？\n\n") + change.path(), UiMessages.message("撤销文件修改"), UiMessages.message("撤销"), UiMessages.message("取消"), Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revert(sessionId, change.path());
            closeDiff(sessionId, change.path());
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("无法撤销修改"));
        }
    }

    void revertAll(String sessionId) {
        if (changeService.listSummaries(sessionId).isEmpty()) return;
        // 全量撤销同样保留显式确认，失败时由 ChangeTracker 汇总未恢复的文件。
        if (Messages.showYesNoDialog(project, UiMessages.message("撤销当前回合捕获的全部文件修改？"), UiMessages.message("撤销全部修改"), UiMessages.message("全部撤销"), UiMessages.message("取消"), Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revertAll(sessionId);
            closeSession(sessionId);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("部分文件无法撤销"));
        }
    }

    void open(String sessionId, String provider, int index) {
        var change = changeAt(sessionId, index);
        if (change == null) return;
        var diffKey = new ChangeDiffKey(sessionId, change.path().toAbsolutePath().normalize());
        var editorManager = FileEditorManager.getInstance(project);
        var existingDiff = changeDiffFiles.get(diffKey);
        // 已打开的同一 Diff 只切换焦点，避免生成内容相同的重复标签。
        if (existingDiff != null && editorManager.isFileOpen(existingDiff)) {
            editorManager.openFile(existingDiff, true);
            return;
        }

        var details = changeService.readDetails(sessionId, change.path());
        if (details == null) return;
        var before = details.beforeContent() == null ? "" : new String(details.beforeContent(), StandardCharsets.UTF_8);
        var factory = DiffContentFactory.getInstance();
        // 刷新并定位源文件，让 Diff 读取用户当前看到的最新内容。
        var sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(change.path());
        var beforeContent = sourceFile == null ? factory.create(project, before) : factory.create(project, before, sourceFile);
        var sourceDocument = sourceFile == null ? null : FileDocumentManager.getInstance().getDocument(sourceFile);
        // 文本源文件绑定真实文档，Diff 中的编辑会直接落到当前文件。
        var afterContent = sourceDocument == null
            ? factory.create(project, details.afterContent() == null ? "" : new String(details.afterContent(), StandardCharsets.UTF_8))
            : factory.create(project, sourceDocument, sourceFile);
        var request = new SimpleDiffRequest(
            (Objects.equals(provider, "claude") ? UiMessages.message("Claude Code 修改 · ") : UiMessages.message("Codex 修改 · ")) + change.path().getFileName(),
            beforeContent,
            afterContent,
            UiMessages.message("AI 修改前（只读）"),
            UiMessages.message("当前文件（可编辑）")
        );
        // 只读保护 AI 修改前快照，允许右侧真实文件文档接收编辑。
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, new boolean[]{true, false});
        com.codexgui.service.DiffSourceContext.install(change.path(), request);
        var diffFile = new SimpleDiffVirtualFile(request);
        changeDiffFiles.put(diffKey, diffFile);
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true);
    }

    void openLocation(String sessionId, int index) {
        var change = changeAt(sessionId, index);
        if (change == null) return;
        // 已删除文件无法被系统选中，此时退回到仍然存在的父目录。
        var fileExists = Files.exists(change.path());
        var target = fileExists ? change.path() : change.path().getParent();
        if (target == null || !Files.exists(target)) {
            toast.accept(UiMessages.message("无法打开文件所在目录"));
            return;
        }
        // 现存文件交给系统文件管理器选中，删除记录则直接打开原父目录。
        if (fileExists) RevealFileAction.openFile(target);
        else RevealFileAction.openDirectory(target);
    }

    void fileClosed(VirtualFile file) {
        // 用户手动关闭 Diff 后释放复用记录，下次点击重新读取最新基线。
        changeDiffFiles.values().removeIf(file::equals);
    }

    void closeSession(String sessionId) {
        var editorManager = FileEditorManager.getInstance(project);
        var diffFiles = changeDiffFiles.entrySet().stream()
            .filter(entry -> Objects.equals(entry.getKey().sessionId(), sessionId))
            .map(Map.Entry::getValue)
            .toList();
        changeDiffFiles.entrySet().removeIf(entry -> Objects.equals(entry.getKey().sessionId(), sessionId));
        diffFiles.forEach(editorManager::closeFile);
    }

    private ConversationChangeTracker.ChangeSummary changeAt(String sessionId, int index) {
        var changes = changeService.listSummaries(sessionId);
        return index >= 0 && index < changes.size() ? changes.get(index) : null;
    }

    private void closeDiff(String sessionId, Path path) {
        var diffFile = changeDiffFiles.remove(new ChangeDiffKey(sessionId, path.toAbsolutePath().normalize()));
        if (diffFile != null) FileEditorManager.getInstance(project).closeFile(diffFile);
    }

    @Override
    public void dispose() {
        changeDiffFiles.clear();
    }

    private record ChangeDiffKey(String sessionId, Path path) {}
}
