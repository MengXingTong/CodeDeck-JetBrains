package com.codexgui.changelist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ProjectScopeTest {
    @TempDir Path base;

    @Test void includesOnlyDescendantsWithoutRequiringFilesToExist() {
        var scope = ProjectScope.of(base);
        assertTrue(scope.contains(base.resolve("Source/new.cs")));
        assertTrue(scope.contains(base.resolve("Source/deleted.cs").toString()));
        assertFalse(scope.contains(base));
        assertFalse(scope.contains(base.getParent()));
        assertFalse(scope.contains(base.getRoot().resolve("other/file.cs")));
    }

    @Test void siblingWithSamePrefixAndParentTraversalStayOutsideProject() {
        var scope = ProjectScope.of(base);
        assertFalse(scope.contains(base.resolveSibling(base.getFileName() + "-other").resolve("a.cs")));
        assertFalse(scope.contains(base.resolve("../outside/a.cs")));
        assertTrue(scope.contains(base.resolve("Source/../Content/asset.uasset")));
        assertFalse(scope.contains(Path.of("Source/a.cs")));
    }

    @Test void missingInvalidRelativeOrDriveRootBasesFailClosed() {
        assertNull(ProjectScope.forBasePath(null).root());
        assertNull(ProjectScope.forBasePath("").root());
        assertNull(ProjectScope.forBasePath("\u0000").root());
        assertNull(ProjectScope.of(Path.of("project")).root());
        assertNull(ProjectScope.of(base.getRoot()).root());
        assertFalse(ProjectScope.of(null).contains(base.resolve("a.cs")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsCaseAndDriveBoundariesMatchNativePaths() {
        var scope = ProjectScope.of(Path.of("D:/Projects/Game"));
        assertTrue(scope.contains("d:\\projects\\game\\Source\\a.cs"));
        assertFalse(scope.contains("D:/Projects/GameBackup/Source/a.cs"));
        assertFalse(scope.contains("C:/Projects/Game/Source/a.cs"));
        assertFalse(scope.contains("D:Source/a.cs"));
        assertTrue(ProjectScope.of(base).contains(Path.of(base.toString().toUpperCase(Locale.ROOT)).resolve("a.cs")));
        assertNull(ProjectScope.forBasePath("D:/").root());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void uncShareAndOtherProjectsStayOutsideProject() {
        var scope = ProjectScope.forBasePath("\\\\server\\share\\Game");
        assertTrue(scope.contains("\\\\server\\share\\Game\\a.cs"));
        assertFalse(scope.contains("\\\\server\\share\\Other\\a.cs"));
        assertFalse(scope.contains("\\\\server\\other\\Game\\a.cs"));
        assertNull(ProjectScope.forBasePath("\\\\server\\share\\").root());
    }
}
