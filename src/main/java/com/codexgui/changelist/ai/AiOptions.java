package com.codexgui.changelist.ai;

import com.codexgui.settings.CodexSettingsState;

/** Empty model/effort values deliberately leave the official CLI configuration in control. */
public record AiOptions(String executable, String model, String effort, boolean fast,
                        CodexSettingsState.ProviderProfileSnapshot provider) {
    public AiOptions(String executable, String model, String effort, boolean fast) {
        this(executable, model, effort, fast, new CodexSettingsState.ProviderProfileSnapshot(
                CodexSettingsState.CODEX_LOCAL_PROVIDER_ID, CodexSettingsState.ProviderChannel.CODEX,
                "本机 Codex", "", "", "responses", true, 1));
    }
    public AiOptions {
        executable = clean(executable); model = clean(model); effort = clean(effort);
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    public static AiOptions from(CodexSettingsState settings, CodexSettingsState.AnnotationScope scope) {
        synchronized (settings) {
            var shared = settings.snapshot(CodexSettingsState.CODEX_CHANNEL);
            var preferences = settings.annotationPreferences(scope);
            var model = settings.annotationModel(scope).model;
            var connection = shared.provider();
            // The provider's chat model must not affect annotation equality or running-task snapshots.
            var provider = new CodexSettingsState.ProviderProfileSnapshot(connection.id(), connection.channel(),
                    connection.name(), connection.baseUrl(), model, connection.wireApi(), connection.builtIn(), connection.revision());
            return new AiOptions(shared.codexExecutable(), model,
                    CodexSettingsState.ReasoningEffort.from(preferences.reasoningEffort).value(), preferences.fast, provider);
        }
    }
    public String cacheKey(AiDiffSnapshot snapshot) {
        return snapshot.fingerprint() + ":codedeck-v1:" + executable + ":" + model + ":" + effort + ":" + fast
                + ":" + provider.id() + ":" + provider.revision() + ":" + provider.baseUrl() + ":" + provider.wireApi();
    }
}
