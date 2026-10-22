package com.codexgui.changelist.ai;

import com.intellij.diff.EditorDiffViewer;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.util.DiffUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import java.nio.file.Path;

/** Add a native Diff notification/header; never replace the viewer or its editor data context. */
public final class AiDiffViewer {
    private static final Key<Boolean> INSTALLED = Key.create("codedeck.diff.annotationHeader");
    private AiDiffViewer() {}
    public static void install(Path path, DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contents) || contents.getContents().size() != 2) return;
        if (Boolean.TRUE.equals(request.getUserData(INSTALLED))) return;
        request.putUserData(INSTALLED, true);
        DiffUtil.addNotification(viewer -> {
            if (!(viewer instanceof EditorDiffViewer editors) || !(viewer instanceof DiffViewerBase base)) return null;
            var project = base.getProject();
            if (project == null) return null;
            var panel = new AiDiffPanel(project, path, contents, editors);
            Disposer.register(viewer, panel); panel.attach(); return panel;
        }, request);
    }
}
