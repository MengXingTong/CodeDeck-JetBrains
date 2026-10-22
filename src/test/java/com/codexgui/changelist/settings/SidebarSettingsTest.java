package com.codexgui.changelist.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SidebarSettingsTest {
    @Test void legacyAiFieldsRemainReadableForStandaloneWorkspaceMigration() {
        var settings = new SidebarSettings();
        assertFalse(settings.snapshot().aiFast); assertEquals("", settings.snapshot().aiModel);
        var data = settings.snapshot(); data.aiModel = "custom-model"; data.aiEffort = "high";
        data.aiFast = true; data.codexExecutable = "C:/Tools/codex.exe"; settings.loadState(data);
        data.aiModel = "changed outside";
        var loaded = settings.snapshot();
        assertEquals("custom-model", loaded.aiModel); assertEquals("high", loaded.aiEffort);
        assertTrue(loaded.aiFast); assertEquals("C:/Tools/codex.exe", loaded.codexExecutable);
    }
    @Test void freshInstallRequiresOptInForEveryAutomaticServerCheck() {
        var settings = new SidebarSettings();
        assertTrue(settings.snapshot().ignoreTemplates.isEmpty());
        assertFalse(settings.snapshot().autoRefresh);
        assertFalse(settings.snapshot().scanUnopened);
        assertEquals(60, settings.snapshot().refreshSeconds);
        assertEquals(1, settings.snapshot().networkActivityVersion);
    }

    @Test void upgradingDisablesLegacyPollingAndScanningWithoutMutatingTheStoredInput() {
        var legacy = new SidebarSettings.Data();
        legacy.autoRefresh = true;
        legacy.scanUnopened = true;
        legacy.refreshSeconds = 45;
        legacy.showUnversioned = false;
        legacy.projectScopeVersion = 2;
        legacy.ignoredPaths.add("Content/Generated/");
        var settings = new SidebarSettings();
        settings.loadState(legacy);
        var upgraded = settings.snapshot();
        assertFalse(upgraded.autoRefresh);
        assertFalse(upgraded.scanUnopened);
        assertEquals(1, upgraded.networkActivityVersion);
        assertEquals(45, upgraded.refreshSeconds);
        assertFalse(upgraded.showUnversioned);
        assertEquals(legacy.ignoredPaths, upgraded.ignoredPaths);
        assertTrue(legacy.autoRefresh);
        assertTrue(legacy.scanUnopened);
        assertEquals(0, legacy.networkActivityVersion);
    }

    @Test void explicitOptInsAfterUpgradeSurviveReloadIndependently() {
        for (boolean polling : new boolean[]{false, true}) {
            for (boolean scanning : new boolean[]{false, true}) {
                var saved = new SidebarSettings().snapshot();
                saved.autoRefresh = polling;
                saved.scanUnopened = scanning;
                var reopened = new SidebarSettings();
                reopened.loadState(saved);
                assertEquals(polling, reopened.snapshot().autoRefresh);
                assertEquals(scanning, reopened.snapshot().scanUnopened);
                assertEquals(1, reopened.snapshot().networkActivityVersion);
            }
        }
    }

    @Test void ignoreListsSurviveReloadAndSnapshotsDoNotMutatePersistedState() {
        var saved = new SidebarSettings.Data();
        saved.ignoreTemplates.add("UnrealEngine");
        saved.ignorePatterns = "*.obj\n!Source/keep.obj\n";
        saved.ignoredPaths.add("Source/Generated/");
        saved.restoredPaths.add("Source/Generated/keep.cpp");
        saved.scanUnopened = false;
        var settings = new SidebarSettings(); settings.loadState(saved);
        saved.ignoredPaths.clear();
        var snapshot = settings.snapshot();
        snapshot.ignoreTemplates.clear(); snapshot.restoredPaths.clear();
        var reopened = new SidebarSettings(); reopened.loadState(settings.getState());
        assertEquals(java.util.List.of("UnrealEngine"), reopened.snapshot().ignoreTemplates);
        assertEquals(java.util.List.of("Source/Generated/"), reopened.snapshot().ignoredPaths);
        assertEquals(java.util.List.of("Source/Generated/keep.cpp"), reopened.snapshot().restoredPaths);
        assertEquals("*.obj\n!Source/keep.obj\n", reopened.snapshot().ignorePatterns);
        assertFalse(reopened.snapshot().scanUnopened);
    }

    @Test void freshInstallIncludesProjectUnversionedFiles() {
        var settings = new SidebarSettings();
        assertTrue(settings.snapshot().showUnversioned);
        assertEquals(2, settings.snapshot().projectScopeVersion);
    }

    @Test void legacyUpgradeKeepsUnversionedVisibleWithoutResettingOtherPreferences() {
        var old = new SidebarSettings.Data();
        old.showUnversioned = true;
        old.autoRefresh = false;
        old.refreshSeconds = 45;
        old.placementVersion = 1;
        var settings = new SidebarSettings();
        settings.loadState(old);
        var migrated = settings.snapshot();
        assertTrue(migrated.showUnversioned);
        assertFalse(migrated.autoRefresh);
        assertEquals(45, migrated.refreshSeconds);
        assertEquals(1, migrated.placementVersion);
        assertEquals(2, migrated.projectScopeVersion);
        assertTrue(old.showUnversioned);
    }

    @Test void affectedUpgradeRecoversTheIncorrectlyHiddenSection() {
        var old = new SidebarSettings.Data();
        old.projectScopeVersion = 1;
        old.showUnversioned = false;
        old.autoRefresh = false;
        old.refreshSeconds = 45;
        old.placementVersion = 1;
        var settings = new SidebarSettings();
        settings.loadState(old);
        var recovered = settings.snapshot();
        assertTrue(recovered.showUnversioned);
        assertEquals(2, recovered.projectScopeVersion);
        assertFalse(recovered.autoRefresh);
        assertEquals(45, recovered.refreshSeconds);
        assertEquals(1, recovered.placementVersion);
        assertFalse(old.showUnversioned);
    }

    @Test void subsequentExplicitHidingSurvivesRestarts() {
        var saved = new SidebarSettings().snapshot();
        saved.showUnversioned = false;
        var reopened = new SidebarSettings();
        reopened.loadState(saved);
        assertFalse(reopened.snapshot().showUnversioned);
        assertEquals(2, reopened.snapshot().projectScopeVersion);
    }

    @Test void legacyExplicitHidingIsPreserved() {
        var old = new SidebarSettings.Data();
        old.showUnversioned = false;
        var settings = new SidebarSettings();
        settings.loadState(old);
        assertFalse(settings.snapshot().showUnversioned);
        assertEquals(2, settings.snapshot().projectScopeVersion);
    }
}
