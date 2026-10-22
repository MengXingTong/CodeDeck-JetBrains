package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.ProviderCredentialStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared process-only provider configuration for chat and read-only Diff annotation requests. */
public final class CodexProviderConfiguration {
    private CodexProviderConfiguration() {}
    public static List<String> arguments(CodexSettingsState.ProviderProfileSnapshot provider) {
        var arguments = new ArrayList<String>();
        if (!provider.builtIn()) {
            arguments.addAll(List.of("-c", "model_provider=\"codex_gui\"",
                    "-c", "model_providers.codex_gui.name=" + tomlString(provider.name()),
                    "-c", "model_providers.codex_gui.base_url=" + tomlString(provider.baseUrl()),
                    "-c", "model_providers.codex_gui.env_key=\"CODEX_GUI_PROVIDER_KEY\"",
                    "-c", "model_providers.codex_gui.wire_api=" + tomlString(provider.wireApi())));
        }
        return List.copyOf(arguments);
    }
    public static void applyEnvironment(ProcessBuilder builder, CodexSettingsState.ProviderProfileSnapshot provider) {
        var codexHome = System.getenv("CODEX_HOME");
        if (codexHome == null || codexHome.isBlank()) codexHome = Path.of(System.getProperty("user.home", "."), ".codex").toString();
        builder.environment().put("CODEX_HOME", codexHome);
        if (!provider.builtIn()) {
            // A queued batch must not send a replacement credential to a previous provider URL.
            var current = CodexSettingsState.getInstance().activeProvider(CodexSettingsState.CODEX_CHANNEL).snapshot();
            if (!sameConnection(provider, current)) throw new IllegalStateException("CodeDeck GPT 供应商已变化，请重新开始任务");
            var apiKey = ProviderCredentialStore.get(provider.id());
            if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("当前 GPT 供应商尚未配置 API 密钥");
            builder.environment().put("CODEX_GUI_PROVIDER_KEY", apiKey);
        }
    }
    static boolean sameConnection(CodexSettingsState.ProviderProfileSnapshot captured,
                                  CodexSettingsState.ProviderProfileSnapshot current) {
        // Model selection is a preference, not a credential/endpoint change. Queued requests keep their own model.
        return captured.id().equals(current.id()) && captured.channel() == current.channel()
                && captured.name().equals(current.name()) && captured.baseUrl().equals(current.baseUrl())
                && captured.wireApi().equals(current.wireApi()) && captured.builtIn() == current.builtIn()
                && captured.revision() == current.revision();
    }
    private static String tomlString(String value) {
        return "\"" + Objects.requireNonNullElse(value, "").replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
