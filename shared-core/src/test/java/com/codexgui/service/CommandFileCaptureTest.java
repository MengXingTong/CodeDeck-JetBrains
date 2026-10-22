package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandFileCaptureTest {
    @TempDir Path root;

    @Test void capturesPowerShellArrayWritesWithoutScanningTheWorkspace() throws Exception {
        var names = List.of("NpcGroupBase.h", "NpcGroupBase.cpp", "NpcCreateComponent.h", "NpcCreateComponent.cpp");
        var source = Files.createDirectories(root.resolve("Source/NPC"));
        for (var name : names) Files.writeString(source.resolve(name), "\ufeffbefore\r\n");
        var unrelated = root.resolve("unrelated.txt"); Files.writeString(unrelated, "before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", """
            powershell.exe -Command '$taskFiles = @('Source\\NPC\\NpcGroupBase.h',
              'Source\\NPC\\NpcGroupBase.cpp', 'Source\\NPC\\NpcCreateComponent.h', 'Source\\NPC\\NpcCreateComponent.cpp');
            foreach ($file in $taskFiles) { [IO.File]::WriteAllText((Join-Path (Get-Location) $file), $contents[$file]) }'
            """, root.toString());
        for (var name : names) Files.writeString(source.resolve(name), "after\n");
        Files.writeString(unrelated, "user edit");

        var changes = capture.completed("cmd");
        assertEquals(4, changes.size());
        for (var before : changes) {
            assertTrue(before.exists());
            assertArrayEquals("\ufeffbefore\r\n".getBytes(StandardCharsets.UTF_8), before.bytes());
            assertTrue(before.path().startsWith(source));
        }
        assertTrue(capture.completed("cmd").isEmpty());
    }

    @Test void capturesDeletionAdditionAndPathsWithSpacesUsingCommandCwd() throws Exception {
        var source = Files.createDirectories(root.resolve("source folder"));
        var deleted = source.resolve("Old.cpp"); Files.writeString(deleted, "old");
        var modified = source.resolve("新 文件.cpp"); Files.writeString(modified, "before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "Remove-Item -LiteralPath 'Old.cpp'; Set-Content 'New.cpp' 'new'; Set-Content '新 文件.cpp' 'after'", source.toString());
        Files.delete(deleted); Files.writeString(source.resolve("New.cpp"), "new"); Files.writeString(modified, "after");
        var changes = capture.finish();
        assertEquals(3, changes.size());
        assertEquals("old", new String(changes.stream().filter(c -> c.path().equals(deleted)).findFirst().orElseThrow().bytes(), StandardCharsets.UTF_8));
        assertFalse(changes.stream().filter(c -> c.path().equals(source.resolve("New.cpp"))).findFirst().orElseThrow().exists());
        assertTrue(capture.finish().isEmpty());
    }

    @Test void readOnlyCommandsAndUnknownCompletionsDoNotCreateChanges() throws Exception {
        Files.writeString(root.resolve("read.txt"), "before");
        var capture = new CommandFileCapture(root);
        capture.started("read", "Get-Content 'read.txt'", null);
        assertTrue(capture.completed("read").isEmpty());
        Files.writeString(root.resolve("read.txt"), "user edit after command");
        assertTrue(capture.completed("missing-start").isEmpty());
        assertTrue(capture.finish().isEmpty());
    }

    @Test void supportsExtensionlessDeletedFilesAndSkipsOutsideAndOversizedFiles() throws Exception {
        var readme = root.resolve("README"); Files.writeString(readme, "before");
        var large = root.resolve("large.bin"); Files.write(large, new byte[5 * 1024 * 1024 + 1]);
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "rm README ../outside.txt large.bin", null);
        Files.delete(readme); Files.delete(large);
        assertEquals(List.of(readme), capture.completed("cmd").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void discardAndForgetPreventAcceptedOrClosedChangesReappearing() throws Exception {
        var file = root.resolve("file.txt"); Files.writeString(file, "before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "Set-Content file.txt after", null);
        capture.forget(file); Files.writeString(file, "after");
        assertTrue(capture.completed("cmd").isEmpty());
        capture.started("cmd2", "rm file.txt", null); capture.clear(); Files.delete(file);
        assertTrue(capture.finish().isEmpty());
    }

    @Test void retainedBaselinesHaveAnAggregateMemoryLimit() throws Exception {
        for (var name : List.of("a.bin", "b.bin", "c.bin", "d.bin")) Files.write(root.resolve(name), new byte[5 * 1024 * 1024]);
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "rm a.bin b.bin c.bin d.bin", null);
        for (var name : List.of("a.bin", "b.bin", "c.bin", "d.bin")) Files.delete(root.resolve(name));
        assertEquals(3, capture.completed("cmd").size());
    }

    @Test void capturesComputedPathsInsideExplicitSmallDirectoriesAndExecutedScripts() throws Exception {
        var scope = Files.createDirectories(root.resolve("source"));
        var existing = scope.resolve("modified.txt"); Files.writeString(existing, "before");
        var deleted = scope.resolve("deleted.txt"); Files.writeString(deleted, "delete before");
        var outside = root.resolve("user.txt"); Files.writeString(outside, "user before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "$base = Join-Path (Get-Location) 'source'; Run-Generator $base", null);
        Files.writeString(existing, "after"); Files.delete(deleted);
        var added = scope.resolve("LICENSE"); Files.writeString(added, "new");
        Files.writeString(outside, "user edit");
        var changes = capture.completed("cmd");
        assertEquals(Set.of(existing, deleted, added), changes.stream().map(CommandFileCapture.Before::path).collect(java.util.stream.Collectors.toSet()));
        assertFalse(changes.stream().filter(c -> c.path().equals(added)).findFirst().orElseThrow().exists());

        Files.writeString(scope.resolve("worker.mjs"), "// arbitrary computed output paths");
        capture.started("script", "node source/worker.mjs", null);
        Files.writeString(existing, "script after"); Files.writeString(scope.resolve("generated.txt"), "script output");
        capture.forget(scope.resolve("generated.txt"));
        assertEquals(List.of(existing), capture.completed("script").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void renameNewNameResolvesAgainstTheSourceParent() throws Exception {
        var scope = Files.createDirectories(root.resolve("source"));
        Files.writeString(scope.resolve("old.txt"), "before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "Rename-Item -LiteralPath source/old.txt -NewName new.txt", null);
        Files.move(scope.resolve("old.txt"), scope.resolve("new.txt"));
        assertEquals(2, capture.completed("cmd").size());
        var spaced = Files.createDirectories(root.resolve("source folder"));
        Files.writeString(spaced.resolve("旧 文件.txt"), "before");
        capture.started("spaced", "Rename-Item -LiteralPath 'source folder/旧 文件.txt' -NewName '新 文件.txt'", null);
        Files.move(spaced.resolve("旧 文件.txt"), spaced.resolve("新 文件.txt"));
        assertEquals(2, capture.completed("spaced").size());
        capture.started("cmd-ren", "cmd /c ren \"source folder/新 文件.txt\" final.txt", null);
        Files.move(spaced.resolve("新 文件.txt"), spaced.resolve("final.txt"));
        assertEquals(2, capture.completed("cmd-ren").size());
    }

    @Test void directoryScopesNeverEnumerateTheWorkspaceRootOrAssumeOversizedTreesAreEmpty() throws Exception {
        var scope = Files.createDirectories(root.resolve("large"));
        for (int i = 0; i < 520; i++) Files.writeString(scope.resolve(i + ".txt"), "before");
        var capture = new CommandFileCapture(root);
        capture.started("cmd", "generator . large", null);
        Files.writeString(root.resolve("unrelated.txt"), "outside scope");
        Files.writeString(scope.resolve("generated.txt"), "output");
        assertTrue(capture.completed("cmd").isEmpty());
    }
}
