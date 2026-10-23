package com.codexgui.ui;

import com.codexgui.bridge.BridgeEvent;
import com.codexgui.bridge.BridgeProtocolCodec;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.service.ProjectFileSearch;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.jcef.JBCefBrowser;

import java.nio.file.Path;
import java.util.List;

final class BridgeEventPresenter {
    private final JBCefBrowser browser;
    private final BridgeProtocolCodec bridgeCodec;
    private final SessionRegistry sessionRegistry;
    private final Path root;

    private boolean pageReady;

    BridgeEventPresenter(
        JBCefBrowser browser,
        BridgeProtocolCodec bridgeCodec,
        SessionRegistry sessionRegistry,
        Path root
    ) {
        this.browser = browser;
        this.bridgeCodec = bridgeCodec;
        this.sessionRegistry = sessionRegistry;
        this.root = root;
    }

    void pageReady() {
        pageReady = true;
    }

    void protocolEvent(BridgeEvent event, boolean allowBeforeReady) {
        if ((!pageReady && !allowBeforeReady) || browser == null || browser.isDisposed()) return;
        var json = bridgeCodec.encodeEvent(event);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!browser.isDisposed()) {
                browser.getCefBrowser().executeJavaScript(
                    "window.CodexGui && window.CodexGui.receive(" + json + ");",
                    "http://codex-gui.local/",
                    0
                );
            }
        });
    }

    void bootstrap(ConversationSession session, JsonObject state) {
        state.add("queuedInputs", queuedInputsJson(session));
        state.addProperty("queuedCount", session.queuedInputs().size());
        state.addProperty("canSteer", canSteer(session));
        state.addProperty("editingQueuedInputId", session.editingQueuedInputId());
        var event = event(BridgeEvent.Type.BOOTSTRAP, session.id());
        event.add("state", state);
        send(event);
    }

    void clear(ConversationSession session) {
        clear(session, false);
    }

    void clear(ConversationSession session, boolean preserveQueue) {
        var event = event(BridgeEvent.Type.CLEAR, session.id());
        event.addProperty("preserveQueue", preserveQueue);
        event.addProperty("title", session.title());
        event.addProperty("provider", session.provider());
        if (session.threadId() != null) event.addProperty("threadId", session.threadId());
        send(event);
    }

    void usage(ConversationSession session) {
        if (session.usageMaxTokens() <= 0) return;
        var event = event(BridgeEvent.Type.USAGE, session.id());
        event.addProperty("usedTokens", session.usageUsedTokens());
        event.addProperty("maxTokens", session.usageMaxTokens());
        event.addProperty("percentage", Math.min(
            100.0,
            session.usageUsedTokens() * 100.0 / session.usageMaxTokens()
        ));
        send(event);
    }

    void usageIncludingEmpty(ConversationSession session) {
        var event = event(BridgeEvent.Type.USAGE, session.id());
        event.addProperty("usedTokens", session.usageUsedTokens());
        event.addProperty("maxTokens", session.usageMaxTokens());
        event.addProperty("percentage", session.usageMaxTokens() > 0
            ? Math.min(100.0, session.usageUsedTokens() * 100.0 / session.usageMaxTokens())
            : 0.0);
        send(event);
    }

    void partialBootstrap(ConversationSession session, JsonObject state) {
        bootstrap(session, state);
    }

    void providerSaveResult(boolean success, String message) {
        var event = event(BridgeEvent.Type.TOAST);
        event.addProperty("message", message);
        event.addProperty("providerSaveSuccess", success);
        send(event);
    }

    void providers(JsonArray providers) {
        var event = event(BridgeEvent.Type.PROVIDERS);
        event.add("items", providers);
        send(event);
    }

    void history(ConversationSession session, String provider, JsonArray items) {
        var event = event(BridgeEvent.Type.HISTORY, session.id());
        event.addProperty("provider", provider);
        event.add("items", items);
        send(event);
    }

    void nativeDrag(boolean active) {
        var event = event(BridgeEvent.Type.NATIVE_DRAG);
        event.addProperty("active", active);
        send(event);
    }

    void nativeDrop(double x, double y) {
        var event = event(BridgeEvent.Type.NATIVE_DROP);
        event.addProperty("x", x);
        event.addProperty("y", y);
        send(event);
    }

    void projectFiles(long requestId, List<ProjectFileSearch.Candidate> files) {
        var event = event(BridgeEvent.Type.PROJECT_FILES).requestId(Long.toString(requestId));
        var items = new JsonArray();
        for (var file : files) {
            var item = new JsonObject();
            // 绝对路径用于创建引用，相对路径只负责候选列表展示。
            item.addProperty("path", file.path().toString());
            item.addProperty("displayPath", file.displayPath());
            item.addProperty("name", file.name());
            items.add(item);
        }
        event.add("items", items);
        send(event);
    }

    void attachments(ConversationSession session) {
        var event = event(BridgeEvent.Type.ATTACHMENTS, session.id());
        event.add("items", attachmentsJson(session));
        send(event);
    }

    void fileReferences(ConversationSession session) {
        var event = event(BridgeEvent.Type.FILE_REFERENCES, session.id());
        event.add("items", fileReferencesJson(session));
        send(event);
    }

    void changes(String sessionId, List<ConversationChangeTracker.ChangeSummary> changes) {
        var items = new JsonArray();
        for (var change : changes) {
            var item = new JsonObject();
            item.addProperty("path", change.displayName(root).replace('\\', '/'));
            item.addProperty("kind", change.kind().name());
            item.addProperty("reversible", change.reversible());
            item.addProperty("addedLines", change.addedLines());
            item.addProperty("deletedLines", change.deletedLines());
            items.add(item);
        }
        var event = event(BridgeEvent.Type.CHANGES, SessionId.of(sessionId));
        event.add("items", items);
        send(event);
    }

    void fileContext(EditorFileContext context) {
        var event = event(BridgeEvent.Type.FILE_CONTEXT);
        addFileContext(event, context);
        send(event);
    }

    void busy(ConversationSession session) {
        var event = event(BridgeEvent.Type.BUSY, session.id());
        event.addProperty("busy", session.busy());
        event.addProperty("queuedCount", session.queuedInputs().size());
        event.add("queuedInputs", queuedInputsJson(session));
        event.addProperty("canSteer", canSteer(session));
        event.addProperty("editingQueuedInputId", session.editingQueuedInputId());
        send(event);
    }

    void queue(ConversationSession session) {
        var event = event(BridgeEvent.Type.QUEUE, session.id());
        event.addProperty("queuedCount", session.queuedInputs().size());
        event.add("queuedInputs", queuedInputsJson(session));
        event.addProperty("canSteer", canSteer(session));
        event.addProperty("editingQueuedInputId", session.editingQueuedInputId());
        send(event);
    }

    private static boolean canSteer(ConversationSession session) {
        return session.busy() && !session.queuedSteerPending() && "codex".equals(session.provider())
            && session.threadId() != null && !session.threadId().isBlank()
            && session.providerTurnId() != null && !session.providerTurnId().isBlank();
    }

    static JsonArray queuedInputsJson(ConversationSession session) {
        var items = new JsonArray();
        for (var input : session.queuedInputs()) {
            var item = new JsonObject();
            item.addProperty("id", input.id());
            item.addProperty("text", input.editableText());
            if (input.editorContext() != null) item.addProperty("context", input.editorContext().displayLabel());
            var attachments = new JsonArray();
            input.attachments().forEach(attachment -> attachments.add(attachment.name()));
            item.add("attachments", attachments);
            items.add(item);
        }
        return items;
    }

    void message(ConversationSession session, ConversationEntry entry) {
        var event = event(BridgeEvent.Type.MESSAGE, session.id());
        event.add("entry", entryJson(entry));
        send(event);
    }

    void replaceMessage(ConversationSession session, ConversationEntry entry) {
        var event = event(BridgeEvent.Type.REPLACE_MESSAGE, session.id());
        event.add("entry", entryJson(entry));
        send(event);
    }

    void appendMessage(
        ConversationSession session,
        String itemId,
        ConversationEntry.Kind kind,
        String title,
        String delta
    ) {
        var event = event(BridgeEvent.Type.APPEND_MESSAGE, session.id());
        event.addProperty("itemId", itemId);
        event.addProperty("kind", kind.name().toLowerCase());
        event.addProperty("title", title);
        event.addProperty("delta", delta);
        send(event);
    }

    void threadTitle(ConversationSession session) {
        var event = event(BridgeEvent.Type.THREAD, session.id());
        event.addProperty("id", session.threadId());
        event.addProperty("title", session.title());
        event.addProperty("titleOnly", true);
        send(event);
    }

    void thread(ConversationSession session) {
        thread(session, false);
    }

    void thread(ConversationSession session, boolean activate) {
        var event = event(BridgeEvent.Type.THREAD, session.id());
        if (session.threadId() != null) event.addProperty("id", session.threadId());
        event.addProperty("title", session.title());
        event.addProperty("provider", session.provider());
        event.addProperty("historyReadOnly", session.historyReadOnly());
        event.addProperty("activate", activate);
        send(event);
    }

    void toast(ConversationSession session, String message) {
        var event = event(BridgeEvent.Type.TOAST, session.id());
        event.addProperty("message", message);
        send(event);
    }

    void mcpServers(ConversationSession session, JsonArray items) {
        var event = event(BridgeEvent.Type.MCP_SERVERS, session.id());
        event.add("items", items);
        send(event);
    }

    void mcpLog(String level, String serverName, String message) {
        var event = event(BridgeEvent.Type.MCP_LOG);
        event.addProperty("level", level);
        event.addProperty("serverName", serverName);
        event.addProperty("message", message);
        send(event);
    }

    void skills(JsonArray items, JsonArray errors) {
        var event = event(BridgeEvent.Type.SKILLS);
        event.add("items", items);
        event.add("errors", errors);
        send(event);
    }

    void skillEnabled(String path, boolean enabled) {
        var event = event(BridgeEvent.Type.SKILL_ENABLED);
        event.addProperty("path", path);
        event.addProperty("enabled", enabled);
        send(event);
    }

    void connection(ConversationSession session, boolean connected, String detail) {
        var event = event(BridgeEvent.Type.CONNECTION, session.id());
        event.addProperty("connected", connected);
        event.addProperty("detail", detail);
        send(event);
    }

    void questions(ConversationSession session, long requestId, JsonArray questions) {
        var event = event(BridgeEvent.Type.QUESTION, session.id()).requestId(Long.toString(requestId));
        event.add("questions", questions);
        send(event);
    }

    JsonArray attachmentsJson(ConversationSession session) {
        var items = new JsonArray();
        for (var attachment : session.attachments()) {
            var item = new JsonObject();
            item.addProperty("kind", attachment.kind().name());
            item.addProperty("name", attachment.name());
            item.addProperty("path", attachment.path().toString());
            items.add(item);
        }
        return items;
    }

    JsonArray fileReferencesJson(ConversationSession session) {
        var items = new JsonArray();
        for (var reference : session.fileReferences()) {
            var item = new JsonObject();
            item.addProperty("id", reference.id());
            item.addProperty("name", reference.name());
            item.addProperty("path", reference.path().toString());
            item.addProperty("directory", reference.directory());
            items.add(item);
        }
        return items;
    }

    static JsonObject entryJson(ConversationEntry entry) {
        var json = new JsonObject();
        json.addProperty("kind", entry.kind().name().toLowerCase());
        json.addProperty("title", entry.title());
        json.addProperty("body", entry.body());
        json.addProperty("createdAtEpochMs", entry.createdAtEpochMs());
        if (!entry.fileReferencePaths().isEmpty()) {
            var references = new JsonArray();
            entry.fileReferencePaths().forEach(references::add);
            json.add("fileReferencePaths", references);
        }
        if (entry.itemId() != null) json.addProperty("itemId", entry.itemId());
        return json;
    }

    void addBehaviorSettings(
        JsonObject state,
        CodexSettingsState.StateData settings,
        ConversationSession session
    ) {
        state.addProperty("sendShortcut", settings.sendShortcut);
        state.addProperty("permissionDialogTimeoutSeconds", settings.permissionDialogTimeoutSeconds);
        state.addProperty("sendOpenedFilePath", settings.sendOpenedFilePath);
        state.addProperty("diffExpandedByDefault", settings.diffExpandedByDefault);
        state.addProperty("preserveFileLineEndings", settings.preserveFileLineEndings);
        state.addProperty("newSessionConfirmEnabled", settings.newSessionConfirmEnabled);
        state.addProperty("askUserQuestionNotificationEnabled", settings.askUserQuestionNotificationEnabled);
        state.addProperty("askUserQuestionSoundEnabled", settings.askUserQuestionSoundEnabled);
        state.addProperty("taskCompletionNotificationEnabled", settings.taskCompletionNotificationEnabled);
        state.addProperty("taskCompletionSoundEnabled", settings.taskCompletionSoundEnabled);
        state.addProperty("systemNotificationOnlyWhenUnfocused", settings.systemNotificationOnlyWhenUnfocused);
        state.addProperty("soundOnlyWhenUnfocused", settings.soundOnlyWhenUnfocused);
        state.addProperty("notificationSound", settings.notificationSound);
        state.addProperty("customSoundPath", settings.customSoundPath);
        // Editor context belongs to the workspace. Session/settings snapshots must not
        // replace it when a new tab refreshes settings with focus inside the chat.
        state.addProperty("usageUsedTokens", session.usageUsedTokens());
        state.addProperty("usageMaxTokens", session.usageMaxTokens());
        state.addProperty("usagePercentage", session.usageMaxTokens() > 0
            ? Math.min(100.0, session.usageUsedTokens() * 100.0 / session.usageMaxTokens())
            : 0.0);
    }

    private void addFileContext(BridgeEvent.Builder target, EditorFileContext context) {
        target.addProperty("path", context == null ? "" : context.path());
        target.addProperty("startLine", context == null ? 0 : context.startLine());
        target.addProperty("endLine", context == null ? 0 : context.endLine());
        target.addProperty("selectionLabel", context == null ? "" : context.selectionLabel());
    }

    private BridgeEvent.Builder event(BridgeEvent.Type type) {
        return event(type, sessionRegistry.activeSessionId());
    }

    private BridgeEvent.Builder event(BridgeEvent.Type type, SessionId sessionId) {
        var session = sessionRegistry.find(sessionId);
        var handle = session == null ? null : session.handle();
        return new BridgeEvent.Builder(
            type,
            sessionId,
            handle == null ? "" : handle.turnId().value(),
            session == null ? 0 : session.generation()
        );
    }

    private void send(BridgeEvent.Builder event) {
        protocolEvent(event.build(), false);
    }
}
