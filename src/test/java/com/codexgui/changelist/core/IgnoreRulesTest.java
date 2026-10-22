package com.codexgui.changelist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IgnoreRulesTest {
    @TempDir Path root;

    private IgnoreRules rules(List<String> templates, String patterns, List<String> ignored, List<String> restored) {
        return new IgnoreRules(ProjectScope.of(root), templates, patterns, ignored, restored);
    }

    @Test void emptyIgnoreListPreservesSourcesAndBuildFiles() {
        var rules = IgnoreRules.none(ProjectScope.of(root));
        assertFalse(rules.isIgnored(root.resolve("Intermediate/obj.cpp")));
        assertFalse(rules.isIgnored(root.resolve("Source/game.cpp")));
    }

    @Test void unrealTemplateHidesGeneratedFilesAndPreservesSourceAndAssets() {
        var rules = rules(List.of("UnrealEngine"), "", List.of(), List.of());
        for (String path : List.of("Intermediate/Build/test.gen.cpp", "Binaries/Win64/game.dll", "Saved/Logs/game.log",
                "DerivedDataCache/cache", "Plugins/Gameplay/Binaries/Win64/game.pdb",
                "Plugins/Gameplay/Intermediate/obj.gen.cpp", "Source/foo.obj", "Content/map_BuiltData.uasset")) {
            assertTrue(rules.isIgnored(root.resolve(path)), path);
        }
        for (String path : List.of("Source/game.cpp", "Source/game.h", "Content/character.uasset", "Config/DefaultEngine.ini")) {
            assertFalse(rules.isIgnored(root.resolve(path)), path);
        }
    }

    @Test void unrealBuildWhitelistUsesGitParentDirectorySemantics() {
        var rules = rules(List.of("UnrealEngine"), "", List.of(), List.of());
        assertTrue(rules.isIgnored(root.resolve("Build/Windows/generated.dat")));
        assertFalse(rules.isIgnored(root.resolve("Build/Windows/PakBlacklist-Shipping.txt")));
        assertFalse(rules.isIgnored(root.resolve("Build/Windows/icon.ico")));
        assertFalse(rules.isIgnored(root.resolve("Build/Windows"), true));
    }

    @Test void rootAnchorsDoubleStarsAndDirectoryRulesDoNotOvermatch() {
        var rules = rules(List.of(), "/Generated/\n**/Temp/\n*.obj\n", List.of(), List.of());
        assertTrue(rules.isIgnored(root.resolve("Generated/file.cpp")));
        assertFalse(rules.isIgnored(root.resolve("Source/Generated/file.cpp")));
        assertTrue(rules.isIgnored(root.resolve("Plugins/Foo/Temp/cache")));
        assertFalse(rules.isIgnored(root.resolve("Plugins/Foo/Temporary/cache")));
        assertTrue(rules.isIgnored(root.resolve("Source/file.obj")));
        assertFalse(rules.isIgnored(root.resolve("Source/file.object")));
        assertFalse(rules.isIgnored(root.resolve("Generated")), "A directory-only rule must not hide a same-name file");
    }

    @Test void negationCannotRestoreChildOfAnIgnoredParentButExplicitRestorationCan() {
        var rules = rules(List.of(), "cache/\n!cache/keep.cpp\n", List.of(), List.of());
        assertTrue(rules.isIgnored(root.resolve("cache/keep.cpp")));
        var restored = rules(List.of(), "cache/\n!cache/keep.cpp\n", List.of(), List.of("cache/keep.cpp"));
        assertFalse(restored.isIgnored(root.resolve("cache/keep.cpp")));
        assertTrue(restored.isIgnored(root.resolve("cache/other.cpp")));
    }

    @Test void laterCustomRulesOverrideTemplateForIndividualFiles() {
        var rules = rules(List.of("C++"), "!Source/keep.obj\n", List.of(), List.of());
        assertFalse(rules.isIgnored(root.resolve("Source/keep.obj")));
        assertTrue(rules.isIgnored(root.resolve("Source/discard.obj")));
    }

    @Test void literalPathsDoNotInterpretWildcardsOrChangelistNames() {
        var rules = rules(List.of(), "", List.of("Source/[Generated] #1/"), List.of());
        assertTrue(rules.isIgnored(root.resolve("Source/[Generated] #1/game.cpp")));
        assertFalse(rules.isIgnored(root.resolve("Source/G #1/game.cpp")));
        assertFalse(rules.isIgnored(root.resolve("Source/[Generated] #10/game.cpp")));
        assertFalse(rules.isIgnored(root.resolve("Source/[Generated] #1")));
    }

    @Test void restoringDirectoryAllowsSpecificChildrenToBeIgnoredAgain() {
        var rules = rules(List.of("UnrealEngine"), "", List.of("Saved/", "Saved/Logs/"), List.of("Saved/"));
        assertTrue(rules.isIgnored(root.resolve("Saved/config.ini")), "Explicit ignore wins an equal-path tie");
        rules = rules(List.of("UnrealEngine"), "", List.of("Saved/Logs/"), List.of("Saved/"));
        assertFalse(rules.isIgnored(root.resolve("Saved/config.ini")));
        assertTrue(rules.isIgnored(root.resolve("Saved/Logs/game.log")));
        assertFalse(rules.isIgnored(root.resolve("Saved/Logstash/game.log")));
    }

    @Test void specificFileCanBeRestoredFromPermanentParentIgnore() {
        var rules = rules(List.of(), "", List.of("Source/Generated/"), List.of("Source/Generated/keep.cpp"));
        assertFalse(rules.isIgnored(root.resolve("Source/Generated/keep.cpp")));
        assertTrue(rules.isIgnored(root.resolve("Source/Generated/other.cpp")));
        assertTrue(rules.isIgnored(root.resolve("source/generated/other.cpp")), "Literal Windows paths ignore case");
    }

    @Test void escapedCommentAndNegationCharactersAreLiteralFileNames() {
        var rules = rules(List.of(), "# comment\n\\#generated\n\\!generated\n", List.of(), List.of());
        assertTrue(rules.isIgnored(root.resolve("#generated")));
        assertTrue(rules.isIgnored(root.resolve("!generated")));
        assertFalse(rules.isIgnored(root.resolve("comment")));
    }

    @Test void projectScopeAndLiteralNormalizationRejectEscapePaths() {
        var rules = rules(List.of("UnrealEngine"), "*", List.of(), List.of());
        assertFalse(rules.isIgnored(root.resolve("../Other/Intermediate/test.obj")));
        assertEquals(List.of("Source/Generated/", "Source/keep.cpp"), IgnoreRules.normalizePaths(List.of(
                "Source\\Generated\\", "source/generated/", "Source/./Generated/", "Source/keep.cpp", "../Other/", ".", "D:/Other/", "/absolute/")));
        assertThrows(IllegalArgumentException.class, () -> IgnoreRules.literalPath(ProjectScope.of(root), root, true));
        assertThrows(IllegalArgumentException.class, () -> IgnoreRules.literalPath(ProjectScope.of(root), root.resolve("../Other"), true));
    }

    @Test void everyBundledTemplateContainsUsableOfflineRules() {
        for (String name : IgnoreTemplates.NAMES) assertFalse(IgnoreTemplates.content(name).isBlank(), name);
        assertEquals("", IgnoreTemplates.content("../../unexpected"));
    }
}
