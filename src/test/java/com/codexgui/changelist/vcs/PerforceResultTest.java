package com.codexgui.changelist.vcs;

import com.intellij.openapi.vcs.VcsException;
import org.jetbrains.idea.perforce.StreamGobbler;
import org.jetbrains.idea.perforce.perforce.ExecResult;
import org.jetbrains.idea.perforce.perforce.PerforceRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PerforceResultTest {
    private StreamGobbler stream(String text) {
        var stream = new StreamGobbler(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        stream.run();
        return stream;
    }

    private ExecResult nativeResult(String stdout, String stderr) {
        var result = new ExecResult();
        result.setExitCode(0);
        result.setCharset(StandardCharsets.UTF_8);
        result.setOutputGobbler(stream(stdout));
        result.setErrorGobbler(stream(stderr));
        return result;
    }

    @Test void nativeCachedErrorStreamDoesNotReintroduceNoDifferencesWarnings() {
        String warnings = "No file(s) to reconcile.\nNo file(s) to reconcile.\n";
        var output = nativeResult("", warnings);
        try {
            assertEquals(warnings, output.getStderr()); // Prime the same cache as the running Rider.
            output.setStderr(PerforcePreview.errorsOnly(output.getStderr()));
            assertEquals(warnings, output.getStderr(), "Reproduce 1.0.6: the cached stream overrides setStderr()");
            assertThrows(VcsException.class, () -> PerforceRunner.checkError(output, null, null));
            var normalized = PerforcePreview.normalizedResult(output);
            assertEquals("", normalized.getStderr());
            assertEquals("", normalized.getStderr(), "Repeated native validation must not restore the cached warnings");
            assertDoesNotThrow(() -> PerforceRunner.checkError(normalized, null, null));
            assertEquals(warnings, output.getStderr(), "The caller still owns the original native output");
        } finally { output.cleanup(); }
    }

    @Test void nativeMixedBatchPreservesDiscoveredChangesWhileIgnoringOnlyNoDifferencesWarnings() {
        String metadata = "... depotFile //depot/game.cpp\n... action edit\n... workRev 16\n";
        var output = nativeResult(metadata, "No file(s) to reconcile.\r\nNo file(s) to reconcile.\r\n");
        try {
            var normalized = PerforcePreview.normalizedResult(output);
            output.cleanup(); // Detached output must remain readable after original stream cleanup.
            assertEquals(metadata, normalized.getStdout());
            assertEquals(StandardCharsets.UTF_8, normalized.getCharset());
            assertEquals(0, normalized.getExitCode());
            assertDoesNotThrow(() -> PerforceRunner.checkError(normalized, null, null));
        } finally { output.cleanup(); }
    }

    @Test void nativeAuthenticationErrorsRemainVisibleAlongsideNoDifferencesWarnings() {
        String error = "Your session has expired, please login again.";
        var output = nativeResult("", "No file(s) to reconcile.\n" + error + "\n");
        try {
            assertEquals(error, PerforcePreview.normalizedResult(output).getStderr());
        } finally { output.cleanup(); }
    }

    @Test void nativeRealErrorsStillFailValidation() {
        var output = nativeResult("", "No file(s) to reconcile.\nUnexpected server error\n");
        try {
            var normalized = PerforcePreview.normalizedResult(output);
            assertEquals("Unexpected server error", normalized.getStderr());
            assertThrows(VcsException.class, () -> PerforceRunner.checkError(normalized, null, null));
        } finally { output.cleanup(); }
    }

    @Test void nativeProcessFailureIsPreservedEvenWhenStderrOnlyContainsNoDifferencesWarnings() {
        var output = nativeResult("", "No file(s) to reconcile.\n");
        try {
            var failure = new VcsException("Process failed");
            output.setExitCode(-1); output.setException(failure);
            var normalized = PerforcePreview.normalizedResult(output);
            assertSame(failure, normalized.getException());
            assertEquals(-1, normalized.getExitCode());
            assertThrows(VcsException.class, () -> PerforceRunner.checkError(normalized, null, null));
        } finally { output.cleanup(); }
    }
}
