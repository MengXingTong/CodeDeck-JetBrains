package com.codexgui.changelist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChangeTreeTest {
    @TempDir Path base;
    private ChangedFile file(String path, String id) {
        return new ChangedFile(id, base.resolve(path), ChangedFile.Kind.MODIFIED, false);
    }
    @Test void largeTreeBuildStopsWhenCancellationIsRequested() {
        var files = java.util.stream.IntStream.range(0, 10000)
                .mapToObj(index -> file("src/" + index + ".cs", "file-" + index)).toList();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class, () -> ChangeTree.build(
                List.of(new ChangeTree.Group("default", "Changes", files)), base, () -> {
                    if (checks.incrementAndGet() == 100) throw new java.util.concurrent.CancellationException();
                }));
        assertEquals(100, checks.get());
    }
    @Test void retainsNativeChangelistsAndSortsFoldersBeforeFiles() {
        var root = ChangeTree.build(List.of(new ChangeTree.Group("p4-123", "Fix bug", List.of(
                file("src/B.cs", "b"), file("B.cs", "root-b"), file("src/A.cs", "a"))),
                new ChangeTree.Group("git-default", "Git changes", List.of(file("src/A.cs", "git-a")))), base);
        assertEquals(List.of("Fix bug (3)", "Git changes (1)"), root.children().stream().map(ChangeTree.Node::label).toList());
        var change = root.children().getFirst();
        assertEquals(List.of("src", "B.cs"), change.children().stream().map(ChangeTree.Node::label).toList());
        assertEquals(List.of("A.cs", "B.cs"), change.children().getFirst().children().stream().map(ChangeTree.Node::label).toList());
        assertNotEquals(change.children().getFirst().children().getFirst().id(),
                root.children().get(1).children().getFirst().children().getFirst().id());
    }
    @Test void idsDoNotChangeWhenAChangelistIsRenamedOrFileStatusChanges() {
        var before = ChangeTree.build(List.of(new ChangeTree.Group("stable", "Old name", List.of(file("src/A.cs", "a")))), base);
        var after = ChangeTree.build(List.of(new ChangeTree.Group("stable", "New name", List.of(
                new ChangedFile("a", base.resolve("src/A.cs"), ChangedFile.Kind.ADDED, false)))), base);
        assertEquals(before.children().getFirst().id(), after.children().getFirst().id());
        assertEquals(before.children().getFirst().children().getFirst().children().getFirst().id(),
                after.children().getFirst().children().getFirst().children().getFirst().id());
    }
    @Test void externalVcsRootsAreRejectedWhileProjectBinaryFilesRemainVisible() {
        var external = new ChangedFile("external", base.getParent().resolve("outside/asset.uasset"), ChangedFile.Kind.ADDED, true);
        var asset = new ChangedFile("asset", base.resolve("Content/asset.uasset"), ChangedFile.Kind.ADDED, true);
        var root = ChangeTree.build(List.of(new ChangeTree.Group("empty", "Empty", List.of()),
                new ChangeTree.Group("outside", "Other project", List.of(external)),
                new ChangeTree.Group("assets", "Assets", List.of(external, asset))), base);
        assertEquals(1, root.children().size());
        var cursor = root.children().getFirst();
        assertEquals("Assets (1)", cursor.label());
        assertEquals("Content", cursor.children().getFirst().label());
        while (cursor.file() == null) cursor = cursor.children().getFirst();
        assertTrue(cursor.file().binary()); assertEquals(asset.path(), cursor.file().path());
    }
    @Test void unknownOrDriveRootProjectNeverShowsAbsolutePathBranches() {
        var groups = List.of(new ChangeTree.Group("all", "Changes", List.of(file("src/A.cs", "a"))));
        assertTrue(ChangeTree.build(groups, null).children().isEmpty());
        assertTrue(ChangeTree.build(groups, base.getRoot()).children().isEmpty());
    }
    @Test void directoryMenuPathsAreIndependentOfChangelistNamesAndIds() {
        var root = ChangeTree.build(List.of(new ChangeTree.Group("list/123", "Fix / folder", List.of(
                file("Source/Tasks/a.cs", "a"), file("Other/Tasks/b.cs", "b")))), base);
        var group = root.children().getFirst();
        assertEquals(base, group.path());
        assertEquals(base.resolve("Other"), group.children().get(0).path());
        assertEquals(base.resolve("Source"), group.children().get(1).path());
        var tasks = group.children().get(1).children().getFirst();
        assertEquals(base.resolve("Source/Tasks"), tasks.path());
        assertEquals(base.resolve("Source/Tasks/a.cs"), tasks.children().getFirst().path());
        assertTrue(ProjectScope.of(base).contains(tasks.path()));
    }
    @Test void utf16IsTextButBinaryNulAndControlPayloadsAreRejected() {
        assertTrue(TextGuard.isProbablyText("中文\r\ntext".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(TextGuard.isProbablyText("中文\r\ntext".getBytes(java.nio.charset.StandardCharsets.UTF_16)));
        assertTrue(TextGuard.isProbablyText(new byte[0]));
        assertFalse(TextGuard.isProbablyText(new byte[]{65, 0, 66}));
        assertFalse(TextGuard.isProbablyText(new byte[]{1, 2, 3, 4, 5, 6}));
        assertFalse(TextGuard.isProbablyText(new byte[]{(byte) 255, (byte) 254, 0, 0}));
    }
}
