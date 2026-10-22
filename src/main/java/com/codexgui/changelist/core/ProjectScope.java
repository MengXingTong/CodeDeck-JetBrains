package com.codexgui.changelist.core;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Lexical project boundary: no VFS traversal, content detection or disk access. */
public final class ProjectScope {
    private final Path root;

    private ProjectScope(Path base) {
        Path normalized = base == null ? null : base.normalize();
        // A missing/relative base or a drive/volume root must never become an all-disk scope.
        root = normalized != null && normalized.isAbsolute() && normalized.getParent() != null
                ? normalized : null;
    }

    public static ProjectScope of(Path base) { return new ProjectScope(base); }

    public static ProjectScope forBasePath(String base) {
        try { return of(base == null || base.isBlank() ? null : Path.of(base)); }
        catch (InvalidPathException ignored) { return of(null); }
    }

    public Path root() { return root; }

    public boolean contains(Path file) {
        if (root == null || file == null || !file.isAbsolute()) return false;
        Path normalized = file.normalize();
        return !normalized.equals(root) && normalized.startsWith(root);
    }

    public boolean contains(String file) {
        try { return file != null && contains(Path.of(file)); }
        catch (InvalidPathException ignored) { return false; }
    }
}
