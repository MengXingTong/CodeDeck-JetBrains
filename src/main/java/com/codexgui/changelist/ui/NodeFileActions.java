package com.codexgui.changelist.ui;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.core.TextGuard;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Explicit menu actions only; filesystem lookup and binary detection stay off the UI thread. */
final class NodeFileActions {
    private NodeFileActions() {}

    static void openInEditor(Project project, Path path, BooleanSupplier expired, Consumer<String> status) {
        withVirtualFile(project, path, true, expired, status, file -> {
            FileEditorManager.getInstance(project).openFile(file, true);
            status.accept("已在编辑器中打开文件");
        });
    }

    static void locateInProject(Project project, Path path, BooleanSupplier expired, Consumer<String> status) {
        withVirtualFile(project, path, false, expired, status, file -> {
            var projectWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW);
            if (projectWindow == null) {
                status.accept("项目窗口不可用，无法定位");
                return;
            }
            projectWindow.activate(() -> {
                if (expired.getAsBoolean() || !file.isValid()) return;
                ProjectView.getInstance(project).select(file, file, true);
                status.accept("已在项目窗口中定位");
            }, true);
        });
    }

    private static void withVirtualFile(Project project, Path path, boolean editor, BooleanSupplier expired,
                                        Consumer<String> status, Consumer<VirtualFile> action) {
        new Task.Backgroundable(project, editor ? "打开本地文件" : "定位项目文件", true) {
            private VirtualFile file;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                if (expired.getAsBoolean()) return;
                var scope = ProjectScope.forBasePath(project.getBasePath());
                if (!allowed(scope, path)) throw new IllegalStateException("已跳过项目目录外的路径");
                indicator.checkCanceled();
                if (editor) {
                    if (!Files.isRegularFile(path)) throw new IllegalStateException("本地文件不存在，无法在编辑器中打开");
                    try (var stream = Files.newInputStream(path)) {
                        if (!TextGuard.isProbablyText(stream.readNBytes(8192))) {
                            throw new IllegalStateException("已跳过二进制文件");
                        }
                    } catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
                }
                file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                if (file == null) throw new IllegalStateException("本地路径不存在，无法定位");
                if (editor && file.getFileType().isBinary()) throw new IllegalStateException("已跳过二进制文件");
                indicator.checkCanceled();
            }
            @Override public void onSuccess() {
                if (!expired.getAsBoolean() && file != null) action.accept(file);
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                if (!expired.getAsBoolean()) status.accept(error.getMessage());
            }
        }.queue();
    }

    static void reveal(Project project, Path path, BooleanSupplier expired, Consumer<String> status) {
        new Task.Backgroundable(project, "定位资源管理器路径", true) {
            private Path existing;
            private boolean directory;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                if (expired.getAsBoolean()) return;
                var scope = ProjectScope.forBasePath(project.getBasePath());
                if (!allowed(scope, path)) throw new IllegalStateException("已跳过项目目录外的路径");
                existing = path.normalize();
                while (!Files.exists(existing)) {
                    indicator.checkCanceled();
                    if (existing.equals(scope.root())) throw new IllegalStateException("项目目录已不存在");
                    existing = existing.getParent();
                }
                directory = Files.isDirectory(existing);
            }
            @Override public void onSuccess() {
                if (expired.getAsBoolean() || existing == null) return;
                if (directory) RevealFileAction.openDirectory(existing); else RevealFileAction.openFile(existing);
                status.accept("已在资源管理器中显示");
            }
            @Override public void onThrowable(@NotNull Throwable error) {
                if (!expired.getAsBoolean()) status.accept(error.getMessage());
            }
        }.queue();
    }

    private static boolean allowed(ProjectScope scope, Path path) {
        return path != null && scope.root() != null
                && (scope.contains(path) || scope.root().equals(path.normalize()));
    }
}
