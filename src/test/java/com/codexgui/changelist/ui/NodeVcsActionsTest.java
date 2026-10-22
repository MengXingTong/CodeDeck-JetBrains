package com.codexgui.changelist.ui;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.vcs.NativeChanges;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeVcsActionsTest {
    @TempDir Path directory;

    private ContentRevision revision(Path path) {
        var file = (FilePath) Proxy.newProxyInstance(FilePath.class.getClassLoader(), new Class<?>[]{FilePath.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPath")) return path.toString();
                    throw new AssertionError("Path collection read filesystem metadata: " + method.getName());
                });
        return (ContentRevision) Proxy.newProxyInstance(ContentRevision.class.getClassLoader(),
                new Class<?>[]{ContentRevision.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getFile")) return file;
                    throw new AssertionError("Path collection read content: " + method.getName());
                });
    }

    private NativeChanges.DiffTarget target(Path before, Path after) {
        var status = (FileStatus) Proxy.newProxyInstance(FileStatus.class.getClassLoader(), new Class<?>[]{FileStatus.class},
                (proxy, method, args) -> { throw new AssertionError("No UI status reads"); });
        return new NativeChanges.DiffTarget(new Change(before == null ? null : revision(before),
                after == null ? null : revision(after), status), null);
    }
    private ChangedFile file(Path path) { return new ChangedFile("selected", path, ChangedFile.Kind.MOVED, false); }

    @Test void movingFilesCollectsBothEndpointsWithoutContentOrDiskReads() {
        Path before = directory.resolve("before.cpp"), after = directory.resolve("after.cpp");
        assertEquals(List.of(after, before), NodeVcsActions.paths(ProjectScope.of(directory), file(after), target(before, after)));
    }

    @Test void movingIntoTheProjectCannotRevertTheExternalSource() {
        Path before = directory.resolve("../other/before.cpp").normalize(), after = directory.resolve("after.cpp");
        assertThrows(IllegalArgumentException.class,
                () -> NodeVcsActions.paths(ProjectScope.of(directory), file(after), target(before, after)));
    }

    @Test void existingAndDeletedFilesAreDeduplicatedAndLazyTargetsAreNotResolved() {
        Path path = directory.resolve("file.cpp");
        assertEquals(List.of(path), NodeVcsActions.paths(ProjectScope.of(directory), file(path), target(path, path)));
        assertEquals(List.of(path), NodeVcsActions.paths(ProjectScope.of(directory), file(path), target(path, null)));
        assertEquals(List.of(path), NodeVcsActions.paths(ProjectScope.of(directory), file(path),
                NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("No diff reads"); })));
    }
}
