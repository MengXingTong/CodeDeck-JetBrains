package com.codexgui.changelist.vcs;

import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.editor.DiffEditorTabFilesManager;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/** Use the IDE's live editor list, so closing a diff never leaves a cached request behind. EDT only. */
public final class NativeDiffTabs {
    private static final Key<Path> SOURCE_PATH = Key.create("com.codexgui.changelist.diff.sourcePath");
    private NativeDiffTabs() {}

    public static boolean focusExisting(Project project, Path path) {
        return focusExisting(path, Arrays.asList(FileEditorManager.getInstance(project).getOpenFiles()),
                file -> DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true));
    }

    static boolean focusExisting(Path path, Iterable<? extends VirtualFile> openFiles, Consumer<VirtualFile> activate) {
        Path key = path.normalize();
        for (VirtualFile file : openFiles) {
            if (file.isValid() && key.equals(file.getUserData(SOURCE_PATH))) {
                activate.accept(file);
                return true;
            }
        }
        return false;
    }

    public static void show(Project project, Path path, DiffRequest request) {
        // Recheck after background preparation, in case another action opened the same file meanwhile.
        if (focusExisting(project, path)) return;
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(createFile(path, request), true);
    }

    static ChainDiffVirtualFile createFile(Path path, DiffRequest request) {
        com.codexgui.service.DiffSourceContext.install(path, request);
        var file = new ChainDiffVirtualFile(new SimpleDiffRequestChain(request), path.getFileName() + " — VCS Diff");
        file.putUserData(SOURCE_PATH, path.normalize());
        return file;
    }
}
