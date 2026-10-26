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

    @Test void capturesPythonInterpolatedPathsInInlinePowerShellScripts() throws Exception {
        var ability = Files.createDirectories(root.resolve("Source/Game/Ability"));
        var medicine = Files.writeString(ability.resolve("GMedicineGameplayAbility.cpp"), "medicine before\r\n");
        var prop = Files.writeString(ability.resolve("GPropGameplayAbility.cpp"), "prop before\r\n");
        var unrelated = Files.writeString(ability.resolve("Other.cpp"), "user before");
        var capture = new CommandFileCapture(root);
        capture.started("inline", """
            powershell.exe -Command "$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            @'
            from pathlib import Path
            root = Path.cwd()
            changed = []
            for kind, rid in [('Medicine', 'medicineRid'), ('Prop', 'propRid')]:
                path = root / f'Source/Game/Ability/G{kind}GameplayAbility.cpp'
                changed.append((path, path.read_bytes(), b'after'))
            for path, original, data in changed:
                path.write_bytes(data)
            '@ | python -"
            """, root.toString());
        assertEquals(Set.of(medicine, prop), capture.snapshot("inline").stream()
            .filter(CommandFileCapture.Before::exists).map(CommandFileCapture.Before::path)
            .collect(java.util.stream.Collectors.toSet()));
        Files.writeString(medicine, "medicine after\n"); Files.writeString(prop, "prop after\n");
        Files.writeString(unrelated, "concurrent user edit");
        var changes = capture.completed("inline");
        assertEquals(Set.of(medicine, prop), changes.stream().map(CommandFileCapture.Before::path)
            .collect(java.util.stream.Collectors.toSet()));
        assertArrayEquals("medicine before\r\n".getBytes(StandardCharsets.UTF_8), changes.stream()
            .filter(before -> before.path().equals(medicine)).findFirst().orElseThrow().bytes());
    }

    @Test void interpolatedScopesDetectAdditionsDeletionsAndRespectIgnoreRules() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var deleted = Files.writeString(source.resolve("BattleItemHelper.h"), "header before");
        var kept = Files.writeString(source.resolve("BattleItemHelper.cpp"), "source before");
        var ignored = Files.writeString(source.resolve("BattleItemHelper.log"), "log before");
        var excluded = Files.writeString(source.resolve("BattleItemHelper.private"), "private before");
        var unrelated = Files.writeString(source.resolve("other.cpp"), "user before");
        var capture = new CommandFileCapture(root, (path, directory) -> path.equals(excluded));
        capture.started("extensions", "python -c \"path = Path(f'Source/BattleItemHelper.{suffix}'); path.write_bytes(data)\"", null);
        Files.delete(deleted); Files.writeString(kept, "source after");
        var added = Files.writeString(source.resolve("BattleItemHelper.txt"), "new file");
        Files.writeString(ignored, "background log"); Files.writeString(excluded, "private after");
        Files.writeString(unrelated, "concurrent user edit");
        assertEquals(Set.of(deleted, kept, added), capture.completed("extensions").stream()
            .map(CommandFileCapture.Before::path).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void interpolatedScopesRemainBoundedAndDoNotClaimReadOnlyOrRootPaths() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("GMedicine.cpp"), "before");
        var rootFile = Files.writeString(root.resolve("GMedicine.cpp"), "root before");
        var capture = new CommandFileCapture(root);
        for (var command : List.of("Get-Content f'Source/G{kind}.cpp'", "python -c \"Path(f'G{kind}.cpp').write_bytes(data)\"")) {
            capture.started("excluded", command, null);
            assertTrue(capture.snapshot("excluded").stream().noneMatch(CommandFileCapture.Before::exists), command);
            Files.writeString(file, "user edit"); Files.writeString(rootFile, "root edit");
            assertTrue(capture.completed("excluded").isEmpty(), command);
        }
        for (int i = 0; i < 520; i++) Files.writeString(source.resolve("other-" + i + ".txt"), "before");
        capture.started("oversized", "python -c \"Path(f'Source/G{kind}.cpp').write_bytes(data)\"", null);
        Files.writeString(file, "after oversized command");
        assertTrue(capture.completed("oversized").isEmpty());
    }

    @Test void filenameTemplatesDoNotTraverseUnrelatedChildTreesOrResolveExpressions() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("GMedicine.cpp"), "before");
        var child = Files.createDirectories(source.resolve("Other"));
        for (int i = 0; i < 520; i++) Files.writeString(child.resolve(i + ".cpp"), "user before");
        var capture = new CommandFileCapture(root);
        capture.started("template", "python -c \"Path(f'Source/G{kind}.cpp').write_bytes(data)\"", null);
        Files.writeString(file, "after"); Files.writeString(child.resolve("0.cpp"), "concurrent user edit");
        assertEquals(List.of(file), capture.completed("template").stream().map(CommandFileCapture.Before::path).toList());
        for (var command : List.of("python -c \"Path(f'Source/{helper()}.cpp').write_bytes(data)\"",
            "python -c \"Path(f'../Source/G{kind}.cpp').write_bytes(data)\"")) {
            capture.started("unknown", command, null);
            Files.writeString(file, "after unknown expression");
            assertTrue(capture.completed("unknown").isEmpty(), command);
        }
    }

    @Test void inferredScopesExcludeBackgroundLogsAndBuildOutputsBeforeReservingBaselines() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var header = Files.writeString(source.resolve("BattleInMatchTaskComponent.h"), "before");
        var log = Files.writeString(source.resolve("InMatchUnifiedInteractionBuild20261023.log"), "old log");
        var library = Files.write(source.resolve("UnrealEditor-GAbilitySystem.LIB"), new byte[]{1, 0, 2});
        var generated = Files.createDirectories(source.resolve("Intermediate/Build"));
        var generatedSource = Files.writeString(generated.resolve("generated.cpp"), "old generated source");
        var capture = new CommandFileCapture(root);
        capture.started("scope", "generator Source", null);
        assertEquals(List.of(header), capture.snapshot("scope").stream().map(CommandFileCapture.Before::path).toList());
        Files.writeString(header, "after"); Files.writeString(log, "background log"); Files.delete(library);
        Files.writeString(generatedSource, "regenerated"); Files.writeString(source.resolve("another.log"), "new log");
        assertEquals(List.of(header), capture.completed("scope").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void knownReadOnlyCommandsDoNotClaimConcurrentChangesEvenInReferencedSourceFiles() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("file.cpp"), "before");
        var capture = new CommandFileCapture(root);
        for (var command : List.of("rg -n pattern Source", "Get-Content Source/file.cpp | Select-String pattern",
            "powershell.exe -NoProfile -Command \"Get-ChildItem Source; Get-Content Source/file.cpp\"",
            "cmd /c type Source/file.cpp", "git diff -- Source/file.cpp")) {
            capture.started("read", command, null);
            assertTrue(capture.snapshot("read").isEmpty(), command);
            Files.writeString(file, Files.readString(file) + "user edit");
            assertTrue(capture.completed("read").isEmpty(), command);
        }
    }

    @Test void mixedReadsAndWritesOnlyReserveTheWritingSegmentsAndExplicitArtifactsStillWork() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var read = Files.writeString(source.resolve("read.cpp"), "read before");
        var log = Files.writeString(source.resolve("explicit.log"), "log before");
        var library = Files.write(source.resolve("explicit.lib"), new byte[]{1, 0, 2});
        var capture = new CommandFileCapture(root, (path, directory) -> true);
        capture.started("mixed", "Get-Content Source/read.cpp; Set-Content Source/explicit.log after; Remove-Item Source/explicit.lib", null);
        assertEquals(Set.of(log, library), capture.snapshot("mixed").stream().map(CommandFileCapture.Before::path)
            .collect(java.util.stream.Collectors.toSet()));
        Files.writeString(read, "concurrent user edit"); Files.writeString(log, "after"); Files.delete(library);
        assertEquals(Set.of(log, library), capture.completed("mixed").stream().map(CommandFileCapture.Before::path)
            .collect(java.util.stream.Collectors.toSet()));
    }

    @Test void writingAScriptDoesNotWatchItsParentButExecutingItStillFindsComputedSourceEdits() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var script = Files.writeString(source.resolve("worker.py"), "before");
        var other = Files.writeString(source.resolve("other.cpp"), "other before");
        var capture = new CommandFileCapture(root);
        capture.started("write", "Set-Content Source/worker.py after", null);
        Files.writeString(script, "after"); Files.writeString(other, "concurrent edit");
        assertEquals(List.of(script), capture.completed("write").stream().map(CommandFileCapture.Before::path).toList());
        capture.started("execute", "python Source/worker.py", null);
        Files.writeString(other, "computed edit"); Files.writeString(source.resolve("worker.log"), "runtime log");
        assertEquals(List.of(other), capture.completed("execute").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void explicitWritesInGeneratedDirectoriesAndShellRedirectionBypassInferredIgnores() throws Exception {
        var source = Files.createDirectories(root.resolve("Saved/Logs"));
        var capture = new CommandFileCapture(root);
        for (var command : List.of("echo entry > Saved/Logs/output.log",
            "Get-Content input.txt | Out-File Saved/Logs/output.log",
            "[IO.File]::WriteAllText('Saved/Logs/output.log', 'entry')",
            "node -e \"require('fs').writeFileSync('Saved/Logs/output.log', 'entry')\"",
            "python -c \"open('Saved/Logs/output.log', 'w').write('entry')\"")) {
            Files.deleteIfExists(source.resolve("output.log"));
            capture.started("write", command, null);
            Files.writeString(source.resolve("output.log"), "entry");
            assertEquals(List.of(source.resolve("output.log")), capture.completed("write").stream()
                .map(CommandFileCapture.Before::path).toList(), command);
        }
    }

    @Test void projectIgnoreRulesFilterBothExistingAndNewInferredFiles() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var header = Files.writeString(source.resolve("file.cpp"), "before");
        var excluded = Files.writeString(source.resolve("project-noise.txt"), "noise");
        var capture = new CommandFileCapture(root, (path, directory) -> path.getFileName().toString().startsWith("project-"));
        capture.started("scope", "generator Source", null);
        assertEquals(List.of(header), capture.snapshot("scope").stream().map(CommandFileCapture.Before::path).toList());
        Files.writeString(header, "after"); Files.writeString(excluded, "background");
        Files.writeString(source.resolve("project-new.txt"), "new noise");
        assertEquals(List.of(header), capture.completed("scope").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void commandsWithSubstitutionsAndUnknownHelpersKeepBoundedFallbackCapture() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("file.cpp"), "before");
        Files.writeString(source.resolve("helper.py"), "# writes computed paths");
        var capture = new CommandFileCapture(root);
        capture.started("read-with-side-effect", "Get-Content $(python Source/helper.py)", null);
        Files.writeString(file, "after");
        assertEquals(List.of(file), capture.completed("read-with-side-effect").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void aWritingPipelineRetainsInputDirectoryScopeAndExplicitVcsRestoresKeepIgnoredFiles() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("file.cpp"), "before");
        var log = Files.writeString(source.resolve("output.log"), "log before");
        var capture = new CommandFileCapture(root);
        capture.started("pipeline", "Get-ChildItem Source | ForEach-Object { Set-Content $_.FullName after }", null);
        Files.writeString(file, "pipeline after"); Files.writeString(log, "background log");
        assertEquals(List.of(file), capture.completed("pipeline").stream().map(CommandFileCapture.Before::path).toList());
        capture.started("restore", "p4 revert Source/output.log", null);
        Files.writeString(log, "restored log");
        var restored = capture.completed("restore");
        assertEquals(List.of(log), restored.stream().map(CommandFileCapture.Before::path).toList());
        assertFalse(restored.getFirst().preserveFormat());
    }

    @Test void overlyComplexShellSyntaxKeepsFallbackInsteadOfBeingAssumedReadOnly() throws Exception {
        var source = Files.createDirectories(root.resolve("Source"));
        var file = Files.writeString(source.resolve("file.cpp"), "before");
        var capture = new CommandFileCapture(root);
        capture.started("complex", "rg Source " + " | Select-String pattern".repeat(130) + " | generator", null);
        Files.writeString(file, "after");
        assertEquals(List.of(file), capture.completed("complex").stream().map(CommandFileCapture.Before::path).toList());
    }

    @Test void gitRevertResolvesCommitFilesWithoutScanningTheWorktreeOrCapturingGitInternals() throws Exception {
        git("init", "--quiet"); git("config", "user.name", "Test"); git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(root.resolve("modified.cpp"), "base\n");
        Files.writeString(root.resolve("deleted.cpp"), "deleted base\n");
        git("add", "."); git("commit", "--quiet", "-m", "base");
        Files.writeString(root.resolve("modified.cpp"), "before\r\n");
        Files.delete(root.resolve("deleted.cpp")); Files.writeString(root.resolve("added.cpp"), "added\n");
        git("add", "."); git("commit", "--quiet", "-m", "change");
        for (int i = 0; i < 520; i++) Files.writeString(root.resolve("unrelated-" + i + ".txt"), "user content");
        var capture = new CommandFileCapture(root);
        capture.prepare();
        // A native command may finish before its streamed started event reaches the plugin.
        git("revert", "--no-edit", "HEAD");
        capture.started("git", "git revert --no-edit HEAD", root.toString());
        var changes = capture.completed("git");
        assertEquals(Set.of("modified.cpp", "deleted.cpp", "added.cpp"), changes.stream()
            .map(before -> before.path().getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(changes.stream().noneMatch(CommandFileCapture.Before::preserveFormat));
        assertFalse(changes.stream().filter(before -> before.path().endsWith("deleted.cpp")).findFirst().orElseThrow().exists());
    }

    @Test void explicitDirectoryScopesExcludeVcsDatabases() throws Exception {
        var scope = Files.createDirectories(root.resolve("repo"));
        var index = Files.createDirectories(scope.resolve(".git")).resolve("index");
        Files.writeString(index, "old index"); Files.writeString(scope.resolve("file.cpp"), "old");
        var capture = new CommandFileCapture(root);
        capture.started("scope", "generator repo", null);
        Files.writeString(index, "new index"); Files.writeString(scope.resolve("file.cpp"), "new");
        assertEquals(List.of(scope.resolve("file.cpp")), capture.completed("scope").stream().map(CommandFileCapture.Before::path).toList());
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.fsmonitor=false", "-C", root.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

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
