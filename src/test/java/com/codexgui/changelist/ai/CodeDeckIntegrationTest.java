package com.codexgui.changelist.ai;

import com.codexgui.service.CodexProviderConfiguration;
import com.codexgui.service.DiffSourceContext;
import com.codexgui.settings.CodexSettingsState;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeDeckIntegrationTest {
    @TempDir Path root;
    @Test void annotationsInheritTheSharedGptSettingsAndProviderModel() {
        var settings = new CodexSettingsState(); var data = settings.getState();
        data.codexExecutable = "D:/Tools/codex.exe"; data.model = "local-model";
        data.reasoningEffort = "ultra"; data.serviceTier = "fast";
        var profile = new CodexSettingsState.ProviderProfile("team", "codex", "团队 GPT", false);
        profile.baseUrl = "https://example.invalid/v1"; profile.model = "team-model"; profile.wireApi = "responses";
        data.providers.add(profile); data.activeCodexProviderId = profile.id; settings.loadState(data);
        var options = AiOptions.from(settings.snapshot(CodexSettingsState.CODEX_CHANNEL));
        assertEquals("D:/Tools/codex.exe", options.executable()); assertEquals("team-model", options.model());
        assertEquals("ultra", options.effort()); assertTrue(options.fast()); assertEquals("team", options.provider().id());
        var args = CodexCli.command(List.of("codex.exe"), options, Path.of("schema.json"), Path.of("out.json"));
        assertTrue(args.containsAll(CodexProviderConfiguration.arguments(options.provider())));
        assertTrue(args.contains("read-only")); assertTrue(args.contains("--ephemeral"));
        assertTrue(args.contains("approval_policy=\"never\""));
        assertEquals("team-model", args.get(args.indexOf("--model") + 1));
    }
    @Test void providerChangesInvalidateAnnotationCacheWithoutASecondSettingsStore() {
        var settings = new CodexSettingsState(); var snapshot = new AiDiffSnapshot("file.cpp", "old", "new");
        var first = AiOptions.from(settings.snapshot("codex"));
        var data = settings.getState(); data.model = "changed"; settings.loadState(data);
        var second = AiOptions.from(settings.snapshot("codex"));
        assertNotEquals(first.cacheKey(snapshot), second.cacheKey(snapshot));
        var oldProvider = second.provider();
        var updatedProvider = new CodexSettingsState.ProviderProfileSnapshot(oldProvider.id(), oldProvider.channel(),
                oldProvider.name(), oldProvider.baseUrl(), oldProvider.model(), oldProvider.wireApi(), oldProvider.builtIn(), oldProvider.revision() + 1);
        assertNotEquals(second.cacheKey(snapshot), new AiOptions(second.executable(), second.model(), second.effort(), second.fast(), updatedProvider).cacheKey(snapshot));
    }
    @Test void vcsAndAiDiffsShareSourceMetadataAndInstallOnlyOneAnnotationHeader() {
        var request = new SimpleDiffRequest("Diff", new EmptyContent(), new EmptyContent(), "before", "after");
        var file = root.resolve("Source/file.cpp");
        DiffSourceContext.install(file, request); DiffSourceContext.install(file, request);
        assertEquals(file.toAbsolutePath().normalize(), request.getUserData(DiffSourceContext.SOURCE_PATH));
        assertEquals(1, request.getUserData(DiffUserDataKeys.NOTIFICATION_PROVIDERS).size());
    }
    @Test void oneCodeDeckPluginContainsBothToolWindowsAndOptionalPerforceBridge() throws Exception {
        String descriptor;
        try (var stream = getClass().getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(stream); descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(descriptor.contains("<id>com.codexgui.jetbrains</id>"));
        assertTrue(descriptor.contains("id=\"CodeDeck Changes\"")); assertTrue(descriptor.contains("id=\"CodeDeck\""));
        assertTrue(descriptor.contains("config-file=\"changelist-perforce.xml\""));
        assertTrue(descriptor.contains("<incompatible-with>dev.p4changelist.toolwindow</incompatible-with>"));
        assertNotNull(getClass().getResource("/META-INF/changelist-perforce.xml"));
        assertNotNull(getClass().getResource("/icons/changelist.svg"));
        assertFalse(com.codexgui.changelist.core.IgnoreTemplates.content("UnrealEngine").isBlank());
    }
}
