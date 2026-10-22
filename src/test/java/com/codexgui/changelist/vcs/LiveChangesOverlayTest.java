package com.codexgui.changelist.vcs;

import com.codexgui.changelist.core.ChangedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveChangesOverlayTest {
    @TempDir Path root;

    private UnopenedChanges.Entry edit(Path path) {
        return new UnopenedChanges.Entry(path, ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT, false,
                NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("No checkout or content access for snapshots"); }));
    }

    @Test void editingWithoutCheckoutAppearsEvenWhenNativeAndFullScanCachesAreEmpty() {
        var overlay = new LiveChangesOverlay();
        var entry = edit(root.resolve("Source/game.cpp"));
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), entry, true)));
        assertEquals(List.of(entry), overlay.merge(List.of()));
    }

    @Test void fullDiskScanCannotHideUnsavedDocumentChanges() {
        var overlay = new LiveChangesOverlay();
        var entry = edit(root.resolve("Source/game.cpp"));
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), entry, true)));
        long fullScanStarted = overlay.version();
        overlay.acknowledge(List.of(entry.path()), fullScanStarted);
        overlay.acknowledgeAll(fullScanStarted);
        assertEquals(List.of(entry), overlay.merge(List.of()), "The saved file can be clean while the editor has changes");
    }

    @Test void anEditDuringAFullScanWinsOverItsOlderDiskSnapshot() {
        var overlay = new LiveChangesOverlay();
        long fullScanStarted = overlay.version();
        var entry = edit(root.resolve("Source/game.cpp"));
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), entry, false)));
        overlay.acknowledge(List.of(entry.path()), fullScanStarted);
        overlay.acknowledgeAll(fullScanStarted);
        assertEquals(List.of(entry), overlay.merge(List.of()));
    }

    @Test void restoringBaselineImmediatelyRemovesAStaleFullScanChange() {
        var overlay = new LiveChangesOverlay();
        var entry = edit(root.resolve("Source/game.cpp"));
        long fullScanStarted = overlay.version();
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), null, false)));
        overlay.acknowledgeAll(fullScanStarted);
        assertTrue(overlay.merge(List.of(entry)).isEmpty());
    }

    @Test void unsavedUndoToBaselineWinsEvenIfTheOldSavedFileWasModified() {
        var overlay = new LiveChangesOverlay();
        var entry = edit(root.resolve("Source/game.cpp"));
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), null, true)));
        overlay.acknowledgeAll(overlay.version());
        assertTrue(overlay.merge(List.of(entry)).isEmpty());
    }

    @Test void aLaterFullScanCanReplaceOldSavedOverrides() {
        var overlay = new LiveChangesOverlay();
        var entry = edit(root.resolve("Source/game.cpp"));
        overlay.update(List.of(new LiveChangesOverlay.Update(entry.path(), entry, false)));
        overlay.acknowledgeAll(overlay.version());
        assertTrue(overlay.merge(List.of()).isEmpty());
    }

    @Test void aPartialBatchOnlyReplacesOlderResultsForItsOwnPaths() {
        var overlay = new LiveChangesOverlay();
        var source = edit(root.resolve("Source/game.cpp"));
        var asset = edit(root.resolve("Content/asset.uasset"));
        overlay.update(List.of(new LiveChangesOverlay.Update(source.path(), source, false),
                new LiveChangesOverlay.Update(asset.path(), asset, false)));
        overlay.acknowledge(List.of(source.path()), overlay.version());
        assertEquals(List.of(asset), overlay.merge(List.of()));
    }

    @Test void documentComparisonDetectsEditsAndUndoWithNativeLineEndings() {
        assertTrue(PerforceLiveChanges.documentDiffers("modified\n", "baseline\r\n"));
        assertFalse(PerforceLiveChanges.documentDiffers("baseline\n", "baseline\r\n"));
        assertFalse(PerforceLiveChanges.documentDiffers("", ""));
        assertTrue(PerforceLiveChanges.documentDiffers("new text", ""));
    }
}
