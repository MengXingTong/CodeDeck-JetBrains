package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodexProviderConfigurationTest {
    @Test void queuedRequestsKeepTheirModelWhenOnlyTheSharedModelSelectionChanges() {
        var old = profile("team", "https://example.invalid/v1", "before", 1);
        assertTrue(CodexProviderConfiguration.sameConnection(old, profile("team", old.baseUrl(), "after", 1)));
        assertFalse(CodexProviderConfiguration.sameConnection(old, profile("another", old.baseUrl(), "before", 1)));
        assertFalse(CodexProviderConfiguration.sameConnection(old, profile("team", "https://changed.invalid/v1", "before", 1)));
        assertFalse(CodexProviderConfiguration.sameConnection(old, profile("team", old.baseUrl(), "before", 2)));
    }
    private static CodexSettingsState.ProviderProfileSnapshot profile(String id, String url, String model, int revision) {
        return new CodexSettingsState.ProviderProfileSnapshot(id, CodexSettingsState.ProviderChannel.CODEX,
                "Team", url, model, "responses", false, revision);
    }
}
