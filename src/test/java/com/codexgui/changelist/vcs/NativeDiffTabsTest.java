package com.codexgui.changelist.vcs;

import com.intellij.diff.requests.ErrorDiffRequest;
import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeDiffTabsTest {
    @TempDir Path base;

    @Test void repeatedOpeningActivatesTheSameNativeDiffFileWithoutReadingContents() {
        Path path = base.resolve("Source/a.cs");
        var first = NativeDiffTabs.createFile(path, new ErrorDiffRequest("Fixture", "No content needed"));
        var other = NativeDiffTabs.createFile(base.resolve("Other/a.cs"), new ErrorDiffRequest("Other", "Other"));
        List<VirtualFile> activated = new ArrayList<>();
        for (int click = 0; click < 3; click++) {
            assertTrue(NativeDiffTabs.focusExisting(base.resolve("Source/../Source/a.cs"),
                    List.of(other, first), activated::add));
        }
        assertEquals(List.of(first, first, first), activated);
        assertSame(first.getChain(), ((com.intellij.diff.editor.ChainDiffVirtualFile) activated.getFirst()).getChain());
    }

    @Test void closedTabsAndFilesWithTheSameNameDoNotCountAsAnOpenDiff() {
        Path path = base.resolve("Source/a.cs");
        var other = NativeDiffTabs.createFile(base.resolve("Other/a.cs"), new ErrorDiffRequest("Other", "Other"));
        List<VirtualFile> activated = new ArrayList<>();
        assertFalse(NativeDiffTabs.focusExisting(path, List.of(other), activated::add));
        assertFalse(NativeDiffTabs.focusExisting(path, List.of(), activated::add));
        assertTrue(activated.isEmpty());
        var reopened = NativeDiffTabs.createFile(path, new ErrorDiffRequest("Reloaded", "Fresh request"));
        assertTrue(NativeDiffTabs.focusExisting(path, List.of(reopened), activated::add));
        assertSame(reopened, activated.getFirst());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsPathCaseDoesNotCreateDuplicateDiffs() {
        var file = NativeDiffTabs.createFile(Path.of("D:/Project/Source/a.cs"), new ErrorDiffRequest("Fixture", "Fixture"));
        List<VirtualFile> activated = new ArrayList<>();
        assertTrue(NativeDiffTabs.focusExisting(Path.of("d:\\project\\source\\A.cs"), List.of(file), activated::add));
        assertEquals(List.of(file), activated);
    }
}
