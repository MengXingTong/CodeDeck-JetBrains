package com.codexgui.changelist.vcs;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.core.IgnoreRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeChangesMetadataTest {
    @TempDir Path directory;

    @Test void localNewModifiedAndDeletedFilesShareOneTreeWithoutLosingTheirDiffTargets() {
        var added = metadataOnlyPath("Source/new.cpp");
        var modified = metadataOnlyPath("Source/modified.cpp");
        var editedTarget = NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("No diff reads for the tree"); });
        var deletedTarget = NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("No diff reads for the tree"); });
        var edit = new UnopenedChanges.Entry(directory.resolve("Source/modified.cpp"),
                ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT, false, editedTarget);
        var deletion = new UnopenedChanges.Entry(directory.resolve("Source/deleted.cpp"),
                ChangedFile.Kind.DELETED, false, deletedTarget);
        var scope = ProjectScope.of(directory);
        var result = NativeChanges.collect(scope, List.of(), List.of(added, modified), List.of(modified),
                List.of(edit, deletion), "Perforce", IgnoreRules.none(scope), name -> false, () -> {});
        assertEquals(1, result.groups().size());
        var group = result.groups().getFirst();
        assertEquals("local", group.id());
        assertEquals("本地变更", group.label());
        assertEquals(3, group.files().size());
        var byName = group.files().stream().collect(java.util.stream.Collectors.toMap(
                file -> file.path().getFileName().toString(), file -> file));
        assertEquals("新增", byName.get("new.cpp").kind().label());
        assertEquals("修改", byName.get("modified.cpp").kind().label());
        assertEquals("删除", byName.get("deleted.cpp").kind().label());
        assertSame(added, result.targets().get(byName.get("new.cpp").id()).unversionedPath());
        assertSame(editedTarget, result.targets().get(byName.get("modified.cpp").id()));
        assertSame(deletedTarget, result.targets().get(byName.get("deleted.cpp").id()));
        var tree = com.codexgui.changelist.core.ChangeTree.build(result.groups(), directory);
        assertEquals(1, tree.children().getFirst().children().size(), "Source must appear only once");
        assertEquals("Source", tree.children().getFirst().children().getFirst().label());
    }

    @Test void automaticNewFilesJoinLocalChangesWithoutRequiringNativeCacheOrCheckout() {
        var path = metadataOnlyPath("Source/new.cpp");
        var target = new NativeChanges.DiffTarget(null, path);
        var discovered = new UnopenedChanges.Entry(directory.resolve("Source/new.cpp"), ChangedFile.Kind.UNVERSIONED, false, target);
        var scope = ProjectScope.of(directory);
        var snapshot = NativeChanges.collect(scope, List.of(), List.of(path), List.of(), List.of(discovered),
                "Perforce", IgnoreRules.none(scope), name -> false, () -> {});
        assertEquals(1, snapshot.groups().size());
        assertEquals("local", snapshot.groups().getFirst().id());
        assertEquals(ChangedFile.Kind.UNVERSIONED, snapshot.groups().getFirst().files().getFirst().kind());
        assertEquals(1, snapshot.targets().size());
        assertSame(target, snapshot.targets().values().iterator().next());
    }

    @Test void allwritePreviewIsVisibleEvenWithAnEmptyNativeCacheAndHiddenUnversionedSection() {
        Path path = directory.resolve("Source/game.cpp");
        var target = NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("Snapshot must not load diff"); });
        var preview = new UnopenedChanges.Entry(path, ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT, false, target);
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(), List.of(), List.of(), List.of(preview),
                "Perforce", IgnoreRules.none(ProjectScope.of(directory)), name -> false, () -> {});
        assertEquals(1, result.targets().size());
        assertSame(target, result.targets().values().iterator().next());
        assertEquals("local", result.groups().getFirst().id());
        assertEquals(path, result.groups().getFirst().files().getFirst().path());
    }

    @Test void ignoresFilterTrackedUnopenedDeletedAndUnversionedBeforeTypeOrDiffReads() {
        var generated = metadataOnlyPath("Intermediate/Generated.cpp");
        var change = new Change(revision(generated), revision(generated), status());
        var target = NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("Must not load ignored diff"); });
        var preview = new UnopenedChanges.Entry(directory.resolve("Binaries/game.dll"), ChangedFile.Kind.DELETED, true, target);
        var ignores = new IgnoreRules(ProjectScope.of(directory), List.of("UnrealEngine"), "", List.of(), List.of());
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(changelist("native", List.of(change))),
                List.of(generated), List.of(generated), List.of(preview), "Perforce", ignores,
                name -> { throw new AssertionError("Ignored path classified: " + name); }, () -> {});
        assertTrue(result.groups().isEmpty()); assertTrue(result.targets().isEmpty());
    }

    @Test void nativeCheckoutWinsPreviewAndPreviewDeletionWinsStaleModifiedStatus() {
        var path = metadataOnlyPath("Source/game.cpp");
        var change = new Change(revision(path), revision(path), status());
        var target = NativeChanges.DiffTarget.lazy(project -> { throw new AssertionError("No diff I/O"); });
        var deleted = new UnopenedChanges.Entry(directory.resolve("Source/game.cpp"), ChangedFile.Kind.DELETED, false, target);
        var scope = ProjectScope.of(directory);
        var checkedOut = NativeChanges.collect(scope, List.of(changelist("native", List.of(change))), List.of(path),
                List.of(path), List.of(deleted), "Perforce", IgnoreRules.none(scope), name -> false, () -> {});
        assertEquals(1, checkedOut.targets().size());
        assertSame(change, checkedOut.targets().values().iterator().next().change());
        var noCheckout = NativeChanges.collect(scope, List.of(), List.of(path), List.of(path), List.of(deleted),
                "Perforce", IgnoreRules.none(scope), name -> false, () -> {});
        assertEquals(1, noCheckout.targets().size());
        assertSame(target, noCheckout.targets().values().iterator().next());
        assertEquals("local", noCheckout.groups().getFirst().id());
    }

    // The regression fixture fails immediately if a snapshot touches file detection or VFS/content APIs.
    private FilePath metadataOnlyPath(String name) {
        String path = directory.resolve(name).toString();
        return (FilePath) Proxy.newProxyInstance(FilePath.class.getClassLoader(), new Class<?>[]{FilePath.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPath", "toString" -> path;
                    case "getName" -> name;
                    case "isDirectory", "isNonLocal" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> path.hashCode();
                    default -> throw new AssertionError("Snapshot attempted file detection/I/O: " + method.getName());
                });
    }

    private ContentRevision revision(FilePath path) {
        return new ContentRevision() {
            @Override public String getContent() { throw new AssertionError("Snapshot must not read contents"); }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { throw new AssertionError("Snapshot must not load revision content"); }
        };
    }

    private FileStatus status() {
        return (FileStatus) Proxy.newProxyInstance(FileStatus.class.getClassLoader(), new Class<?>[]{FileStatus.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "test status";
                    throw new AssertionError("Snapshot must not query UI status: " + method.getName());
                });
    }

    @Test void trackedSnapshotUsesMetadataWithoutFileTypeDetectionOrContents() {
        FilePath path = metadataOnlyPath("script.cs");
        Change change = new Change(revision(path), revision(path), status());
        var file = NativeChanges.describe(change, "tracked", name -> name.endsWith(".png"));
        assertEquals(ChangedFile.Kind.MODIFIED, file.kind());
        assertFalse(file.binary());
        assertEquals(directory.resolve("script.cs"), file.path());
    }

    @Test void unversionedSnapshotNeverCreatesAContentRevision() {
        FilePath path = metadataOnlyPath("unknown-file");
        var file = NativeChanges.describeUnversioned(path, "new", name -> false);
        assertEquals(ChangedFile.Kind.UNVERSIONED, file.kind());
        assertFalse(file.binary());
        var target = new NativeChanges.DiffTarget(null, path);
        assertNull(target.change());
        assertSame(path, target.unversionedPath());
    }

    @Test void binaryVcsRevisionsAreRecognizedWithoutDownloadingThem() {
        FilePath path = metadataOnlyPath("asset.uasset");
        BinaryContentRevision binary = new BinaryContentRevision() {
            @Override public byte[] getBinaryContent() { throw new AssertionError("Binary content must never be downloaded for the tree"); }
            @Override public String getContent() { throw new AssertionError("No content reads"); }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { throw new AssertionError("No revision loads"); }
        };
        Change change = new Change(binary, null, status());
        var file = NativeChanges.describe(change, "binary", name -> false);
        assertTrue(file.binary());
        assertEquals(ChangedFile.Kind.DELETED, file.kind());
    }

    private LocalChangeList changelist(String id, List<Change> changes) {
        return new LocalChangeList() {
            @Override public String getId() { return id; }
            @Override public Collection<Change> getChanges() { return changes; }
            @Override public String getName() { return "Test changes"; }
            @Override public String getComment() { return ""; }
            @Override public void setComment(String comment) {}
            @Override public boolean isDefault() { return true; }
            @Override public boolean isReadOnly() { return false; }
            @Override public boolean isBlank() { return false; }
            @Override public Object getData() { return null; }
            @Override public LocalChangeList copy() { return this; }
        };
    }

    @Test void externalTrackedAndUnversionedPathsNeverBecomeFilesOrDiffTargets() {
        var inside = metadataOnlyPath("Source/script.cs");
        var outside = metadataOnlyPath("../other/external.cs");
        var sibling = metadataOnlyPath("../" + directory.getFileName() + "-backup/external.cs");
        var unversioned = metadataOnlyPath("Content/asset.uasset");
        var tracked = new Change(revision(inside), revision(inside), status());
        var outsideChange = new Change(revision(outside), revision(outside), status());
        var result = NativeChanges.collect(ProjectScope.of(directory),
                List.of(changelist("local", List.of(tracked, outsideChange)),
                        changelist("external", List.of(outsideChange))),
                List.of(unversioned, outside, sibling), List.of(), "Perforce / Git", name -> {
                    assertNotEquals("external.cs", name, "Out-of-scope files must be filtered before type classification");
                    return name.endsWith(".uasset");
                }, () -> {});
        assertEquals(2, result.groups().size());
        assertEquals(List.of(1, 1), result.groups().stream().map(group -> group.files().size()).toList());
        assertEquals(Set.of(directory.resolve("Source/script.cs"), directory.resolve("Content/asset.uasset")),
                result.groups().stream().flatMap(group -> group.files().stream()).map(ChangedFile::path)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.targets().size());
        assertTrue(result.targets().values().stream().allMatch(target ->
                target.change() == tracked || target.unversionedPath() == unversioned));
        assertTrue(result.groups().get(1).files().getFirst().binary());
        assertEquals(directory, result.projectRoot());
    }

    @Test void deletedFilesUseOldPathAndMovesOutsideProjectAreExcluded() {
        var inside = metadataOnlyPath("Source/script.cs");
        var outside = metadataOnlyPath("../other/external.cs");
        var deleted = new Change(revision(inside), null, status());
        var movedOut = new Change(revision(inside), revision(outside), status());
        var result = NativeChanges.collect(ProjectScope.of(directory),
                List.of(changelist("local", List.of(deleted, movedOut))), List.of(), List.of(), "Git", name -> false, () -> {});
        assertEquals(1, result.targets().size());
        assertSame(deleted, result.targets().values().iterator().next().change());
        assertEquals(ChangedFile.Kind.DELETED, result.groups().getFirst().files().getFirst().kind());
    }

    @Test void missingProjectDirectoryDoesNotEvenReadCachedLists() {
        Iterable<LocalChangeList> lists = () -> { throw new AssertionError("No project scope; must not read changes"); };
        Iterable<FilePath> unversioned = () -> { throw new AssertionError("No project scope; must not read unversioned files"); };
        var result = NativeChanges.collect(ProjectScope.of(null), lists, unversioned, unversioned,
                "Perforce", name -> false, () -> {});
        assertTrue(result.groups().isEmpty());
        assertTrue(result.targets().isEmpty());
        assertNull(result.projectRoot());
    }

    @Test void localChangesRemainVisibleWhenOnlyProjectNewFilesExist() {
        var inside = metadataOnlyPath("Source/new.cs");
        var outside = metadataOnlyPath("../other/external.cs");
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(), List.of(outside, inside), List.of(),
                "Perforce", name -> {
                    assertEquals("new.cs", name);
                    return false;
                }, () -> {});
        assertEquals(1, result.groups().size());
        var group = result.groups().getFirst();
        assertEquals("local", group.id());
        assertEquals("本地变更", group.label());
        assertEquals(List.of(directory.resolve("Source/new.cs")), group.files().stream().map(ChangedFile::path).toList());
        assertEquals(ChangedFile.Kind.UNVERSIONED, group.files().getFirst().kind());
        assertEquals(1, result.targets().size());
        assertSame(inside, result.targets().values().iterator().next().unversionedPath());
    }

    @Test void modifiedWithoutCheckoutAppearsWithUnversionedSectionHiddenAndDefersDiffReads() {
        var path = metadataOnlyPath("Source/PlayerExitGameComponent.h");
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(), List.of(), List.of(path),
                "Perforce", name -> false, () -> {});
        assertEquals(1, result.groups().size());
        var group = result.groups().getFirst();
        assertEquals("local", group.id());
        assertEquals("本地变更", group.label());
        assertEquals(ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT, group.files().getFirst().kind());
        assertEquals(directory.resolve("Source/PlayerExitGameComponent.h"), group.files().getFirst().path());
        var target = result.targets().get(group.files().getFirst().id());
        assertNull(target.change());
        assertNull(target.unversionedPath());
        assertSame(path, target.modifiedWithoutCheckoutPath());
    }

    @Test void modifiedWithoutCheckoutFiltersExternalPathsBeforeClassificationAndKeepsBinaries() {
        var asset = metadataOnlyPath("Content/asset.uasset");
        var outside = metadataOnlyPath("../other/external.cs");
        var sibling = metadataOnlyPath("../" + directory.getFileName() + "-backup/external.cs");
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(), List.of(),
                List.of(outside, sibling, asset), "Perforce", name -> {
                    assertEquals("asset.uasset", name);
                    return true;
                }, () -> {});
        assertEquals(1, result.groups().size());
        assertEquals(1, result.targets().size());
        assertTrue(result.groups().getFirst().files().getFirst().binary());
    }

    @Test void checkoutMovesFileIntoNativeChangelistWithoutDuplicatingStaleStatuses() {
        var path = metadataOnlyPath("Source/PlayerExitGameComponent.h");
        var change = new Change(revision(path), revision(path), status());
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(changelist("checked-out", List.of(change))),
                List.of(path), List.of(path), "Perforce", name -> false, () -> {});
        assertEquals(1, result.groups().size());
        assertEquals("checked-out", result.groups().getFirst().id());
        assertEquals(1, result.targets().size());
        assertSame(change, result.targets().values().iterator().next().change());
    }

    @Test void duplicateModifiedWithoutCheckoutEntriesRemainOneVersionedFile() {
        var path = metadataOnlyPath("Source/PlayerExitGameComponent.h");
        var result = NativeChanges.collect(ProjectScope.of(directory), List.of(), List.of(path), List.of(path, path),
                "Perforce", name -> false, () -> {});
        assertEquals(1, result.groups().size());
        assertEquals("local", result.groups().getFirst().id());
        assertEquals(1, result.groups().getFirst().files().size());
        assertEquals(1, result.targets().size());
        assertSame(path, result.targets().values().iterator().next().modifiedWithoutCheckoutPath());
    }

}
