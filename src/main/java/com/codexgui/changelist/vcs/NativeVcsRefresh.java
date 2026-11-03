package com.codexgui.changelist.vcs;

import com.codexgui.i18n.UiMessages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl;
import com.intellij.openapi.vcs.changes.ChangesViewRefresher;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.vcsUtil.VcsUtil;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Uses the explicit project and registers completion only after VFS preparation has finished. */
public final class NativeVcsRefresh {
    private NativeVcsRefresh() {}

    interface Backend {
        String unavailable();
        void prepare();
        void refreshFiles(Runnable completed);
        void invalidateProvider();
        void markDirty(Path path, boolean directory, boolean fullRefresh);
        void afterUpdate(Runnable completed);
        String updateError();
    }

    public static void refresh(Project project, Path path, boolean directory, boolean prepare,
                               Consumer<String> completed) {
        refresh(new PlatformBackend(project), path, directory, prepare, completed);
    }

    /** Empty means no known error, not proof that the cached changes are current. */
    public static String problem(Project project) {
        var backend = new PlatformBackend(project);
        String unavailable = backend.unavailable();
        return unavailable.isBlank() ? backend.updateError() : unavailable;
    }

    static void refresh(Backend backend, Path path, boolean directory, boolean prepare,
                        Consumer<String> completed) {
        try {
            String unavailable = backend.unavailable();
            if (!unavailable.isBlank()) { completed.accept(unavailable); return; }
            Runnable update = () -> {
                try {
                    if (prepare) backend.invalidateProvider();
                    // Dirty first: waiting on an empty queue can otherwise acknowledge the old cache.
                    backend.markDirty(path, directory, prepare);
                    backend.afterUpdate(() -> {
                        try {
                            String problem = backend.unavailable();
                            completed.accept(problem.isBlank() ? backend.updateError() : problem);
                        } catch (RuntimeException error) { completed.accept(errorMessage(error)); }
                    });
                } catch (RuntimeException error) { completed.accept(errorMessage(error)); }
            };
            if (prepare) {
                backend.prepare();
                backend.refreshFiles(update);
            } else update.run();
        } catch (RuntimeException error) { completed.accept(errorMessage(error)); }
    }

    private static String errorMessage(RuntimeException error) {
        if (error instanceof com.intellij.openapi.progress.ProcessCanceledException)
            return UiMessages.message("VCS 刷新已取消");
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record PlatformBackend(Project project) implements Backend {
        @Override public String unavailable() {
            if (project.isDisposed()) return UiMessages.message("项目已关闭");
            String frozen = ChangeListManager.getInstance(project).isFreezed();
            if (frozen != null) return UiMessages.message("IDE 暂停了 VCS 刷新：") + frozen;
            var perforce = project.getService(UnopenedChanges.class);
            return perforce == null ? "" : perforce.nativeRefreshProblem();
        }

        @Override public void prepare() {
            FileDocumentManager.getInstance().saveAllDocuments();
            for (var refresher : ChangesViewRefresher.EP_NAME.getExtensionList(project)) refresher.refresh(project);
        }

        @Override public void refreshFiles(Runnable completed) {
            VirtualFileManager.getInstance().asyncRefresh(() ->
                    ApplicationManager.getApplication().invokeLater(completed));
        }

        @Override public void markDirty(Path path, boolean directory, boolean fullRefresh) {
            var dirty = VcsDirtyScopeManager.getInstance(project);
            // A partial update retains ChangeListManager's old exception and refuses to publish
            // recovered changes. Only a complete native update resets that project-wide state.
            if (fullRefresh) { dirty.markEverythingDirty(); return; }
            var file = VcsUtil.getFilePath(path, directory);
            if (directory) dirty.dirDirtyRecursively(file); else dirty.fileDirty(file);
        }

        @Override public void invalidateProvider() {
            var perforce = project.getService(UnopenedChanges.class);
            if (perforce != null) perforce.invalidateNativeRefresh();
        }

        @Override public void afterUpdate(Runnable completed) {
            ChangeListManager.getInstance(project).invokeAfterUpdate(true, completed);
        }

        @Override public String updateError() {
            var manager = ChangeListManager.getInstance(project);
            var error = manager instanceof ChangeListManagerImpl impl ? impl.getUpdateException() : null;
            if (error == null) return "";
            String message = String.join("; ", error.getMessages());
            return message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }
}
