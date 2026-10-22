package com.codexgui.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static com.codexgui.settings.CodexSettingsState.AnnotationScope.*;

class CodexSettingsStateTest {
    @Test void lineEndingGuardDefaultsOnAndPersistsAnExplicitConversionOptOut() {
        var settings = new CodexSettingsState();
        assertTrue(settings.getState().preserveFileLineEndings);
        settings.getState().preserveFileLineEndings = false;
        var xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState());
        var reopened = new CodexSettingsState();
        reopened.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(xml, CodexSettingsState.StateData.class));
        assertFalse(reopened.getState().preserveFileLineEndings);
    }
    @Test void migratesSharedOptionsOnceThenPersistsThreeIndependentContexts() {
        var legacy = new CodexSettingsState.StateData();
        legacy.schemaVersion = 2;
        legacy.model = "old-model";
        legacy.reasoningEffort = "xhigh";
        legacy.serviceTier = "fast";
        var settings = new CodexSettingsState();
        settings.loadState(legacy);
        assertEquals("old-model", settings.annotationModel(DIFF).model);
        assertEquals("old-model", settings.annotationModel(BATCH).model);
        assertEquals("xhigh", settings.annotationPreferences(DIFF).reasoningEffort);
        assertTrue(settings.annotationPreferences(BATCH).fast);

        settings.setAnnotationOptions(DIFF, "diff-model", "low", false);
        settings.annotationModel(DIFF).customModels.add("diff-only");
        settings.setAnnotationOptions(BATCH, "batch-model", "high", true);
        settings.setGptInferenceOptions("chat-model", "ultra", false);
        var xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState());
        var reopened = new CodexSettingsState();
        reopened.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(xml, CodexSettingsState.StateData.class));
        assertEquals("diff-model", reopened.annotationModel(DIFF).model);
        assertEquals("low", reopened.annotationPreferences(DIFF).reasoningEffort);
        assertFalse(reopened.annotationPreferences(DIFF).fast);
        assertEquals(List.of("diff-only"), reopened.annotationModel(DIFF).customModels);
        assertEquals("batch-model", reopened.annotationModel(BATCH).model);
        assertEquals("high", reopened.annotationPreferences(BATCH).reasoningEffort);
        assertTrue(reopened.annotationPreferences(BATCH).fast);
        assertFalse(reopened.annotationModel(BATCH).customModels.contains("diff-only"));
        assertEquals("chat-model", reopened.getState().model);
        assertEquals("ultra", reopened.getState().reasoningEffort);
    }

    @Test void annotationModelsAreRememberedPerProviderWithoutTouchingChatSelections() {
        var settings = new CodexSettingsState();
        settings.setAnnotationOptions(DIFF, "local-diff", "low", false);
        var custom = new CodexSettingsState.ProviderProfile("custom", "codex", "Custom", false);
        custom.model = "chat-custom";
        settings.getState().providers.add(custom);
        settings.setActiveProvider("codex", custom.id);
        assertEquals("chat-custom", settings.annotationModel(DIFF).model);
        settings.setAnnotationOptions(DIFF, "custom-diff", "medium", true);
        settings.setAnnotationOptions(BATCH, "custom-batch", "high", false);
        settings.setActiveProvider("codex", CodexSettingsState.CODEX_LOCAL_PROVIDER_ID);
        assertEquals("local-diff", settings.annotationModel(DIFF).model);
        assertEquals("chat-custom", custom.model);
        settings.setActiveProvider("codex", custom.id);
        assertEquals("custom-diff", settings.annotationModel(DIFF).model);
        assertEquals("custom-batch", settings.annotationModel(BATCH).model);
    }
    @Test
    void restoresBuiltInProvidersWhenMigratingOldSettings() {
        var component = new CodexSettingsState();
        var state = new CodexSettingsState.StateData();
        state.providers = new ArrayList<>();
        state.activeCodexProviderId = "missing";
        state.activeClaudeProviderId = "missing";

        component.loadState(state);

        assertNotNull(component.provider(CodexSettingsState.CODEX_LOCAL_PROVIDER_ID));
        assertNotNull(component.provider(CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID));
        assertEquals(CodexSettingsState.CODEX_LOCAL_PROVIDER_ID, component.activeProviderId(CodexSettingsState.CODEX_CHANNEL));
        assertEquals(CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID, component.activeProviderId(CodexSettingsState.CLAUDE_CHANNEL));
        assertTrue(component.activeProvider(CodexSettingsState.CODEX_CHANNEL).builtIn);
        assertEquals(CodexSettingsState.CURRENT_SCHEMA_VERSION, state.schemaVersion);
    }

    @Test
    void migratesRestrictedValuesOnlyWhenLoadingAnOldSchema() {
        var component = new CodexSettingsState();
        var state = new CodexSettingsState.StateData();
        state.schemaVersion = 0;
        state.reasoningEffort = "unsupported";
        state.serviceTier = "turbo";
        state.approvalPolicy = "always";
        state.sandboxMode = "unknown";
        state.sendShortcut = "space";

        component.loadState(state);

        assertEquals("high", state.reasoningEffort);
        assertEquals("standard", state.serviceTier);
        assertEquals("on-request", state.approvalPolicy);
        assertEquals("workspace-write", state.sandboxMode);
        assertEquals("enter", state.sendShortcut);
        assertEquals(CodexSettingsState.CURRENT_SCHEMA_VERSION, state.schemaVersion);
    }

    @Test
    void conversationSnapshotDoesNotChangeWithThePersistentBean() {
        var component = new CodexSettingsState();
        var state = component.getState();
        var provider = component.activeProvider(CodexSettingsState.CODEX_CHANNEL);
        state.model = "model-a";
        provider.baseUrl = "https://provider-a.example";

        var snapshot = component.snapshot(CodexSettingsState.CODEX_CHANNEL);
        state.model = "model-b";
        provider.baseUrl = "https://provider-b.example";

        assertEquals("model-a", snapshot.model());
        assertEquals("https://provider-a.example", snapshot.provider().baseUrl());
        assertNotEquals(state.model, snapshot.model());
    }

    @Test
    void normalizesAndPersistsProviderCustomModels() {
        var component = new CodexSettingsState();
        var state = component.getState();
        var provider = component.activeProvider(CodexSettingsState.CLAUDE_CHANNEL);
        provider.customModels = new ArrayList<>(List.of(" claude-custom ", "claude-custom", ""));

        component.activeProvider(CodexSettingsState.CLAUDE_CHANNEL);

        assertEquals(List.of("claude-custom"), provider.customModels);
    }
}
