package com.codexgui.changelist.vcs;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ChangedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.codexgui.changelist.vcs.FileVcsOperations.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class FileOperationOverlayTest {
    @TempDir Path root;

    private NativeChanges.DiffTarget target(boolean opened) {
        return NativeChanges.DiffTarget.lazy(project -> {
            throw new AssertionError("Immediate operation feedback must not read diff content or wait for a scan");
        }).withOpened(opened);
    }
    private ChangedFile file(String name, String id) {
        return new ChangedFile(id, root.resolve(name), ChangedFile.Kind.MODIFIED, true);
    }
    private NativeChanges.Snapshot snapshot(String group, String label, ChangedFile file, boolean opened) {
        return new NativeChanges.Snapshot(List.of(new ChangeTree.Group(group, label, List.of(file))),
                Map.of(file.id(), target(opened)), "Perforce", root);
    }
    private FileVcsOperations.Result result(Path path, boolean opened) {
        return new FileVcsOperations.Result(List.of(new FileVcsOperations.Update(path,
                opened ? ChangedFile.Kind.MODIFIED : ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT,
                true, target(opened), opened ? "default" : "local", opened ? "工作区" : "本地变更")));
    }
    private NativeChanges.Snapshot apply(FileOperationOverlay overlay, NativeChanges.Snapshot cached) {
        return overlay.apply(cached, true, path -> true);
    }

    @Test void checkoutIsVisibleAndCancelableBeforeTheNativeCacheRefreshes() {
        var file = file("Source/file.cpp", "old-local");
        var stale = snapshot("local", "本地变更", file, false);
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), true));
        var displayed = apply(overlay, stale);
        assertEquals("default", displayed.groups().getFirst().id());
        assertEquals(1, displayed.groups().getFirst().files().size());
        var shown = displayed.groups().getFirst().files().getFirst();
        var target = displayed.targets().get(shown.id());
        assertTrue(target.opened());
        assertTrue(FileVcsOperations.available(CANCEL_CHECKOUT, shown, target, true, true));
        assertFalse(FileVcsOperations.available(CHECKOUT, shown, target, true, true));
        assertFalse(displayed.targets().containsKey("old-local"));
        assertEquals("local", stale.groups().getFirst().id(), "The IDE's cache must remain untouched");
    }

    @Test void cancelCheckoutOverridesAStaleOpenedCacheAndAllowsImmediateCheckoutAgain() {
        var file = file("Content/asset.uasset", "old-opened");
        var stale = snapshot("named", "Unify friend UI back actions and popup footer layout", file, true);
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), false));
        var displayed = apply(overlay, stale);
        assertEquals("local", displayed.groups().getFirst().id());
        var shown = displayed.groups().getFirst().files().getFirst();
        var target = displayed.targets().get(shown.id());
        assertTrue(shown.binary());
        assertFalse(target.opened());
        assertTrue(FileVcsOperations.available(CHECKOUT, shown, target, true, true));
        assertFalse(FileVcsOperations.available(CANCEL_CHECKOUT, shown, target, true, true));
        assertEquals(1, displayed.targets().size());
    }

    @Test void anOlderRefreshCannotEraseTheResultOfARapidCheckoutCancelSequence() {
        var file = file("Source/file.cpp", "old");
        var stale = snapshot("default", "工作区", file, true);
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), true));
        overlay.nativeUpdateCompleted();
        overlay.record(result(file.path(), false));
        assertEquals("local", apply(overlay, stale).groups().getFirst().id());
        overlay.nativeUpdateCompleted();
        assertEquals("local", apply(overlay, stale).groups().getFirst().id(), "A stale native opened state cannot clear cancel");
        var refreshed = snapshot("local", "本地变更", file, false);
        assertSame(refreshed, apply(overlay, refreshed));
    }

    @Test void namedChangelistsKeepTheirOwnCategoriesWhileOneFileMovesImmediately() {
        var first = file("Source/first.cpp", "first");
        var second = file("Source/second.cpp", "second");
        String description = "Unify friend UI back actions and popup footer layout";
        var stale = new NativeChanges.Snapshot(List.of(
                new ChangeTree.Group("default", "工作区", List.of(first)),
                new ChangeTree.Group("named", description, List.of(second))),
                Map.of(first.id(), target(true), second.id(), target(true)), "Perforce", root);
        var overlay = new FileOperationOverlay();
        overlay.record(result(first.path(), false));
        var displayed = apply(overlay, stale);
        assertEquals(List.of(description, "本地变更"), displayed.groups().stream().map(group -> group.label()).toList());
        assertSame(second, displayed.groups().getFirst().files().getFirst());
        assertEquals(2, displayed.targets().size());
    }

    @Test void successfulRevertRemovesOnlyTheSelectedFileWithoutWaitingForNativeRefresh() {
        var reverted = file("Source/reverted.cpp", "reverted");
        var other = file("Source/other.cpp", "other");
        var stale = new NativeChanges.Snapshot(List.of(new ChangeTree.Group("named", "Keep this changelist",
                List.of(reverted, other))), Map.of(reverted.id(), target(true), other.id(), target(true)), "Perforce", root);
        var overlay = new FileOperationOverlay();
        overlay.record(new FileVcsOperations.Result(List.of(new FileVcsOperations.Update(reverted.path(), null, true,
                null, "local", "本地变更"))));
        var displayed = apply(overlay, stale);
        assertEquals(List.of(other), displayed.groups().getFirst().files());
        assertEquals("Keep this changelist", displayed.groups().getFirst().label());
        assertFalse(displayed.targets().containsKey(reverted.id()));
    }

    @Test void observingOneUpdatedFileDoesNotEraseOtherOutstandingOperations() {
        var first = file("Source/first.cpp", "first");
        var second = file("Source/second.cpp", "second");
        var overlay = new FileOperationOverlay();
        overlay.record(result(first.path(), true));
        overlay.record(result(second.path(), false));
        overlay.nativeUpdateCompleted();
        var nativeSnapshot = new NativeChanges.Snapshot(List.of(
                new ChangeTree.Group("default", "工作区", List.of(first)),
                new ChangeTree.Group("named", "Named list", List.of(second))),
                Map.of(first.id(), target(true), second.id(), target(true)), "Perforce", root);
        var displayed = apply(overlay, nativeSnapshot);
        assertSame(first, displayed.groups().getFirst().files().getFirst());
        // The second native entry is still stale/opened, so its cancellation must keep overriding it.
        var secondStale = snapshot("named", "Named list", second, true);
        assertEquals("local", apply(overlay, secondStale).groups().getFirst().id());
    }

    @Test void immediateFeedbackRespectsHiddenNewFilesIgnoresAndTheProjectBoundary() {
        var overlay = new FileOperationOverlay();
        var path = root.resolve("Source/new.cpp");
        overlay.record(new FileVcsOperations.Result(List.of(new FileVcsOperations.Update(path, ChangedFile.Kind.UNVERSIONED,
                false, target(false), "local", "本地变更"))));
        var empty = new NativeChanges.Snapshot(List.of(), Map.of(), "Perforce", root);
        assertTrue(overlay.apply(empty, false, candidate -> true).groups().isEmpty());
        assertTrue(overlay.apply(empty, true, candidate -> false).groups().isEmpty());
        assertEquals(1, apply(overlay, empty).groups().size());
        overlay.record(result(root.resolve("../outside.cpp").normalize(), true));
        assertEquals(1, apply(overlay, empty).targets().size());
    }

    @Test void targetedCleanResultRemovesACanceledUnchangedFileWithNoNativeRefresh() {
        var file = file("Source/file.cpp", "old-opened");
        var stale = snapshot("default", "工作区", file, true);
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), false));
        overlay.localChanges(List.of(file.path()), List.of());
        assertTrue(apply(overlay, stale).groups().isEmpty());
        assertTrue(apply(overlay, stale).targets().isEmpty());
    }

    @Test void editingAgainAfterUndoIsShownByATargetedCheckWithoutAWorkspaceRefresh() {
        var file = file("Source/file.cpp", "old-opened");
        var stale = snapshot("default", "工作区", file, true);
        var overlay = new FileOperationOverlay();
        overlay.record(new FileVcsOperations.Result(List.of(new FileVcsOperations.Update(file.path(), null,
                false, null, "local", "本地变更"))));
        var edited = new UnopenedChanges.Entry(file.path(), ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT, false, target(false));
        overlay.localChanges(List.of(file.path()), List.of(edited));
        assertEquals("local", apply(overlay, stale).groups().getFirst().id());
        assertEquals(1, apply(overlay, stale).targets().size());
    }

    @Test void aLocalCheckCannotUndoTheOpenedStateOfANewerCheckout() {
        var file = file("Source/file.cpp", "old-local");
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), false));
        overlay.record(result(file.path(), true));
        overlay.localChanges(List.of(file.path()), List.of());
        assertEquals("default", apply(overlay, snapshot("local", "本地变更", file, false)).groups().getFirst().id());
    }

    @Test void vcsMappingChangesReleaseAllOverrides() {
        var file = file("Source/file.cpp", "native");
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), true));
        overlay.clear();
        var current = snapshot("other-vcs", "Other VCS", file, false);
        assertSame(current, apply(overlay, current));
    }

    @Test void renamingANativeChangelistDoesNotRequireAFullRefreshToUpdateItsLabel() {
        var file = file("Source/file.cpp", "native");
        var overlay = new FileOperationOverlay();
        overlay.record(result(file.path(), true));
        var renamed = snapshot("default", "Renamed changelist", file, true);
        assertEquals("Renamed changelist", apply(overlay, renamed).groups().getFirst().label());
    }
}
