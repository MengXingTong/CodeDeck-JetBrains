package com.codexgui.ui;

import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.model.Attachment;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.FileReference;
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
        var editorContext = new java.util.concurrent.atomic.AtomicReference<EditorFileContext>();
        var workspace = new WorkspaceInputCoordinator(null, null, null, root, registry::active,
            new WorkspaceInputCoordinator.Listener() {
                @Override public void attachmentsChanged(com.codexgui.conversation.ConversationSession session) {}
                @Override public void fileReferencesChanged(com.codexgui.conversation.ConversationSession session) {}
                @Override public void nativeDragChanged(boolean active) {}
                @Override public void nativeDrop(double x, double y) {}
                @Override public void projectFilesLoaded(long requestId, java.util.List<com.codexgui.service.ProjectFileSearch.Candidate> files) {}
            });
        var coordinator = new ConversationCoordinator(null, null, codex, null, null, provider, tracker, approvals,
            registry, sessions, null, providerSettings, workspace, null, presenter, new ConversationCoordinator.Listener() {
                @Override public void settingsChanged() {}
                @Override public EditorFileContext currentEditorContext() { return editorContext.get(); }
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

            onEdt(() -> {
                // Reproduce a synchronous settings failure after the local turn becomes busy.
                settings.getState().globalInstructions = null;
                var dispatch = ConversationCoordinator.class.getDeclaredMethod("dispatchInput",
                    com.codexgui.conversation.ConversationSession.class,
                    com.codexgui.conversation.QueuedInput.class, boolean.class);
                dispatch.setAccessible(true);
                dispatch.invoke(coordinator, session,
                    new com.codexgui.conversation.QueuedInput("保留问题", "保留问题", java.util.List.of(), java.util.List.of()), true);
                assertFalse(session.busy(), "Synchronous preparation failure must release the UI");
                assertNull(session.handle());
                assertNull(session.pendingUserBody());
                assertEquals(0, session.pendingUserMessageCount());
                assertEquals(com.codexgui.model.ConversationEntry.Kind.ERROR, session.transcript().getLast().kind());
                assertTrue(session.transcript().stream().anyMatch(entry -> entry.body().equals("保留问题")));
                settings.getState().globalInstructions = "";

                // Stop must work even before thread/start or turn/start has returned an ID.
                session.threadId(null);
                var pending = sessions.beginTurn(session.id());
                coordinator.interruptCurrentTurn();
                assertFalse(session.busy());
                assertFalse(sessions.isCurrent(pending));
                assertNull(session.handle());
                assertFalse(sessions.apply(pending, current -> fail("Late callback revived the cancelled turn")));
            });

            onEdt(() -> {
                var running = sessions.beginTurn(session.id());
                session.threadId("queued-thread");
                session.bindProviderTurn("running-turn");
                session.pendingUserMessageCount(1);
                var transcriptSize = session.transcript().size();
                var reference = FileReference.fromPath(root.resolve("Queued Reference.java"));
                var attachment = new Attachment(Attachment.Kind.IMAGE, "queued.png", root.resolve("queued.png"));
                session.addFileReference(reference);
                session.attachments().add(attachment);
                var ids = new JsonArray(); ids.add(reference.id());

                var capturedContext = new EditorFileContext(root.resolve("Context.java").toString(), 2, 2, "original context");
                settings.getState().sendOpenedFilePath = true;
                editorContext.set(capturedContext);
                coordinator.sendInput("\uFFFC稍后处理", ids);
                settings.getState().sendOpenedFilePath = false;
                editorContext.set(new EditorFileContext(root.resolve("Other.java").toString(), 1, 1, "new context"));
                coordinator.sendInput("第二条排队消息", new JsonArray());
                assertEquals(2, session.queuedInputs().size());
                assertEquals(transcriptSize, session.transcript().size(), "Queued inputs must not appear as sent messages");
                assertEquals(1, session.pendingUserMessageCount(), "Only sent messages can await a provider echo");
                assertEquals(running, session.handle(), "Ordinary send must not replace or steer the running turn");
                assertTrue(session.attachments().isEmpty());
                assertTrue(session.fileReferences().isEmpty());
                var first = session.queuedInputs().getFirst();
                assertEquals(java.util.List.of(reference), first.fileReferences());
                assertEquals(java.util.List.of(attachment), first.attachments());
                coordinator.queuedInputAction(first.id(), "edit", "");
                assertEquals(first.id(), session.editingQueuedInputId());
                coordinator.queuedInputAction(first.id(), "save", first.editableText() + "（已编辑）");
                assertNull(session.editingQueuedInputId());
                var edited = session.queuedInputs().getFirst();
                assertEquals(first.id(), edited.id());
                assertEquals(capturedContext, edited.editorContext());
                assertEquals(first.fileReferences(), edited.fileReferences());
                assertEquals(first.attachments(), edited.attachments());
                assertTrue(edited.inputText().contains("original context"));
                assertFalse(edited.inputText().contains("new context"));
                assertTrue(edited.editableText().endsWith("（已编辑）"));
                first = edited;
                coordinator.sendInput("将被删除", new JsonArray());
                var deletedId = session.queuedInputs().getLast().id();
                coordinator.queuedInputAction(deletedId, "delete", "");
                coordinator.queuedInputAction(deletedId, "save", "迟到的编辑");
                coordinator.queuedInputAction(deletedId, "steer", "");
                assertEquals(2, session.queuedInputs().size());
                assertEquals(transcriptSize, session.transcript().size());

                var render = ConversationCoordinator.class.getDeclaredMethod("renderCompletedItem",
                    com.codexgui.conversation.ConversationSession.class, JsonObject.class);
                render.setAccessible(true);
                var echo = new JsonObject(); echo.addProperty("type", "userMessage");
                echo.add("content", new JsonArray());
                render.invoke(coordinator, session, echo);
                assertEquals(0, session.pendingUserMessageCount());
                assertEquals(transcriptSize, session.transcript().size());

                // Complete locally, then drain in the background. The disconnected service starts no CLI.
                sessions.complete(running, current -> {});
                var foreground = registry.create(SessionId.of("queue-foreground"));
                registry.activate(foreground.id());
                var drain = ConversationCoordinator.class.getDeclaredMethod("startNextQueuedInput",
                    com.codexgui.conversation.ConversationSession.class);
                drain.setAccessible(true);
                drain.invoke(coordinator, session);
                assertEquals(1, session.queuedInputs().size());
                assertEquals(transcriptSize + 1, session.transcript().size());
                var sent = session.transcript().getLast();
                assertEquals(ConversationEntry.Kind.USER, sent.kind());
                assertEquals(first.display(), sent.body());
                assertEquals(java.util.List.of(reference.path().toString()), sent.fileReferencePaths());
                assertEquals(1, session.pendingUserMessageCount());
                render.invoke(coordinator, session, echo);
                assertEquals(transcriptSize + 1, session.transcript().size(), "Provider echo must not duplicate the dispatched input");
                assertEquals(0, session.pendingUserMessageCount());
                provider.complete(session.handle());
                sessions.cancel(session.id()); // Reject the disconnected service's queued callbacks.

                drain.invoke(coordinator, session);
                assertTrue(session.queuedInputs().isEmpty());
                assertEquals("第二条排队消息", session.transcript().getLast().body());
                assertEquals(transcriptSize + 2, session.transcript().size());
                assertEquals(foreground.id(), registry.activeSessionId());
                assertTrue(foreground.transcript().isEmpty());
                provider.complete(session.handle());
                sessions.cancel(session.id());

                // Stopping a turn dispatches the next queued input with exactly one expected echo.
                registry.activate(session.id());
                sessions.beginTurn(session.id());
                var beforeStop = session.transcript().size();
                coordinator.sendInput("停止后发送第一条", new JsonArray());
                coordinator.sendInput("停止后发送第二条", new JsonArray());
                assertEquals(beforeStop, session.transcript().size());
                coordinator.interruptCurrentTurn();
                assertEquals(1, session.queuedInputs().size());
                assertEquals("停止后发送第一条", session.transcript().getLast().body());
                assertEquals(1, session.pendingUserMessageCount(), "Unsent inputs must not consume provider echo slots after stop");
                provider.complete(session.handle());
                sessions.cancel(session.id());
                session.queuedInputs().clear();

                var editingTurn = sessions.beginTurn(session.id());
                coordinator.sendInput("编辑期间等待", new JsonArray());
                var editingId = session.queuedInputs().getFirst().id();
                coordinator.queuedInputAction(editingId, "edit", "");
                sessions.complete(editingTurn, current -> {});
                var beforeDrain = session.transcript().size();
                drain.invoke(coordinator, session);
                assertEquals(1, session.queuedInputs().size(), "Finishing a turn must not send an input being edited");
                assertEquals(beforeDrain, session.transcript().size());
                coordinator.sendInput("编辑等待期间的新消息", new JsonArray());
                assertEquals(2, session.queuedInputs().size(), "New input cannot jump ahead of an input being edited");
                assertEquals(editingId, session.queuedInputs().getFirst().id());
                assertEquals(beforeDrain, session.transcript().size());
                coordinator.queuedInputAction(editingId, "save", "保存后发送");
                assertEquals("保存后发送", session.transcript().getLast().body());
                assertEquals(1, session.queuedInputs().size());
                assertEquals("编辑等待期间的新消息", session.queuedInputs().getFirst().text());
                provider.complete(session.handle());
                sessions.cancel(session.id());
                session.queuedInputs().clear();
            });

            var wire = new java.io.StringWriter();
            var transportGeneration = readyTransport(codex, wire);
            var queuedIds = new String[2];
            var beforeSteer = new int[1];
            onEdt(() -> {
                session.pendingUserMessageCount(0);
                sessions.beginTurn(session.id());
                session.threadId("steering-thread");
                session.bindProviderTurn("steering-turn");
                beforeSteer[0] = session.transcript().size();
                coordinator.sendInput("队首立即引导", new JsonArray());
                coordinator.sendInput("失败后恢复的消息", new JsonArray());
                queuedIds[0] = session.queuedInputs().getFirst().id();
                queuedIds[1] = session.queuedInputs().getLast().id();
                coordinator.queuedInputAction(queuedIds[1], "steer", "");
                assertTrue(wire.toString().isEmpty(), "Only the first queued input can steer");
                coordinator.queuedInputAction(queuedIds[0], "steer", "");
                coordinator.queuedInputAction(queuedIds[0], "steer", "");
                coordinator.queuedInputAction(queuedIds[1], "steer", "");
                assertTrue(session.queuedSteerPending());
                assertEquals(1, session.queuedInputs().size());
                assertEquals(beforeSteer[0], session.transcript().size(), "Wait for the steering acknowledgement before publishing");
                assertEquals(1, wire.toString().lines().count(), "Repeated clicks cannot submit a message twice");
                var request = com.google.gson.JsonParser.parseString(wire.toString().strip()).getAsJsonObject();
                assertEquals("turn/steer", request.get("method").getAsString());
                assertEquals("steering-turn", request.getAsJsonObject("params").get("expectedTurnId").getAsString());
            });
            replyToLastRequest(codex, transportGeneration, wire, false);
            onEdt(() -> {
                assertEquals(beforeSteer[0] + 1, session.transcript().size());
                assertEquals("队首立即引导", session.transcript().getLast().body());
                assertFalse(session.queuedSteerPending());
                coordinator.queuedInputAction(queuedIds[1], "steer", "");
                assertTrue(session.queuedInputs().isEmpty());
            });
            replyToLastRequest(codex, transportGeneration, wire, true);
            onEdt(() -> {
                assertEquals(1, session.queuedInputs().size());
                assertEquals(queuedIds[1], session.queuedInputs().getFirst().id());
                assertEquals("失败后恢复的消息", session.queuedInputs().getFirst().text());
                assertFalse(session.queuedSteerPending());
                assertEquals(beforeSteer[0] + 1, session.transcript().size(), "Failed steering must not create a sent message");
                sessions.cancel(session.id());
                session.queuedInputs().clear();
            });
        } finally {
            coordinator.dispose(); claude.dispose(); relay.dispose(); providerSettings.dispose(); tracker.dispose(); codex.dispose();
            onEdt(() -> Disposer.dispose(parent));
        }
    }

    private static void onEdt(ThrowableRunnable<Exception> runnable) throws Exception {
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, runnable);
    }

    private static long readyTransport(CodexAppServerService codex, java.io.StringWriter wire) throws Exception {
        // In-memory JSON-RPC transport exercises the production service without a CLI or model call.
        var lifecycleField = CodexAppServerService.class.getDeclaredField("lifecycle");
        lifecycleField.setAccessible(true);
        var lifecycle = lifecycleField.get(codex);
        var begin = lifecycle.getClass().getDeclaredMethod("beginStart"); begin.setAccessible(true);
        var generation = (long) begin.invoke(lifecycle);
        var transition = lifecycle.getClass().getDeclaredMethod("transition", long.class, CodexAppServerService.LifecycleState.class);
        transition.setAccessible(true); transition.invoke(lifecycle, generation, CodexAppServerService.LifecycleState.READY);
        var contextField = CodexAppServerService.class.getDeclaredField("context"); contextField.setAccessible(true);
        var constructor = contextField.getType().getDeclaredConstructor(long.class, Process.class, java.io.BufferedWriter.class);
        constructor.setAccessible(true);
        contextField.set(codex, constructor.newInstance(generation, null, new java.io.BufferedWriter(wire)));
        return generation;
    }

    private static void replyToLastRequest(CodexAppServerService codex, long generation, java.io.StringWriter wire, boolean fail) throws Exception {
        var request = com.google.gson.JsonParser.parseString(wire.toString().lines().reduce((a, b) -> b).orElseThrow()).getAsJsonObject();
        var response = new JsonObject(); response.add("id", request.get("id"));
        if (fail) {
            var error = new JsonObject(); error.addProperty("code", -32000); error.addProperty("message", "test steering failure");
            response.add("error", error);
        } else response.add("result", new JsonObject());
        var receive = CodexAppServerService.class.getDeclaredMethod("handleProtocolMessage", long.class, String.class);
        receive.setAccessible(true); receive.invoke(codex, generation, response.toString());
    }
}
