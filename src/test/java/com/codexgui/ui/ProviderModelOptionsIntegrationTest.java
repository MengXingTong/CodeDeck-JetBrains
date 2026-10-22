package com.codexgui.ui;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.settings.CodexProjectSettingsState;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ThrowableRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real IDE event queue without starting a CLI or making a model request. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_MODEL_OPTIONS", matches = "1")
class ProviderModelOptionsIntegrationTest {
    @TempDir Path root;

    @Test void cacheIsAvailableBeforeConnectionAcrossTabsAndNeverLeaksIntoOtherProviders() throws Exception {
        withCoordinator(CompletableFuture.completedFuture(List.of("cached-a", "cached-b")), fixture -> {
            onEdt(() -> fixture.coordinator.loadModels());
            onEdt(() -> {
                assertEquals(List.of("cached-a", "cached-b"), fixture.models());
                assertEquals("", fixture.settings.getState().model, "Suggestions must preserve the default selection");
                assertEquals(1, fixture.updates.get());
                fixture.registry.activate(SessionId.of("new-tab"));
                assertEquals(List.of("cached-a", "cached-b"), fixture.models());
                fixture.registry.active().provider("claude");
                fixture.coordinator.loadModels();
                assertTrue(fixture.models().isEmpty(), "Codex cache must not populate Claude");
                var custom = new CodexSettingsState.ProviderProfile("private", "codex", "Private", false);
                fixture.settings.getState().providers.add(custom);
                fixture.settings.setActiveProvider("codex", custom.id);
                fixture.registry.active().provider("codex");
                assertTrue(fixture.models().isEmpty(), "Local cache must not populate a custom API provider");
                fixture.settings.setActiveProvider("codex", CodexSettingsState.CODEX_LOCAL_PROVIDER_ID);
                assertEquals(List.of("cached-a", "cached-b"), fixture.models());
            });
        });
    }

    @Test void lateCacheCannotOverwriteTheLiveCatalogOrSavedSelection() throws Exception {
        var cache = new CompletableFuture<List<String>>();
        withCoordinator(cache, fixture -> {
            onEdt(() -> {
                fixture.settings.getState().model = "live-model";
                fixture.coordinator.loadModels();
                var apply = ProviderSettingsCoordinator.class.getDeclaredMethod("applyModelOptions",
                    String.class, int.class, List.class, String.class);
                apply.setAccessible(true);
                apply.invoke(fixture.coordinator, CodexSettingsState.CODEX_LOCAL_PROVIDER_ID, 1,
                    List.of("live-model"), "live-model");
                cache.complete(List.of("stale-cache"));
            });
            onEdt(() -> {
                assertEquals(List.of("live-model"), fixture.models());
                assertEquals("live-model", fixture.settings.getState().model);
                assertEquals(1, fixture.updates.get());
            });
        });
    }

    @Test void unavailableLegacyProjectSettingsDoNotAbortChatSettingsAndMigrationStillWorks() throws Exception {
        withCoordinator(CompletableFuture.completedFuture(List.of()), fixture -> onEdt(() -> {
            assertEquals("", fixture.coordinator.sharedInstructions(fixture.settings.getState()));
            fixture.settings.getState().projectInstructions = "shared instructions";
            assertEquals("shared instructions", fixture.coordinator.sharedInstructions(fixture.settings.getState()));
            fixture.settings.getState().projectInstructions = "";
            fixture.legacy[0] = new CodexProjectSettingsState();
            fixture.legacy[0].getState().projectInstructions = "  old instructions  ";
            assertEquals("old instructions", fixture.coordinator.sharedInstructions(fixture.settings.getState()));
            assertEquals("old instructions", fixture.settings.getState().projectInstructions);
            assertEquals("", fixture.legacy[0].getState().projectInstructions);
        }));
    }

    private void withCoordinator(CompletableFuture<List<String>> cache, Check check) throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        var parent = Disposer.newDisposable();
        var settings = new CodexSettingsState();
        Class.forName("com.intellij.testFramework.ServiceContainerUtil")
            .getMethod("registerOrReplaceServiceInstance", ComponentManager.class, Class.class, Object.class,
                com.intellij.openapi.Disposable.class)
            .invoke(null, ApplicationManager.getApplication(), CodexSettingsState.class, settings, parent);
        var registry = new SessionRegistry();
        var codex = new CodexAppServerService(null);
        var updates = new AtomicInteger();
        var legacy = new CodexProjectSettingsState[1];
        var project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
            (proxy, method, args) -> method.getName().equals("getService") ? legacy[0] : null);
        var coordinator = new ProviderSettingsCoordinator(project, null, root, codex, null, null, registry,
            new ProviderSettingsCoordinator.Listener() {
                @Override public void settingsChanged() {}
                @Override public void modelOptionsChanged() { updates.incrementAndGet(); }
                @Override public void providerStatusChanged(JsonArray providers) {}
                @Override public void providerSaveResult(boolean success, String message) {}
                @Override public void startNewConversation(String title, String provider) {}
                @Override public void toast(String message) {}
                @Override public void asyncError(String title, Throwable error) { fail(title, error); }
            }, cache);
        try {
            check.run(new Fixture(coordinator, settings, registry, updates, legacy));
        } finally {
            onEdt(() -> { coordinator.dispose(); codex.dispose(); Disposer.dispose(parent); });
        }
    }

    private record Fixture(ProviderSettingsCoordinator coordinator, CodexSettingsState settings,
                           SessionRegistry registry, AtomicInteger updates, CodexProjectSettingsState[] legacy) {
        List<String> models() {
            return coordinator.providerModels(settings.getState()).asList().stream().map(value -> value.getAsString()).toList();
        }
    }
    private interface Check { void run(Fixture fixture) throws Exception; }
    private static void onEdt(ThrowableRunnable<Exception> runnable) throws Exception {
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, runnable);
    }
}
