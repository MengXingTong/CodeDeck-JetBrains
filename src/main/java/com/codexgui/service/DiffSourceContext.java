package com.codexgui.service;

import com.codexgui.changelist.ai.AiDiffViewer;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.openapi.util.Key;
import java.nio.file.Path;

/** Shared source metadata and annotation entry point for CodeDeck's VCS and AI-change Diff tabs. */
public final class DiffSourceContext {
    public static final Key<Path> SOURCE_PATH = Key.create("codedeck.diff.sourcePath");
    private DiffSourceContext() {}
    public static void install(Path path, DiffRequest request) {
        request.putUserData(SOURCE_PATH, path.toAbsolutePath().normalize());
        AiDiffViewer.install(path, request);
    }
}
