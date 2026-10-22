package com.codexgui.changelist.vcs;

import com.intellij.openapi.vcs.VcsException;
import com.codexgui.changelist.core.ProjectScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.codexgui.changelist.vcs.FileVcsOperations.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class PerforceFileOperationsTest {
    @TempDir Path directory;

    private PerforceFileOperations.State state(String name, String action, long revision) {
        return new PerforceFileOperations.State(directory.resolve(name), action, revision, "//depot/" + name, null);
    }
    private List<String> command(FileVcsOperations.Operation operation, PerforceFileOperations.State... states)
            throws VcsException {
        return List.of(PerforceFileOperations.command(operation, ProjectScope.of(directory), List.of(states)));
    }

    @Test void checkoutPreservesContentsAndUsesOnlyTheSelectedLiteralPath() throws Exception {
        var file = state("Source/a #1@work%.cpp", null, 7);
        assertEquals(List.of("edit", PerforcePreview.encodePath(file.path())), command(CHECKOUT, file));
        assertTrue(command(CHECKOUT, file).getLast().endsWith("a %231%40work%25.cpp"));
    }

    @Test void cancelCheckoutAlwaysKeepsWorkspaceContentsForEditAddAndDelete() throws Exception {
        for (String action : List.of("edit", "add", "delete", "integrate", "branch")) {
            var file = state("file.cpp", action, "add".equals(action) ? 0 : 7);
            assertEquals(List.of("revert", "-k", PerforcePreview.encodePath(file.path())), command(CANCEL_CHECKOUT, file));
        }
    }

    @Test void revertOpenedFilesUsesRevertWithoutDeletingLocalAdds() throws Exception {
        for (String action : List.of("edit", "delete")) {
            var file = state("file.cpp", action, 7);
            assertEquals(List.of("revert", PerforcePreview.encodePath(file.path())), command(REVERT, file));
        }
        for (String action : List.of("add", "branch", "integrate")) {
            var file = state("file.cpp", action, 0);
            assertEquals(List.of("revert", "-k", PerforcePreview.encodePath(file.path())), command(REVERT, file));
        }
    }

    @Test void revertUnopenedModificationsAndMissingFilesRestoresHaveInsteadOfHead() throws Exception {
        var file = state("Content/missing.uasset", null, 7);
        assertEquals(List.of("sync", "-f", PerforcePreview.encodePath(file.path()) + "#7"), command(REVERT, file));
        assertEquals(command(REVERT, file), command(REVERT, state("Content/missing.uasset", "", 7)));
    }

    @Test void staleOrUnversionedStatesNeverProduceMutationCommands() {
        assertThrows(VcsException.class, () -> command(CHECKOUT, state("file.cpp", "edit", 7)));
        assertThrows(VcsException.class, () -> command(CHECKOUT, state("file.cpp", null, 0)));
        assertThrows(VcsException.class, () -> command(CANCEL_CHECKOUT, state("file.cpp", null, 7)));
        assertThrows(VcsException.class, () -> command(REVERT, state("file.cpp", null, 0)));
        assertThrows(VcsException.class, () -> command(REVERT));
    }

    @Test void projectBoundaryIsCheckedForEveryMutationAndBothMoveEndpoints() {
        var outside = new PerforceFileOperations.State(directory.resolve("../outside.cpp").normalize(),
                "edit", 7, "//depot/outside.cpp", null);
        for (var operation : FileVcsOperations.Operation.values()) {
            assertThrows(VcsException.class, () -> command(operation, outside));
        }
        assertThrows(VcsException.class, () -> command(REVERT, state("inside.cpp", "edit", 7), outside));
    }

    @Test void movesRequireBothReciprocalEndpointsAndStayInTheProject() throws Exception {
        var before = new PerforceFileOperations.State(directory.resolve("before.cpp"), "move/delete", 7,
                "//depot/before.cpp", "//depot/after.cpp");
        var after = new PerforceFileOperations.State(directory.resolve("after.cpp"), "move/add", 0,
                "//depot/after.cpp", "//depot/before.cpp");
        assertEquals(List.of("revert", PerforcePreview.encodePath(after.path())),
                command(REVERT, after, before));
        assertEquals(command(REVERT, after, before), command(REVERT, before, after));
        assertEquals(List.of("revert", "-k", PerforcePreview.encodePath(after.path())),
                command(CANCEL_CHECKOUT, after, before));
        assertThrows(VcsException.class, () -> command(REVERT, after));
        assertThrows(VcsException.class, () -> command(CANCEL_CHECKOUT, before));
        assertThrows(VcsException.class, () -> command(REVERT, after, state("other.cpp", "edit", 7)));
        var stale = new PerforceFileOperations.State(before.path(), "move/delete", 7, before.depot(), "//depot/elsewhere.cpp");
        assertThrows(VcsException.class, () -> command(REVERT, after, stale));
    }
}
