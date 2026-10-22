package com.codexgui.ui;

import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.provider.TurnEvent;
import com.codexgui.provider.CodexConversationProvider;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.ClaudeHookRelayService;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Replays the first-send / delayed connection notification race on the real IDE event queue. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_CONNECTION_STATE", matches = "1")
class ConversationConnectionIntegrationTest {
    @TempDir Path root;

    @Test void delayedConnectedNotificationCannotDiscardFirstReplyOrLoseItsThread() throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        var parent = Disposer.newDisposable();
        var settings = new CodexSettingsState();
        settings.getState().claudeExecutable = root.resolve("no-claude-test.exe").toString();
        Class.forName("com.intellij.testFramework.ServiceContainerUtil")
            .getMethod("registerOrReplaceServiceInstance", ComponentManager.class, Class.class, Object.class,
                com.intellij.openapi.Disposable.class)
            .invoke(null, ApplicationManager.getApplication(), CodexSettingsState.class, settings, parent);
        var registry = new SessionRegistry();
        var sessions = new SessionCoordinator(registry);
        var codex = new CodexAppServerService(null);
        var provider = new CodexConversationProvider(codex);
        var constructor = ConversationChangeTracker.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        var tracker = constructor.newInstance(root);
        var relayConstructor = ClaudeHookRelayService.class.getDeclaredConstructor(ConversationChangeTracker.class);
        relayConstructor.setAccessible(true);
        var relay = relayConstructor.newInstance(tracker);
        // The availability check only needs a working directory; no Claude process or hook is started.
        var project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getBasePath" -> root.toString();
                case "getService" -> arguments[0] == ClaudeHookRelayService.class ? relay : null;
                default -> null;
            });
        var claude = new ClaudeCodeService(project);
        var checks = new CountDownLatch(3);
        var providerSettings = new ProviderSettingsCoordinator(null, null, root, codex, claude, null, registry,
            new ProviderSettingsCoordinator.Listener() {
                @Override public void settingsChanged() {}
                @Override public void modelOptionsChanged() {}
                @Override public void providerStatusChanged(JsonArray providers) { checks.countDown(); }
                @Override public void providerSaveResult(boolean success, String message) {}
                @Override public void startNewConversation(String title, String provider) {}
                @Override public void toast(String message) {}
                @Override public void asyncError(String title, Throwable error) { fail(title, error); }
            });
        var approvals = new ApprovalCoordinator(null, codex, sessions::isCurrent);
        var presenter = new BridgeEventPresenter(null, null, registry, root);
        var coordinator = new ConversationCoordinator(null, null, codex, null, null, provider, tracker, approvals,
            registry, sessions, null, providerSettings, null, null, presenter, new ConversationCoordinator.Listener() {
                @Override public void settingsChanged() {}
                @Override public EditorFileContext currentEditorContext() { return null; }
                @Override public boolean handleNativeCommand(String text) { return false; }
                @Override public boolean handleFeatureNotification(String method, JsonObject params) { return false; }
                @Override public void connectionRestored() {}
            });
        try {
            var session = registry.active();
            var handle = sessions.beginTurn(session.id());
            var background = registry.create(SessionId.of("background"));
            var backgroundHandle = sessions.beginTurn(background.id());
            var otherProvider = registry.create(SessionId.of("claude"));
            otherProvider.provider("claude");
            var claudeHandle = sessions.beginTurn(otherProvider.id());
            // startFuture has completed and dispatched the first input, but the connected event arrives later.
            onEdt(() -> coordinator.onConnectionChanged(true, "Codex CLI 已连接"));
            onEdt(() -> {
                assertTrue(sessions.isCurrent(handle), "Connected notification cancelled the just-started first turn");
                assertTrue(sessions.isCurrent(backgroundHandle));
                assertTrue(sessions.isCurrent(claudeHandle));
                var handler = ConversationCoordinator.class.getDeclaredMethod("handleCodexTurnEvent", TurnEvent.class);
                handler.setAccessible(true);
                handler.invoke(coordinator, new TurnEvent.Started(handle, "original-thread", "first-turn"));
                handler.invoke(coordinator, new TurnEvent.Item(handle, TurnEvent.Item.Phase.COMPLETED, "first-reply",
                    "agentMessage", Map.of("id", "first-reply", "type", "agentMessage", "text", "第一句话的完整回复")));
                assertEquals("original-thread", session.threadId(), "Follow-up input must use the original conversation");
                assertEquals("first-turn", session.providerTurnId());
                assertEquals("第一句话的完整回复", session.transcript().getFirst().body());
                coordinator.onConnectionChanged(true, "重复已连接通知");
            });
            onEdt(() -> {
                assertTrue(sessions.isCurrent(handle), "Duplicate connected events must also preserve the active turn");
                coordinator.onConnectionChanged(false, "连接中断");
            });
            onEdt(() -> {
                assertFalse(sessions.isCurrent(handle), "A real disconnect must still invalidate old turns");
                assertFalse(sessions.isCurrent(backgroundHandle));
                assertTrue(sessions.isCurrent(claudeHandle), "Codex disconnection must not cancel Claude");
                assertEquals("original-thread", session.threadId());
                assertEquals("第一句话的完整回复", session.transcript().getFirst().body());
            });
            assertTrue(checks.await(10, TimeUnit.SECONDS));
        } finally {
            coordinator.dispose(); claude.dispose(); relay.dispose(); providerSettings.dispose(); tracker.dispose(); codex.dispose();
            onEdt(() -> Disposer.dispose(parent));
        }
    }

    private static void onEdt(ThrowableRunnable<Exception> runnable) throws Exception {
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, runnable);
    }
}
