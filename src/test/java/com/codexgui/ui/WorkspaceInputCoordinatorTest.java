package com.codexgui.ui;

import com.codexgui.model.FileReference;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class WorkspaceInputCoordinatorTest {
    @TempDir Path root;

    @Test
    void permitsDeletedChangeListReferencesWithinTheWorkspace() throws Exception {
        var path = root.resolve("Deleted.cpp");
        assertTrue(WorkspaceInputCoordinator.canReferencePath(root, path));
        assertFalse(WorkspaceInputCoordinator.canReferencePath(root, root.getParent().resolve("missing.cpp")));
        assertFalse(WorkspaceInputCoordinator.canReferencePath(root, null));
        java.nio.file.Files.writeString(path, "present");
        assertTrue(WorkspaceInputCoordinator.canReferencePath(root, path));
        java.nio.file.Files.delete(path);
        assertTrue(WorkspaceInputCoordinator.canReferencePath(root, path));
    }
    @Test
    void validatesAndAppliesEditorReferenceOrderAtomically() {
        var first = new FileReference("first", "First.cpp", Path.of("D:/Project/First.cpp"), false);
        var second = new FileReference("second", "Second.cpp", Path.of("D:/Project/Second.cpp"), false);

        assertEquals(List.of(second, first), WorkspaceInputCoordinator.orderedSendReferences(
            List.of(first, second), "左\uFFFC中\uFFFC右", ids("second", "first")));
        assertNull(WorkspaceInputCoordinator.orderedSendReferences(
            List.of(first, second), "只有一个\uFFFC", ids("first", "second")));
        assertNull(WorkspaceInputCoordinator.orderedSendReferences(
            List.of(first, second), "\uFFFC\uFFFC", ids("first", "first")));
        assertNull(WorkspaceInputCoordinator.orderedSendReferences(
            List.of(first, second), "\uFFFC\uFFFC", ids("first", "unknown")));
    }

    private JsonArray ids(String... values) {
        var result = new JsonArray();
        for (var value : values) result.add(value);
        return result;
    }
}
