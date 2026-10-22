package com.codexgui.changelist.vcs;

import com.codexgui.changelist.core.ProjectScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class PerforcePreviewTest {
    @TempDir Path root;

    private String record(Path path, String action, int revision, String type) {
        return "... depotFile //depot/Source/game.cpp\n... clientFile " + path
                + "\n... workRev " + revision + "\n... action " + action + "\n... type " + type + "\n\n";
    }

    @Test void allwritePreviewKeepsUnopenedEditsDeletesHaveRevisionAndBinaryMetadata() {
        var edited = root.resolve("Source/game with spaces.cpp");
        var deleted = root.resolve("Content/missing.uasset");
        var entries = PerforcePreview.parse(record(edited, "edit", 16, "text")
                + record(deleted, "delete", 4, "binary+l"), ProjectScope.of(root), () -> {});
        assertEquals(2, entries.size());
        assertEquals(edited, entries.getFirst().path());
        assertEquals(16, entries.getFirst().revision());
        assertFalse(entries.getFirst().deleted());
        assertFalse(entries.getFirst().binary());
        assertTrue(entries.getLast().deleted());
        assertTrue(entries.getLast().binary());
    }

    @Test void malformedUnmappedAddedAndOutsidePathsNeverBecomeDiscoveredChanges() {
        var text = record(root.resolve("../outside/game.cpp"), "edit", 3, "text")
                + record(root.resolve("new.cpp"), "add", 0, "text")
                + record(root.resolve("unknown.cpp"), "edit", 0, "text")
                + "... code error\n... data not in client view\n";
        assertTrue(PerforcePreview.parse(text, ProjectScope.of(root), () -> {}).isEmpty());
        assertTrue(PerforcePreview.parse("", ProjectScope.of(root), () -> {}).isEmpty());
    }

    @Test void commandIsAlwaysPreviewOnlyAndEscapesP4RevisionAndWildcardCharacters() {
        var argumentFile = root.resolve("preview args.txt");
        var command = List.of(PerforcePreview.command(argumentFile));
        assertEquals(List.of("-x", argumentFile.toString(), "-ztag", "reconcile", "-n", "-e", "-d"), command);
        assertEquals(List.of("-ztag", "have", PerforcePreview.encodePath(root) + "/Game %231%40work%25[x]/..."),
                List.of(PerforcePreview.haveCommand(root.resolve("Game #1@work%[x]"))));
        assertFalse(command.contains("-w"));
    }

    @Test void havePlanUsesOnlySyncedProjectFilesAndPrioritizesSourceWithoutReadingTheFilesystem() {
        var source = root.resolve("Source/game.cpp");
        var config = root.resolve("Config/DefaultEngine.ini");
        var plugin = root.resolve("Plugins/Example/Source/plugin.cpp");
        var asset = root.resolve("Content/huge.uasset");
        var output = "... path " + asset + "\n... path " + root.resolve("../Other/outside.cpp")
                + "\n... clientFile //client/not-a-local-path\n... path " + source + "\n... path " + source
                + "\n... path " + plugin + "\n... path " + config + "\n";
        assertEquals(List.of(source, config, plugin, asset), PerforcePreview.syncedFiles(output, ProjectScope.of(root), () -> {}));
        assertEquals(List.of(), PerforcePreview.syncedFiles(output, ProjectScope.of(null), () -> {}));
    }

    @Test void unchangedBatchWarningsAreAcceptedWithoutHidingAuthenticationOrOtherErrors() {
        assertEquals("", PerforcePreview.errorsOnly("No file(s) to reconcile.\nNo file(s) to reconcile.\n"));
        assertEquals("Your session has expired, please login again.", PerforcePreview.errorsOnly(
                "No file(s) to reconcile.\nYour session has expired, please login again.\n"));
        assertEquals("Unexpected error", PerforcePreview.errorsOnly("Unexpected error\n"));
    }

    @Test void targetedHaveFindsWorkspaceBaselineWithoutAnyCheckoutOrActionRecord() {
        Path path = root.resolve("Source/edited-but-not-checked-out.cpp");
        String have = "... depotFile //depot/Source/game.cpp\n... clientFile //client/Source/game.cpp\n... path "
                + path + "\n... haveRev 16\n... syncTime 12345\n";
        var entries = PerforcePreview.haveEntries(have, ProjectScope.of(root), () -> {});
        assertEquals(1, entries.size());
        assertEquals(path, entries.getFirst().path());
        assertEquals(16, entries.getFirst().revision());
        assertEquals("//depot/Source/game.cpp", entries.getFirst().depotPath());
        assertTrue(PerforcePreview.haveEntries(have, ProjectScope.of(null), () -> {}).isEmpty());
    }

    @Test void aNewFileHaveWarningIsBenignOnlyForTargetedHaveAndDoesNotHideRealErrors() {
        String warning = root.resolve("Source/new.cpp") + " - file(s) not on client.";
        assertEquals(warning, PerforcePreview.errorsOnly(warning));
        assertEquals("", PerforcePreview.haveErrorsOnly(warning));
        assertEquals("Your session has expired", PerforcePreview.haveErrorsOnly(warning + "\nYour session has expired"));
        assertEquals(List.of("-x", root.resolve("paths.txt").toString(), "-ztag", "have"),
                List.of(PerforcePreview.haveFilesCommand(root.resolve("paths.txt"))));
    }

    @Test void slowAssetScanHasAnIndependentTimeoutAndCannotDisableTheIdeConnection() {
        var disabled = new java.util.concurrent.atomic.AtomicBoolean();
        var original = new org.jetbrains.idea.perforce.perforce.PerforcePhysicalConnectionParametersI() {
            public String getPathToExec() { return "p4.exe"; }
            public String getPathToIgnore() { return ".p4ignore"; }
            public com.intellij.openapi.project.Project getProject() { return null; }
            public void disable() { disabled.set(true); }
            public int getServerTimeout() { return 20_000; }
            public String getCharsetName() { return "none"; }
        };
        var scan = PerforceUnopenedChanges.scanParameters(original);
        assertEquals(900_000, scan.getServerTimeout());
        assertEquals(20_000, original.getServerTimeout());
        assertEquals("p4.exe", scan.getPathToExec());
        assertEquals(".p4ignore", scan.getPathToIgnore());
        assertEquals("none", scan.getCharsetName());
        scan.disable(); assertFalse(disabled.get());
    }

    @Test void cancellationIsCheckedWhileParsingLargeOutputs() {
        assertThrows(CancellationException.class, () -> PerforcePreview.parse(
                record(root.resolve("game.cpp"), "edit", 1, "text"), ProjectScope.of(root),
                () -> { throw new CancellationException(); }));
    }
}
