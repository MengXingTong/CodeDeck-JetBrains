package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConversationChangeTrackerTest {
    @TempDir
    Path root;

    @Test void resolvesGitRevertFilesInThePluginRuntime() throws Exception {
        var repository = Files.createDirectories(root.resolve("repo"));
        for (var args : List.of(List.of("init", "--quiet"), List.of("config", "user.name", "Test"),
            List.of("config", "user.email", "test@example.invalid"), List.of("config", "core.autocrlf", "false"))) git(repository, args);
        var path = Files.writeString(repository.resolve("file.cpp"), "base\n");
        git(repository, List.of("add", ".")); git(repository, List.of("commit", "--quiet", "-m", "base"));
        Files.writeString(path, "before\r\n");
        git(repository, List.of("add", ".")); git(repository, List.of("commit", "--quiet", "-m", "change"));
        var capture = new CommandFileCapture(root);
        capture.started("git", List.of(new ShellCommandInput("git -C repo revert --no-edit HEAD", root.toString())));
        assertEquals(List.of(path), capture.snapshot("git").stream().map(CommandFileCapture.Before::path).toList());
        git(repository, List.of("revert", "--no-edit", "HEAD"));
        assertEquals(List.of(path), capture.completed("git").stream().map(CommandFileCapture.Before::path).toList());
    }

    private void git(Path repository, List<String> args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.fsmonitor=false", "-c", "commit.gpgsign=false", "-C", repository.toString()));
        command.addAll(args);
        var output = root.resolve("git-test-output");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(output));
    }

    @Test void restoresP4ReadOnlyFilesAndKeepsRepositoryLineEndings() throws Exception {
        var path = write("file.cpp", "before\r\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "p4", "p4 revert file.cpp", root.toString());
        Files.writeString(path, "repository\n");
        var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class);
        if (dos != null) dos.setReadOnly(true);
        try {
            tracker.commandCompleted("session", "p4");
            assertEquals("repository\n", Files.readString(path));
            tracker.revertAll("session");
            assertEquals("before\r\n", Files.readString(path));
            assertTrue(tracker.listSummaries("session").isEmpty());
        } finally { if (dos != null) dos.setReadOnly(false); }
    }

    @Test void revertingReadOnlyAdditionsRemovesThemWithoutUnlockingUnchangedFiles() throws Exception {
        var untouched = write("untouched.cpp", "user\n");
        var added = root.resolve("restored.cpp");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "p4", "p4 revert restored.cpp untouched.cpp", root.toString());
        Files.writeString(added, "restored\n");
        var dos = Files.getFileAttributeView(untouched, java.nio.file.attribute.DosFileAttributeView.class);
        var addedDos = Files.getFileAttributeView(added, java.nio.file.attribute.DosFileAttributeView.class);
        if (dos != null) dos.setReadOnly(true);
        if (addedDos != null) addedDos.setReadOnly(true);
        try {
            tracker.revertAll("session");
            tracker.commandCompleted("session", "p4");
            assertFalse(Files.exists(added));
            assertEquals("user\n", Files.readString(untouched));
            if (dos != null) assertTrue(dos.readAttributes().isReadOnly());
        } finally { if (dos != null) dos.setReadOnly(false); }
    }

    @Test void earliestRawSnapshotWinsAgainstLateStructuredStartsAndProviderDiffs() throws Exception {
        var path = write("file.cpp", "first\r\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandsStarted("session", "raw", List.of(new ShellCommandInput("Set-Content file.cpp second", root.toString())));
        Files.writeString(path, "second\n");
        tracker.commandStarted("session", "structured", "Set-Content file.cpp second", root.toString());
        tracker.commandCompleted("session", "structured");
        tracker.commandCompleted("session", "raw");
        assertArrayEquals(bytes("first\r\n"), tracker.readDetails("session", path).beforeContent());
        assertEquals("second\r\n", Files.readString(path));
        tracker.revertAll("session");
        assertEquals("first\r\n", Files.readString(path));
    }

    @Test
    void firstTouchOwnsBaselineAcrossProviderAndUserEdits() throws IOException {
        var path = write("file.txt", "original\n");
        var tracker = new ConversationChangeTracker(root);

        assertTrue(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "provider\n");
        assertFalse(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "user-follow-up\n");

        var summary = tracker.listSummaries("session").getFirst();
        var details = tracker.readDetails("session", path);
        assertEquals(ChangeEntry.Kind.MODIFIED, summary.kind());
        assertEquals(1, summary.addedLines());
        assertEquals(1, summary.deletedLines());
        assertArrayEquals(bytes("original\n"), details.beforeContent());
        assertArrayEquals(bytes("user-follow-up\n"), details.afterContent());

        tracker.revert("session", path);
        assertEquals("original\n", Files.readString(path, StandardCharsets.UTF_8));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void shellAndRepeatedPatchesPreserveOriginalSeparatorsAndBom() throws IOException {
        var path = write("file.txt", "\ufeffold\r\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "shell", "Set-Content 'file.txt'", root.toString());
        Files.writeString(path, "new\n");
        tracker.commandCompleted("session", "shell");
        assertEquals("\ufeffnew\r\n", Files.readString(path));
        tracker.patchStarted("session", "patch1", List.of("file.txt"));
        Files.writeString(path, "second\n");
        tracker.patchCompleted("session", "patch1");
        tracker.patchStarted("session", "patch2", List.of("file.txt"));
        Files.writeString(path, "third\n");
        tracker.patchCompleted("session", "patch2");
        assertEquals("\ufeffthird\r\n", Files.readString(path));
        tracker.revert("session", path);
        assertEquals("\ufeffold\r\n", Files.readString(path));
    }

    @Test
    void formatOnlyChangesDisappearAndPreWriteHooksFinalizeAtTurnEnd() throws IOException {
        var path = write("file.txt", "original\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.patchStarted("session", "patch", List.of("file.txt"));
        Files.writeString(path, "original\r\n");
        tracker.patchCompleted("session", "patch");
        assertEquals("original\n", Files.readString(path));
        assertTrue(tracker.listSummaries("session").isEmpty());
        tracker.trackBeforeWrite("session", "file.txt");
        Files.writeString(path, "changed\r\n");
        tracker.finishCommands("session");
        assertEquals("changed\n", Files.readString(path));
        assertArrayEquals(bytes("original\n"), tracker.readDetails("session", path).beforeContent());
    }

    @Test
    void formatGuardCanBeDisabledAndNeverRewritesHistoryOrNewFiles() throws IOException {
        var path = write("file.txt", "before\r\n");
        var tracker = new ConversationChangeTracker(root, () -> false);
        tracker.patchStarted("session", "convert", List.of("file.txt"));
        Files.writeString(path, "after\n");
        tracker.patchCompleted("session", "convert");
        assertEquals("after\n", Files.readString(path));
        tracker = new ConversationChangeTracker(root);
        tracker.trackProviderFile("history", "file.txt", "update", "@@ -1 +1 @@\n-before\r\n+after\n");
        assertEquals("after\n", Files.readString(path));
        tracker.patchStarted("session", "add", List.of("new.txt"));
        var added = write("new.txt", "new\r\n");
        tracker.patchCompleted("session", "add");
        assertEquals("new\r\n", Files.readString(added));
    }

    @Test
    void summaryCountsOnlyChangedLinesAcrossSeparateRegions() throws IOException {
        var original = String.join("\n",
            "line 1", "line 2", "line 3", "line 4", "line 5", "line 6",
            "line 7", "line 8", "line 9", "line 10", "line 11", "line 12") + "\n";
        var changed = original
            .replace("line 2\n", "updated 2\n")
            .replace("line 11\n", "updated 11\n");
        write("file.txt", original);
        var tracker = new ConversationChangeTracker(root);

        tracker.trackBeforeWrite("session", "file.txt");
        write("file.txt", changed);

        var summary = tracker.listSummaries("session").getFirst();
        assertEquals(2, summary.addedLines());
        assertEquals(2, summary.deletedLines());
    }

    @Test
    void addedAndDeletedFilesRestoreTheirFirstBaseline() throws IOException {
        var added = root.resolve("added.txt");
        var deleted = write("deleted.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);

        assertTrue(tracker.trackBeforeWrite("session", "added.txt"));
        write("added.txt", "created\n");
        assertTrue(tracker.trackBeforeWrite("session", "deleted.txt"));
        Files.delete(deleted);

        var summaries = tracker.listSummaries("session");
        assertEquals(ChangeEntry.Kind.ADDED, summaries.get(0).kind());
        assertEquals(ChangeEntry.Kind.DELETED, summaries.get(1).kind());

        tracker.revertAll("session");
        assertFalse(Files.exists(added));
        assertEquals("before\n", Files.readString(deleted, StandardCharsets.UTF_8));
    }

    @Test
    void capturesCodexWholeFileAdditionsIncludingEmptyFiles() throws IOException {
        Files.createDirectories(root.resolve("Helper"));
        var header = write("Helper/JYInteractionPlacementHelper.h", "#pragma once\nclass FJYInteractionPlacementHelper {};\n");
        var empty = write("empty.txt", "");
        var tracker = new ConversationChangeTracker(root);

        tracker.trackProviderFile("session", header.toString(), "add", Files.readString(header));
        tracker.trackProviderFile("session", empty.toString(), "add", "");

        assertEquals(2, tracker.listSummaries("session").size());
        assertTrue(tracker.listSummaries("session").stream().allMatch(change -> change.kind() == ChangeEntry.Kind.ADDED));
        assertEquals(2, tracker.listSummaries("session").getFirst().addedLines());
        tracker.revertAll("session");
        assertFalse(Files.exists(header));
        assertFalse(Files.exists(empty));
    }

    @Test
    void capturesCodexWholeFileDeletionsAndKeepsTheFirstBaseline() throws IOException {
        var path = root.resolve("deleted.txt");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackProviderFile("session", path.toString(), "delete", "\ufeffbefore\r\n");
        assertEquals(ChangeEntry.Kind.DELETED, tracker.listSummaries("session").getFirst().kind());
        tracker.revertAll("session");
        assertArrayEquals(bytes("\ufeffbefore\r\n"), Files.readAllBytes(path));

        tracker.trackBeforeWrite("session", "deleted.txt");
        Files.delete(path);
        tracker.trackProviderFile("session", path.toString(), "delete", "later version\n");
        tracker.revertAll("session");
        assertArrayEquals(bytes("\ufeffbefore\r\n"), Files.readAllBytes(path));
    }

    @Test
    void historicalMovesWithoutDestinationBytesNeverInventADestructiveRollback() throws IOException {
        var destination = write("moved.txt", "after\n");
        var original = root.resolve("old.txt");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackProviderDiff("session", "turn", textDiff("moved.txt", "before", "after"));
        tracker.trackProviderFile("session", original.toString(), "update", "@@ -1 +1 @@\n-before\n+after\n\n\nMoved to: " + destination, destination.toString());
        assertEquals(List.of(ChangeEntry.Kind.MODIFIED, ChangeEntry.Kind.DELETED),
            tracker.listSummaries("session").stream().map(ConversationChangeTracker.ChangeSummary::kind).toList());
        assertFalse(tracker.listSummaries("session").getFirst().reversible());
        tracker.revert("session", original);
        assertEquals("after\n", Files.readString(destination));
        assertEquals("before\n", Files.readString(original));
    }

    @Test
    void rawPatchReservationsPreserveOverwrittenDestinationsAndDiscardFailedNoOps() throws IOException {
        var source = write("source.txt", "source before\n");
        var destination = write("destination.txt", "destination before\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.patchStarted("session", "call", List.of(source.toString(), destination.toString()));
        Files.delete(source); Files.writeString(destination, "after\n");
        tracker.trackProviderFile("session", source.toString(), "update", "@@ -1 +1 @@\n-source before\n+after\n", destination.toString());
        tracker.patchCompleted("session", "call");
        tracker.revertAll("session");
        assertEquals("source before\n", Files.readString(source));
        assertEquals("destination before\n", Files.readString(destination));
        tracker.patchStarted("session", "failed", List.of(destination.toString()));
        tracker.patchCompleted("session", "failed");
        Files.writeString(destination, "later user edit\n");
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void acceptClearsBaselineAndNextTouchStartsFromCurrentContent() throws IOException {
        var path = write("file.txt", "original\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "file.txt");
        write("file.txt", "accepted\n");

        tracker.accept("session", path);
        assertFalse(tracker.isTracked("session", path));
        assertTrue(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "second\n");

        assertArrayEquals(bytes("accepted\n"), tracker.readDetails("session", path).beforeContent());
    }

    @Test
    void providerDiffCreatesMembershipWithoutScanningUnreportedFiles() throws IOException {
        var tracked = write("tracked.txt", "after\n");
        write("unrelated.txt", "user-only\n");
        var tracker = new ConversationChangeTracker(root);

        tracker.trackProviderDiff("session", textDiff("tracked.txt", "before", "after"));

        assertEquals(1, tracker.listSummaries("session").size());
        assertArrayEquals(bytes("before\n"), tracker.readDetails("session", tracked).beforeContent());
        assertFalse(tracker.isTracked("session", root.resolve("unrelated.txt")));
    }

    @Test
    void staleProviderDiffDoesNotCreateAnInvalidBaseline() throws IOException {
        write("file.txt", "latest\n");
        var tracker = new ConversationChangeTracker(root);

        tracker.trackProviderDiff("session", "turn-1", textDiff("file.txt", "before", "intermediate"));

        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void latestCumulativeDiffReplacesBaselineDerivedDuringTheSameTurn() throws IOException {
        var path = write("file.txt", "new first\nsame\nnew last\n");
        var tracker = new ConversationChangeTracker(root);
        var earlyDiff = textDiff("file.txt", "old first", "new first");
        var cumulativeDiff = """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1 +1 @@
            -old first
            +new first
            @@ -3 +3 @@
            -old last
            +new last
            """;

        tracker.trackProviderDiff("session", "turn-1", earlyDiff);
        tracker.trackProviderDiff("session", "turn-1", cumulativeDiff);

        assertArrayEquals(bytes("old first\nsame\nold last\n"),
            tracker.readDetails("session", path).beforeContent());
    }

    @Test
    void binaryProviderModificationIsNotMisclassifiedAsAnAddedFile() throws IOException {
        write("asset.bin", "current\n");
        var tracker = new ConversationChangeTracker(root);
        var diff = """
            diff --git a/asset.bin b/asset.bin
            Binary files a/asset.bin and b/asset.bin differ
            """;

        tracker.trackProviderDiff("session", "turn-1", diff);

        var summary = tracker.listSummaries("session").getFirst();
        assertEquals(ChangeEntry.Kind.MODIFIED, summary.kind());
        assertFalse(summary.reversible());
    }

    @Test
    void malformedAndOutsideWorkspacePathsNeverBecomeMembers() {
        var tracker = new ConversationChangeTracker(root);

        assertFalse(tracker.trackBeforeWrite("session", ""));
        assertFalse(tracker.trackBeforeWrite("session", root.resolveSibling("outside.txt").toString()));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void largeFilesAreTrackedButNotReversible() throws IOException {
        var path = root.resolve("large.bin");
        var content = new byte[(int) ConversationChangeTracker.MAX_BASELINE_BYTES + 1];
        Files.write(path, content);
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "large.bin");
        content[0] = 1;
        Files.write(path, content);

        var summary = tracker.listSummaries("session").getFirst();
        assertFalse(summary.reversible());
        assertThrows(IOException.class, () -> tracker.revert("session", path));
    }

    @Test
    void clearSessionReleasesMembership() throws IOException {
        var path = write("file.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "file.txt");

        tracker.clearSession("session");

        assertFalse(tracker.isTracked("session", path));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void sessionRefreshPublishesChangesWrittenByAnExternalProvider() throws IOException {
        var path = write("file.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);
        var updates = new ArrayList<ConversationChangeTracker.ChangeUpdate>();
        tracker.trackBeforeWrite("session", path.toString());
        tracker.addListener(updates::add);
        write("file.txt", "after\n");

        tracker.refreshSession("session");

        assertEquals(1, updates.size());
        assertEquals(path, updates.getFirst().changes().getFirst().path());
    }

    @Test
    void shellWritesAndDeletionPublishReversibleChangesWithoutProviderDiffs() throws IOException {
        var modified = write("changed.cpp", "\ufeffbefore\r\n");
        var deleted = write("deleted.cpp", "restore me\r\n");
        var tracker = new ConversationChangeTracker(root);
        var updates = new ArrayList<ConversationChangeTracker.ChangeUpdate>();
        tracker.addListener(updates::add);
        tracker.commandStarted("session", "cmd", "Set-Content 'changed.cpp' 'after'; Remove-Item 'deleted.cpp'; Set-Content 'new.cpp' 'new'", root.toString());
        assertTrue(tracker.listSummaries("session").isEmpty());
        Files.writeString(modified, "after\n"); Files.delete(deleted); write("new.cpp", "new\n");
        tracker.commandCompleted("session", "cmd");

        assertEquals(3, tracker.listSummaries("session").size());
        assertTrue(tracker.listSummaries("other-session").isEmpty());
        var details = tracker.readDetails("session", deleted);
        assertEquals(ChangeEntry.Kind.DELETED, details.kind());
        assertArrayEquals(bytes("restore me\r\n"), details.beforeContent());
        assertEquals(null, details.afterContent());
        assertTrue(details.unifiedDiff().contains("+++ /dev/null"));
        assertEquals(1, tracker.listSummaries("session").stream().filter(c -> c.kind() == ChangeEntry.Kind.DELETED).findFirst().orElseThrow().deletedLines());
        assertFalse(updates.isEmpty());
        tracker.revertAll("session");
        assertArrayEquals(bytes("\ufeffbefore\r\n"), Files.readAllBytes(modified));
        assertArrayEquals(bytes("restore me\r\n"), Files.readAllBytes(deleted));
        assertFalse(Files.exists(root.resolve("new.cpp")));
    }

    @Test
    void shellCaptureRetainsFirstBaselineAcrossCommandsAndFinishesInterruptedCommands() throws IOException {
        var file = write("file.cpp", "first\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "cmd1", "Set-Content file.cpp second", null);
        write("file.cpp", "second\n"); tracker.commandCompleted("session", "cmd1");
        tracker.commandStarted("session", "cmd2", "Remove-Item file.cpp", null); Files.delete(file);
        tracker.finishCommands("session");
        assertArrayEquals(bytes("first\n"), tracker.readDetails("session", file).beforeContent());
        tracker.revert("session", file);
        assertEquals("first\n", Files.readString(file));
    }

    @Test
    void acceptedAndClearedCommandsCannotReappearFromLateCompletions() throws IOException {
        var file = write("file.cpp", "first\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "cmd1", "Set-Content file.cpp second", null);
        write("file.cpp", "second\n"); tracker.accept("session", file);
        tracker.commandCompleted("session", "cmd1");
        assertTrue(tracker.listSummaries("session").isEmpty());
        tracker.commandStarted("session", "cmd2", "Remove-Item file.cpp", null);
        tracker.clearSession("session"); Files.delete(file); tracker.commandCompleted("session", "cmd2");
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void revertedChangesCannotBeReintroducedByAnAlreadyRunningCommand() throws IOException {
        var file = write("file.cpp", "first\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.commandStarted("session", "cmd1", "Set-Content file.cpp second", null);
        write("file.cpp", "second\n"); tracker.commandCompleted("session", "cmd1");
        tracker.commandStarted("session", "cmd2", "Set-Content file.cpp third", null);
        write("file.cpp", "third\n"); tracker.revertAll("session");
        tracker.commandCompleted("session", "cmd2");
        assertTrue(tracker.listSummaries("session").isEmpty());
        assertEquals("first\n", Files.readString(file));
    }

    private Path write(String relativePath, String content) throws IOException {
        var path = root.resolve(relativePath);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String textDiff(String path, String before, String after) {
        return """
            diff --git a/%1$s b/%1$s
            --- a/%1$s
            +++ b/%1$s
            @@ -1 +1 @@
            -%2$s
            +%3$s
            """.formatted(path, before, after);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
