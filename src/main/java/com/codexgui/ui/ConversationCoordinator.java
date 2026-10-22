package com.codexgui.ui;

import com.codexgui.i18n.UiMessages;

import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.Attachment;
import com.codexgui.model.EditorFileContext;
import com.codexgui.model.FileReference;
import com.codexgui.conversation.QueuedInput;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.bridge.BridgeCommand;
import com.codexgui.provider.ClaudeConversationProvider;
import com.codexgui.provider.CodexConversationProvider;
import com.codexgui.provider.CodexError;
import com.codexgui.provider.TurnEvent;
import com.codexgui.provider.TurnRequest;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexEventListener;
import com.codexgui.service.CodexTitleGenerator;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.service.FileReferenceText;
import com.codexgui.service.AttentionService;
import com.codexgui.service.Utf8IO;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.JFileChooser;
import javax.swing.JPanel;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class ConversationCoordinator implements Disposable, CodexEventListener {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    interface Listener {
        void settingsChanged();
        EditorFileContext currentEditorContext();
        boolean handleNativeCommand(String text);
        boolean handleFeatureNotification(String method, JsonObject params);
        void connectionRestored();
    }

    private final Project project;
    private final JPanel owner;
    private final CodexAppServerService codex;
    private final ClaudeCodeService claude;
    private final ClaudeConversationProvider claudeProvider;
    private final CodexConversationProvider codexProvider;
    private final ConversationChangeTracker changeService;
    private final ApprovalCoordinator approvalCoordinator;
    private final SessionRegistry sessionRegistry;
    private final SessionCoordinator sessionCoordinator;
    private final ChangeReviewCoordinator changeReview;
    private final ProviderSettingsCoordinator providerSettings;
    private final WorkspaceInputCoordinator workspaceInput;
    private final AttentionService attentionService;
    private final BridgeEventPresenter presenter;
    private final Listener listener;
    private final CodexTitleGenerator titleGenerator;
    private final ConversationTitles titles;
    private final Set<String> confirmedSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, StringBuilder> pendingCommandDeltas = new ConcurrentHashMap<>();
    private final Set<String> scheduledCommandDeltas = ConcurrentHashMap.newKeySet();
    private final Set<String> completedCommandItems = ConcurrentHashMap.newKeySet();
    private final Map<SessionId, Object> openingHistory = new ConcurrentHashMap<>();

    ConversationCoordinator(
        Project project,
        JPanel owner,
        CodexAppServerService codex,
        ClaudeCodeService claude,
        ClaudeConversationProvider claudeProvider,
        CodexConversationProvider codexProvider,
        ConversationChangeTracker changeService,
        ApprovalCoordinator approvalCoordinator,
        SessionRegistry sessionRegistry,
        SessionCoordinator sessionCoordinator,
        ChangeReviewCoordinator changeReview,
        ProviderSettingsCoordinator providerSettings,
        WorkspaceInputCoordinator workspaceInput,
        AttentionService attentionService,
        BridgeEventPresenter presenter,
        Listener listener
    ) {
        this.project = project;
        this.owner = owner;
        this.codex = codex;
        this.claude = claude;
        this.claudeProvider = claudeProvider;
        this.codexProvider = codexProvider;
        this.changeService = changeService;
        this.approvalCoordinator = approvalCoordinator;
        this.sessionRegistry = sessionRegistry;
        this.sessionCoordinator = sessionCoordinator;
        this.changeReview = changeReview;
        this.providerSettings = providerSettings;
        this.workspaceInput = workspaceInput;
        this.attentionService = attentionService;
        this.presenter = presenter;
        this.listener = listener;
        titleGenerator = new CodexTitleGenerator(codex);
        titles = new ConversationTitles(sessionRegistry, titleGenerator::generate, codex::setThreadName,
            action -> ApplicationManager.getApplication().invokeLater(action), presenter::threadTitle, () -> loadHistory(""),
            error -> com.intellij.openapi.diagnostic.Logger.getInstance(ConversationCoordinator.class)
                .info(UiMessages.message("Codex 会话标题生成失败，保留现有名称"), error));
        codex.addListener(this);
    }

    String activeSessionId() {
        return sessionRegistry.activeSessionId().value();
    }

    ConversationSession activeSession() {
        return sessionRegistry.active();
    }

    ConversationSession session(String sessionId) {
        return sessionRegistry.find(SessionId.of(sessionId));
    }

    void activateSession(String sessionId) {
        var id = SessionId.of(sessionId);
        var existing = sessionRegistry.find(id);
        var session = sessionRegistry.activate(id);
        if (existing != null) return;

        // 新页签继承当前全局渠道，但保存独立的供应商版本绑定。
        var settingsService = CodexSettingsState.getInstance();
        session.provider(ProviderSettingsCoordinator.providerChannel(settingsService.getState().activeProvider));
        var activeProvider = settingsService.activeProvider(session.provider());
        session.providerProfileId(activeProvider.id);
        session.providerRevision(activeProvider.revision);
    }

    void publishCurrentSession() {
        var session = activeSession();
        presenter.clear(session);
        session.transcript().forEach(entry -> presenter.message(session, entry));
        presenter.attachments(session);
        presenter.fileReferences(session);
        presenter.changes(session.id().value(), changeService.listSummaries(session.id().value()));
        presenter.thread(session);
        presenter.usageIncludingEmpty(session);
        presenter.busy(session);
        // 页签激活后重发该渠道的模型与策略，不能沿用前一个页签的供应商状态。
        listener.settingsChanged();
    }

    void loadHistory(String search) {
        var session = activeSession();
        var historyProvider = session.provider();
        // Claude 历史来自当前项目的 JSONL 文件，不经过 Codex app-server。
        if (Objects.equals(historyProvider, CodexSettingsState.CLAUDE_CHANNEL)) {
            claude.listHistory(search).thenAccept(history -> {
                var items = new JsonArray();
                for (var entry : history) {
                    var item = new JsonObject();
                    item.addProperty("id", entry.id());
                    item.addProperty("title", entry.title());
                    item.addProperty("favorite", false);
                    item.addProperty("time", Instant.ofEpochMilli(entry.updatedAtEpochMs())
                        .atZone(ZoneId.systemDefault()).format(HISTORY_TIME));
                    items.add(item);
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    var current = sessionRegistry.find(session.id());
                    if (current == session && Objects.equals(current.provider(), historyProvider)) {
                        presenter.history(session, historyProvider, items);
                    }
                });
            }).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(
                    () -> asyncError(session, UiMessages.message("无法读取 Claude 历史"), error));
                return null;
            });
            return;
        }
        // app-server 读取共享 CODEX_HOME 的历史，不限制客户端来源或当前项目。
        if (!codex.isConnected()) return;
        codex.listThreads(search).thenAccept(result -> {
            var items = new JsonArray();
            for (var element : array(result, "data")) {
                var thread = element.getAsJsonObject();
                var id = string(thread, "id", "");
                var title = string(thread, "name", "");
                if (title.isBlank()) title = string(thread, "preview", UiMessages.message("未命名会话"));
                var item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("title", title);
                item.addProperty("cwd", string(thread, "cwd", ""));
                item.addProperty("favorite", CodexSettingsState.getInstance().isFavorite(id));
                if (thread.has("updatedAt")) {
                    item.addProperty("time", Instant.ofEpochSecond(thread.get("updatedAt").getAsLong())
                        .atZone(ZoneId.systemDefault()).format(HISTORY_TIME));
                }
                items.add(item);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                var current = sessionRegistry.find(session.id());
                if (current == session && Objects.equals(current.provider(), historyProvider)) {
                    presenter.history(session, historyProvider, items);
                }
            });
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(
                () -> asyncError(session, UiMessages.message("无法读取 Codex 历史"), error));
            return null;
        });
    }

    void openThread(String threadId) {
        var session = activeSession();
        if (threadId.isBlank()) return;
        var provider = session.provider();
        // 优先定位已有页签，运行中的回合和草稿都留在原会话中。
        var existing = sessionRegistry.sessions().stream()
            .filter(candidate -> Objects.equals(candidate.provider(), provider)
                && Objects.equals(candidate.threadId(), threadId) && !candidate.historyReadOnly())
            .findFirst().orElse(null);
        if (existing != null) {
            sessionRegistry.activate(existing.id());
            presenter.thread(existing, true);
            return;
        }
        if (session.busy()) {
            presenter.toast(session, UiMessages.message("当前页签有任务正在运行，请新建页签后打开其他历史会话"));
            return;
        }
        var requestToken = new Object();
        if (openingHistory.putIfAbsent(session.id(), requestToken) != null) {
            presenter.toast(session, UiMessages.message("正在打开历史会话，请稍候"));
            return;
        }
        var generation = session.generation();
        // Claude 会话直接解析项目历史，并绑定原 session ID 供后续 --resume 使用。
        if (Objects.equals(session.provider(), CodexSettingsState.CLAUDE_CHANNEL)) {
            claude.readHistory(threadId).thenAccept(history -> ApplicationManager.getApplication().invokeLater(() -> {
                openingHistory.remove(session.id(), requestToken);
                if (!canApplyHistory(session, provider, generation)) return;
                approvalCoordinator.clearSession(session.id());
                session.clearConversation();
                session.threadId(history.id());
                session.title(history.title());
                presenter.clear(session);
                for (var historyEntry : history.entries()) {
                    var kind = ConversationEntry.Kind.valueOf(historyEntry.kind().name());
                    var title = switch (kind) {
                        // 用户消息沿用聊天区的用户标签。
                        case USER -> UiMessages.message("你");
                        // 助手消息明确标记为 Claude。
                        case ASSISTANT -> "Claude";
                        // 思考内容使用统一的推理标签。
                        case REASONING -> UiMessages.message("思考过程");
                        // 工具调用在历史中按命令条目展示。
                        case COMMAND -> UiMessages.message("工具调用");
                        default -> "Claude";
                    };
                    addEntry(session, new ConversationEntry(
                        kind,
                        title,
                        historyEntry.body(),
                        historyEntry.itemId(),
                        List.of(),
                        historyEntry.createdAtEpochMs()
                    ));
                }
                presenter.attachments(session);
                presenter.fileReferences(session);
                presenter.thread(session);
            })).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    openingHistory.remove(session.id(), requestToken);
                    if (canApplyHistory(session, provider, generation)) asyncError(session, UiMessages.message("无法打开 Claude 历史会话"), error);
                });
                return null;
            });
            return;
        }
        // 外部客户端持有写入权时读取历史快照，避免重复恢复占用中的会话。
        codex.openHistoryThread(threadId).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            openingHistory.remove(session.id(), requestToken);
            if (canApplyHistory(session, provider, generation)) renderThread(session, result);
        })).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                openingHistory.remove(session.id(), requestToken);
                if (canApplyHistory(session, provider, generation)) asyncError(session, UiMessages.message("无法打开历史会话"), error);
            });
            return null;
        });
    }

    private boolean canApplyHistory(ConversationSession session, String provider, long generation) {
        return sessionRegistry.find(session.id()) == session && Objects.equals(session.provider(), provider)
            && session.generation() == generation && !session.busy();
    }

    void renderThread(ConversationSession session, JsonObject result) {
        var thread = result.getAsJsonObject("thread");
        if (thread == null) return;
        approvalCoordinator.clearSession(session.id());
        session.clearConversation();
        session.historyReadOnly(result.has("historyReadOnly") && result.get("historyReadOnly").getAsBoolean());
        session.threadId(string(thread, "id", null));
        session.title(string(thread, "name", ""));
        titles.historyLoaded(session, !session.title().isBlank());
        if (session.title().isBlank()) session.title(string(thread, "preview", UiMessages.message("Codex 会话")));
        presenter.clear(session);
        for (var turnElement : array(thread, "turns")) {
            for (var itemElement : array(turnElement.getAsJsonObject(), "items")) {
                renderCompletedItem(session, itemElement.getAsJsonObject());
            }
        }
        presenter.attachments(session);
        presenter.fileReferences(session);
        presenter.thread(session);
    }

    void newConversation() {
        newConversation("", false, activeSession().provider());
    }

    void newConversation(String requestedTitle, boolean skipConfirmation, String requestedProvider) {
        var session = activeSession();
        if (session.busy()) return;
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        // 命令入口未完成前端确认时，使用原生确认框保护已有会话。
        if (!skipConfirmation && settings.newSessionConfirmEnabled && !session.transcript().isEmpty()
            && Messages.showYesNoDialog(
                project,
                UiMessages.message("当前会话已有消息，确定要新建会话吗？"),
                UiMessages.message("新建会话"),
                UiMessages.message("新建"),
                UiMessages.message("取消"),
                Messages.getQuestionIcon()
            ) != Messages.YES) return;

        // 复用页签开启新对话时，旧回合的修改视为用户已确认。
        var sessionId = session.id().value();
        confirmedSessionIds.remove(sessionId);
        changeService.clearSession(sessionId);
        changeReview.closeSession(sessionId);
        approvalCoordinator.clearSession(session.id());
        openingHistory.remove(session.id());
        session.clearConversation();
        titles.reset(session);
        var selectedProvider = ProviderSettingsCoordinator.providerChannel(requestedProvider);
        var changesChannel = !Objects.equals(session.provider(), selectedProvider);
        session.provider(selectedProvider);
        settings.activeProvider = selectedProvider;
        var activeProvider = settingsService.activeProvider(session.provider());
        session.providerProfileId(activeProvider.id);
        session.providerRevision(activeProvider.revision);
        session.title(requestedTitle == null || requestedTitle.isBlank() ? UiMessages.message("新会话") : requestedTitle.trim());
        presenter.clear(session);
        presenter.attachments(session);
        presenter.fileReferences(session);
        presenter.thread(session);
        // 新页签也可能创建于模型加载之前，始终补发当前渠道设置与模型。
        listener.settingsChanged();
        providerSettings.loadModels(selectedProvider);
        if (changesChannel) {
            providerSettings.publishProviderStatus();
        }
    }

    void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        var session = session(sessionId);
        if (session != null) {
            titles.reset(session);
            openingHistory.remove(session.id());
            // 关闭页签时同时停止进程并使旧回调失效，防止会话被迟到事件复活。
            if (Objects.equals(session.provider(), CodexSettingsState.CLAUDE_CHANNEL)) {
                claudeProvider.cancel(session.handle());
            } else {
                codexProvider.cancel(session.handle());
            }
            approvalCoordinator.clearSession(session.id());
            sessionCoordinator.close(session.id());
        }
        // 页签关闭代表用户确认保留该会话的工作区修改，清理其待处理事务。
        confirmedSessionIds.add(sessionId);
        changeService.clearSession(sessionId);
        changeReview.closeSession(sessionId);
    }

    void renameCurrentThread() {
        var session = activeSession();
        var name = Messages.showInputDialog(
            project,
            UiMessages.message("输入新的会话名称："),
            UiMessages.message("重命名会话"),
            Messages.getQuestionIcon(),
            session.title(),
            null
        );
        if (name == null || name.isBlank()) return;
        titles.named(session);
        session.title(name.trim());
        // 空白页签和 Claude 会话名称由插件本地维护，Codex 会话再同步到 app-server。
        if (session.threadId() == null || Objects.equals(session.provider(), "claude")) {
            presenter.thread(session);
            return;
        }
        codex.setThreadName(session.threadId(), session.title()).thenRun(() -> {
            presenter.thread(session);
            loadHistory("");
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(
                () -> asyncError(session, UiMessages.message("无法重命名会话"), error));
            return null;
        });
    }

    void exportConversation() {
        var session = activeSession();
        if (session.transcript().isEmpty()) return;
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setDialogTitle(UiMessages.message("导出 Codex 会话"));
        chooser.setSelectedFile(new java.io.File("codex-conversation.md"));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        var markdown = new StringBuilder("# ").append(session.title()).append("\n\n");
        session.transcript().forEach(entry -> markdown
            .append("## ").append(entry.title()).append("\n\n")
            .append(entry.body()).append("\n\n"));
        try {
            Utf8IO.write(chooser.getSelectedFile().toPath(), markdown);
            presenter.toast(session, UiMessages.message("会话已导出"));
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("导出失败"));
        }
    }

    ConversationSession sessionForThread(String threadId) {
        var session = sessionRegistry.findByThreadId(threadId);
        return session == null || confirmedSessionIds.contains(session.id().value()) ? null : session;
    }

    void sendInput(String text) {
        sendInput(text, null);
    }

    void sendInput(String text, JsonArray referenceIds) {
        var session = activeSession();
        text = text.trim();
        // 新版编辑器随发送命令提交引用顺序，旧版命令继续使用当前会话顺序。
        if (referenceIds != null && !applySendReferenceOrder(session, text, referenceIds)) return;
        if (text.isBlank() && session.attachments().isEmpty() && session.fileReferences().isEmpty()) return;
        if (session.busy()) {
            enqueueInput(text);
            return;
        }
        var settingsService = CodexSettingsState.getInstance();
        var activeProvider = settingsService.activeProvider(session.provider());
        if (!Objects.equals(session.providerProfileId(), activeProvider.id)
            || session.providerRevision() != activeProvider.revision) {
            presenter.toast(session, UiMessages.message("当前会话使用的供应商配置已变化，请开启新对话后继续"));
            return;
        }
        // 自定义供应商必须先由模型目录确定具体选择，禁止以空模型启动请求。
        if (!activeProvider.builtIn && activeProvider.model.isBlank()) {
            providerSettings.loadProviderModels(activeProvider);
            presenter.toast(session, UiMessages.message("当前供应商尚未加载到可用模型，请等待模型目录加载完成后重试"));
            return;
        }
        if (Objects.equals(session.provider(), "codex") && !codex.isConnected()) {
            var reconnectText = text;
            var sessionId = session.id();
            codex.start().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                if (sessionRegistry.find(sessionId) == session) sendInput(session, reconnectText);
            })).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(() -> asyncError(session, UiMessages.message("Codex CLI 未连接"), error));
                return null;
            });
            return;
        }
        sendInput(session, text);
    }

    void steerInput(String text, JsonArray referenceIds) {
        var session = activeSession();
        text = text.trim();
        // 回合刚好结束时按普通消息发送，避免用户输入丢失。
        if (!session.busy()) {
            sendInput(text, referenceIds);
            return;
        }
        // Claude Code CLI 不支持向当前进程追加输入，降级排队以保留用户输入。
        if (!Objects.equals(session.provider(), "codex")) {
            sendInput(text, referenceIds);
            presenter.toast(session, UiMessages.message("Claude Code 运行中暂不支持立即引导，消息已加入队列"));
            return;
        }
        if (referenceIds != null && !applySendReferenceOrder(session, text, referenceIds)) return;
        if (text.isBlank() && session.attachments().isEmpty() && session.fileReferences().isEmpty()) return;
        if (session.threadId() == null || session.threadId().isBlank()
            || session.providerTurnId() == null || session.providerTurnId().isBlank()) {
            enqueueInput(text);
            presenter.toast(session, UiMessages.message("当前回合尚未准备好接收引导，消息已加入队列"));
            return;
        }

        // 引导消息立即归入当前回合，并保留乐观消息用于抵消 app-server 回推。
        var input = prepareInput(session, text);
        addEntry(session, new ConversationEntry(
            ConversationEntry.Kind.USER,
            UiMessages.message("你"),
            input.display(),
            null,
            input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
        ));
        session.pendingUserMessageCount(session.pendingUserMessageCount() + 1);
        workspaceInput.clearInputs(session);

        var turnHandle = session.handle();
        codex.steerTurn(
            session.threadId(),
            session.providerTurnId(),
            input.inputText(),
            input.attachments(),
            input.fileReferences()
        ).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!sessionCoordinator.isCurrent(turnHandle)) return;
                if (session.pendingUserMessageCount() > 0) {
                    session.pendingUserMessageCount(session.pendingUserMessageCount() - 1);
                }
                asyncError(session, UiMessages.message("无法引导当前回合"), error);
            });
            return null;
        });
    }

    private boolean applySendReferenceOrder(ConversationSession session, String text, JsonArray ids) {
        if (!workspaceInput.applySendReferenceOrder(session, text, ids)) {
            presenter.toast(session, UiMessages.message("文件引用状态尚未同步，请稍后重试"));
            return false;
        }
        return true;
    }

    private void sendInput(ConversationSession session, String text) {
        if (session.historyReadOnly()) return;
        if (session.attachments().isEmpty() && session.fileReferences().isEmpty()
            && listener.handleNativeCommand(text)) return;
        var input = prepareInput(session, text);
        workspaceInput.clearInputs(session);
        dispatchInput(session, input, true);
    }

    private QueuedInput prepareInput(ConversationSession session, String text) {
        var settings = CodexSettingsState.getInstance().getState();
        var editorContext = settings.sendOpenedFilePath ? listener.currentEditorContext() : null;
        var inputText = editorContext == null ? text : editorContext.appendTo(text);
        var sentAttachments = List.copyOf(session.attachments());
        var sentFileReferences = List.copyOf(session.fileReferences());
        var display = new StringBuilder(embedFileReferencePaths(text, sentFileReferences));
        if (editorContext != null) display.append(UiMessages.message("\n\n[当前编辑器上下文] ")).append(editorContext.displayLabel());
        sentAttachments.forEach(attachment -> display.append("\n").append(switch (attachment.kind()) {
            case IMAGE -> UiMessages.message("[图片] ");
            case FILE -> "@";
        }).append(attachment.kind() == Attachment.Kind.FILE ? absolutePath(attachment.path()) : attachment.name()));
        return new QueuedInput(inputText, display.toString().trim(), sentAttachments, sentFileReferences);
    }

    private void enqueueInput(String text) {
        var session = activeSession();
        if (session.attachments().isEmpty() && session.fileReferences().isEmpty()
            && listener.handleNativeCommand(text)) return;
        var input = prepareInput(session, text);
        session.queuedInputs().add(input);
        addEntry(session, new ConversationEntry(
            ConversationEntry.Kind.USER,
            UiMessages.message("你"),
            input.display(),
            null,
            input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
        ));
        session.pendingUserMessageCount(session.pendingUserMessageCount() + 1);
        workspaceInput.clearInputs(session);
        presenter.queue(session);
    }

    private void dispatchInput(ConversationSession session, QueuedInput input, boolean publishUser) {
        if (publishUser) {
            session.pendingUserBody(input.display());
            addEntry(session, new ConversationEntry(
                ConversationEntry.Kind.USER,
                UiMessages.message("你"),
                input.display(),
                null,
                input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
            ));
            session.pendingUserMessageCount(session.pendingUserMessageCount() + 1);
        }
        var turnHandle = sessionCoordinator.beginTurn(session.id());
        presenter.busy(session);

        try {
            var settings = CodexSettingsState.getInstance().snapshot(session.provider());
            var providerProfile = settings.provider();
            var model = providerProfile.builtIn()
                ? (Objects.equals(session.provider(), "claude") ? settings.claudeModel() : settings.model())
                : providerProfile.model();
            // Claude Code 使用独立 CLI 会话，不能把会话 ID 或输入误发给 Codex app-server。
            if (Objects.equals(session.provider(), "claude")) {
                dispatchClaudeInput(session, turnHandle, input, settings);
                return;
            }

            var request = new TurnRequest(
                turnHandle,
                Objects.requireNonNullElse(session.threadId(), ""),
                input.inputText(),
                input.attachments(),
                input.fileReferences(),
                model,
                settings.reasoningEffort().value(),
                settings.serviceTier().value(),
                settings.approvalPolicy().value(),
                settings.sandboxMode().value(),
                providerSettings.developerInstructions(),
                "",
                providerProfile
            );
            changeService.prepareCommands(session.id().value());
            codexProvider.startTurn(request, event -> {
                // Snapshot on the protocol reader, before an EDT queue delay can lose the shell baseline.
                captureCodexCommand(event);
                ApplicationManager.getApplication().invokeLater(() -> handleCodexTurnEvent(event));
            });
        } catch (RuntimeException error) {
            // 设置读取或 Provider 启动也可能同步失败，此时还没有远端回合 ID。
            sessionCoordinator.complete(turnHandle, current -> {
                approvalCoordinator.clearTurn(turnHandle);
                if (Objects.equals(current.provider(), "claude")) claudeProvider.cancel(turnHandle);
                else codexProvider.cancel(turnHandle);
                current.pendingUserMessageCount(Math.max(0, current.pendingUserMessageCount() - 1));
                current.pendingUserBody(null);
                presenter.busy(current);
                asyncError(current, UiMessages.message("无法发送消息"), error);
            });
        }
    }

    void captureCodexCommand(TurnEvent event) {
        if (!sessionCoordinator.isCurrent(event.handle())) return;
        var sessionId = event.handle().sessionId().value();
        if (event instanceof TurnEvent.Item raw && raw.kind().equals("rawToolCall") && raw.phase() == TurnEvent.Item.Phase.STARTED) {
            var input = Objects.toString(raw.data().get("input"), Objects.toString(raw.data().get("arguments"), ""));
            changeService.commandsStarted(sessionId, raw.id(), com.codexgui.service.ShellCommandInput.parse(
                Objects.toString(raw.data().get("name"), ""), input));
            changeService.patchStarted(sessionId, raw.id(), com.codexgui.service.PatchInputPaths.parse(
                Objects.toString(raw.data().get("name"), ""), input));
        }
        if (event instanceof TurnEvent.Item item && item.kind().equals("commandExecution")) {
            if (item.phase() == TurnEvent.Item.Phase.STARTED) {
                var cwd = Objects.toString(item.data().get("cwd"), "");
                var commands = new ArrayList<com.codexgui.service.ShellCommandInput>();
                if (item.data().get("commandActions") instanceof List<?> actions) {
                    for (var action : actions.stream().limit(16).toList()) if (action instanceof Map<?, ?> data
                        && data.get("command") instanceof String command && !command.isBlank())
                        commands.add(new com.codexgui.service.ShellCommandInput(command, cwd));
                }
                if (commands.isEmpty()) commands.add(new com.codexgui.service.ShellCommandInput(
                    Objects.toString(item.data().get("command"), ""), cwd));
                changeService.commandsStarted(sessionId, item.id(), commands);
            }
        }
        if (event instanceof TurnEvent.Item item && item.kind().equals("fileChange") && item.phase() == TurnEvent.Item.Phase.STARTED) {
            var payload = GSON.toJsonTree(item.data()).getAsJsonObject();
            var movePaths = new StringBuilder();
            for (var element : array(payload, "changes")) {
                if (!element.isJsonObject()) continue;
                var change = element.getAsJsonObject(); var kind = change.get("kind");
                if (kind == null || !kind.isJsonObject()) continue;
                var destination = string(kind.getAsJsonObject(), "move_path", "");
                if (destination.isBlank()) continue;
                for (var path : List.of(string(change, "path", ""), destination))
                    movePaths.append('"').append(path.replace('\\', '/')).append("\" ");
            }
            if (!movePaths.isEmpty()) changeService.commandStarted(sessionId, item.id(), movePaths.toString(), "");
        }
    }

    private void handleCodexTurnEvent(TurnEvent event) {
        var turnHandle = event.handle();
        if (!sessionCoordinator.isCurrent(turnHandle)) return;
        var session = sessionRegistry.find(turnHandle.sessionId());
        if (session == null) return;
        if (event instanceof TurnEvent.Started started) {
            sessionCoordinator.apply(turnHandle, current -> {
                if (!started.conversationId().isBlank()) current.threadId(started.conversationId());
                if (!started.providerTurnId().isBlank()) current.bindProviderTurn(started.providerTurnId());
                presenter.thread(current);
                presenter.busy(current);
            });
            return;
        }
        if (event instanceof TurnEvent.Delta delta) {
            appendProviderDelta(session, turnHandle, delta);
            return;
        }
        if (event instanceof TurnEvent.Item item) {
            handleCodexItem(session, item);
            return;
        }
        if (event instanceof TurnEvent.Change change) {
            var providerTurnKey = turnHandle.turnId().value() + ':' + turnHandle.generation();
            changeService.trackProviderDiff(session.id().value(), providerTurnKey, change.unifiedDiff());
            return;
        }
        if (event instanceof TurnEvent.Usage usage) {
            session.usageUsedTokens(usage.usedTokens());
            session.usageMaxTokens(usage.maxTokens());
            presenter.usage(session);
            return;
        }
        if (event instanceof TurnEvent.Notice notice) {
            addEntry(session, new ConversationEntry(ConversationEntry.Kind.NOTICE, UiMessages.message("正在重连"), notice.message(), null));
            return;
        }
        if (event instanceof TurnEvent.Completed) {
            changeService.finishCommands(session.id().value());
            approvalCoordinator.clearTurn(turnHandle);
            codexProvider.complete(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                current.pendingUserBody(null);
                changeService.refreshSession(current.id().value());
                presenter.busy(current);
                var settings = CodexSettingsState.getInstance().snapshot(CodexSettingsState.CODEX_CHANNEL);
                attentionService.notify(
                    UiMessages.message("Codex 任务已完成"),
                    current.title(),
                    settings.taskCompletionNotificationEnabled(),
                    settings.taskCompletionSoundEnabled()
                );
                titles.completed(current, settings.provider().builtIn() ? settings.model() : settings.provider().model());
                startNextQueuedInput(current);
            });
            loadHistory("");
            return;
        }
        if (event instanceof TurnEvent.Failed failed) {
            changeService.finishCommands(session.id().value());
            approvalCoordinator.clearTurn(turnHandle);
            codexProvider.complete(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                if (current.pendingUserMessageCount() > 0) {
                    current.pendingUserMessageCount(current.pendingUserMessageCount() - 1);
                }
                current.pendingUserBody(null);
                changeService.refreshSession(current.id().value());
                presenter.busy(current);
                if (!failed.cancelled()) asyncError(current, UiMessages.message("无法发送消息"), failed.error());
                startNextQueuedInput(current);
            });
        }
    }

    private void appendProviderDelta(ConversationSession session, TurnHandle turnHandle, TurnEvent.Delta delta) {
        if (!CodexSettingsState.getInstance().snapshot(CodexSettingsState.CODEX_CHANNEL).streamResponses()
            || delta.text().isEmpty()) return;
        var kind = switch (delta.kind()) {
            case TEXT -> ConversationEntry.Kind.ASSISTANT;
            case THINKING -> ConversationEntry.Kind.REASONING;
            case PLAN -> ConversationEntry.Kind.PLAN;
            case COMMAND -> ConversationEntry.Kind.COMMAND;
        };
        var title = switch (delta.kind()) {
            case TEXT -> "Codex";
            case THINKING -> UiMessages.message("思考");
            case PLAN -> UiMessages.message("计划");
            case COMMAND -> UiMessages.message("命令");
        };
        var itemId = delta.itemId().isBlank() ? fallbackId(session, kind) : delta.itemId();
        if (delta.kind() != TurnEvent.Delta.Kind.COMMAND) {
            appendEntry(session, itemId, kind, title, delta.text());
            return;
        }
        // 命令输出高频到达时继续使用短批处理，避免阻塞 IDE 事件线程。
        var commandKey = commandKey(session, itemId);
        var buffer = pendingCommandDeltas.computeIfAbsent(commandKey, ignored -> new StringBuilder());
        synchronized (buffer) { buffer.append(delta.text()); }
        if (scheduledCommandDeltas.add(commandKey)) {
            CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS).execute(
                () -> flushCommandDelta(session, turnHandle, itemId, title));
        }
    }

    private void handleCodexItem(ConversationSession session, TurnEvent.Item item) {
        var source = GSON.toJsonTree(item.data()).getAsJsonObject();
        if (item.kind().equals("rawToolCall")) {
            if (item.phase() == TurnEvent.Item.Phase.COMPLETED) {
                changeService.commandCompleted(session.id().value(), item.id());
                changeService.patchCompleted(session.id().value(), item.id());
                changeService.refreshSession(session.id().value());
            }
            return;
        }
        if ((item.kind().equals("commandExecution") || item.kind().equals("fileChange")) && item.phase() == TurnEvent.Item.Phase.COMPLETED) {
            // Publishing summaries reads IDE Documents, so keep this part on the EDT.
            changeService.commandCompleted(session.id().value(), item.id());
            changeService.refreshSession(session.id().value());
        }
        // Adapter 已完成协议归一化，渲染层只消费普通数据。
        if (item.phase() == TurnEvent.Item.Phase.UPDATED) {
            updateFileChangeDiffs(session.id().value(), source);
        } else if (item.phase() == TurnEvent.Item.Phase.STARTED) {
            renderStartedItem(session, source);
        } else {
            renderCompletedItem(session, source);
        }
    }

    private void dispatchClaudeInput(
        ConversationSession session,
        TurnHandle turnHandle,
        QueuedInput input,
        CodexSettingsState.SettingsSnapshot settings
    ) {
        var sessionId = session.id().value();
        var providerProfile = settings.provider();
        var conversationId = session.threadId();
        var finalItemId = "claude:" + UUID.randomUUID();
        var streamedText = new StringBuilder();
        var toolIds = ConcurrentHashMap.<String>newKeySet();
        var prompt = claudePrompt(input);

        var request = new TurnRequest(
            turnHandle,
            conversationId,
            prompt,
            input.attachments(),
            input.fileReferences(),
            providerProfile.builtIn() ? settings.claudeModel() : providerProfile.model(),
            settings.reasoningEffort().value(),
            settings.serviceTier().value(),
            settings.approvalPolicy().value(),
            settings.sandboxMode().value(),
            providerSettings.developerInstructions(),
            settings.claudeExecutable(),
            providerProfile
        );
        claudeProvider.startTurn(request, event -> handleClaudeTurnEvent(
            sessionId, turnHandle, finalItemId, streamedText, toolIds, settings, event));
    }

    private void handleClaudeTurnEvent(
        String sessionId,
        TurnHandle turnHandle,
        String finalItemId,
        StringBuilder streamedText,
        Set<String> toolIds,
        CodexSettingsState.SettingsSnapshot settings,
        TurnEvent event
    ) {
        if (!turnHandle.equals(event.handle())) return;
        if (event instanceof TurnEvent.ModelSelected selected) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!sessionCoordinator.isCurrent(turnHandle)) return;
                CodexSettingsState.getInstance().setClaudeModel(selected.model());
                if (Objects.equals(activeSessionId(), sessionId)) listener.settingsChanged();
            });
            return;
        }
        if (event instanceof TurnEvent.Delta delta) {
            if (delta.text().isEmpty()) return;
            if (delta.kind() == TurnEvent.Delta.Kind.TEXT) {
                synchronized (streamedText) { streamedText.append(delta.text()); }
                if (settings.streamResponses()) ApplicationManager.getApplication().invokeLater(() ->
                    sessionCoordinator.apply(turnHandle, current -> appendEntry(
                        current, delta.itemId(), ConversationEntry.Kind.ASSISTANT, "Claude", delta.text())));
            } else if (delta.kind() == TurnEvent.Delta.Kind.THINKING && settings.streamResponses()) {
                ApplicationManager.getApplication().invokeLater(() -> sessionCoordinator.apply(
                    turnHandle, current -> appendEntry(
                        current, delta.itemId(), ConversationEntry.Kind.REASONING, UiMessages.message("思考"), delta.text())));
            }
            return;
        }
        if (event instanceof TurnEvent.Tool tool) {
            toolIds.add(tool.id());
            ApplicationManager.getApplication().invokeLater(() -> sessionCoordinator.apply(
                turnHandle, current -> replaceEntry(
                    current,
                    tool.id(),
                    ConversationEntry.Kind.COMMAND,
                    UiMessages.message("Claude 工具"),
                    tool.name() + "\n\n" + GSON.toJson(tool.input())
                )));
            return;
        }
        if (event instanceof TurnEvent.Completed completed) {
            finishClaudeTurn(turnHandle, finalItemId, streamedText, toolIds, settings, completed, null);
            return;
        }
        if (event instanceof TurnEvent.Failed failed) {
            finishClaudeTurn(turnHandle, finalItemId, streamedText, toolIds, settings, null, failed);
        }
    }

    private void finishClaudeTurn(
        TurnHandle turnHandle,
        String itemId,
        StringBuilder streamedText,
        Set<String> toolIds,
        CodexSettingsState.SettingsSnapshot settings,
        TurnEvent.Completed completed,
        TurnEvent.Failed failed
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            approvalCoordinator.clearTurn(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                // 正常完成时发布最终回复；失败时保留已收到的流式内容并明确提示。
                if (completed != null) {
                    current.threadId(completed.conversationId());
                    String received;
                    synchronized (streamedText) { received = streamedText.toString(); }
                    if (!settings.streamResponses() || received.isBlank()) {
                        replaceEntry(current, itemId, ConversationEntry.Kind.ASSISTANT, "Claude", completed.finalText());
                    }
                    if (!completed.model().isBlank()) CodexSettingsState.getInstance().setClaudeModel(completed.model());
                    toolIds.forEach(id -> appendEntry(
                        current, id, ConversationEntry.Kind.COMMAND, UiMessages.message("Claude 工具"), UiMessages.message("\n\n执行状态：completed")));
                    presenter.thread(current);
                    attentionService.notify(
                        UiMessages.message("Claude 任务已完成"),
                        current.title(),
                        settings.taskCompletionNotificationEnabled(),
                        settings.taskCompletionSoundEnabled()
                    );
                } else if (failed != null && !failed.cancelled()) {
                    asyncError(current, UiMessages.message("无法发送 Claude Code 消息"), failed.error());
                }
                changeService.finishCommands(current.id().value());
                changeService.refreshSession(current.id().value());
                if (current.pendingUserMessageCount() > 0) {
                    current.pendingUserMessageCount(current.pendingUserMessageCount() - 1);
                }
                current.pendingUserBody(null);
                presenter.busy(current);
                startNextQueuedInput(current);
            });
        });
    }

    private String claudePrompt(QueuedInput input) {
        var result = new StringBuilder(input.inputText());
        // Claude Code 通过工作区工具读取附件，因此显式提供绝对路径并保留原始用户文本。
        for (var reference : input.fileReferences()) {
            result.append(UiMessages.message("\n\n[引用文件] ")).append(absolutePath(reference.path()));
        }
        for (var attachment : input.attachments()) {
            result.append("\n\n[")
                .append(attachment.kind() == Attachment.Kind.IMAGE ? UiMessages.message("图片") : UiMessages.message("附件"))
                .append("] ")
                .append(absolutePath(attachment.path()));
        }
        return result.toString();
    }

    private void startNextQueuedInput(ConversationSession session) {
        if (session.busy() || session.queuedInputs().isEmpty()) return;
        var next = session.queuedInputs().removeFirst();
        presenter.queue(session);
        dispatchInput(session, next, false);
    }

    private String embedFileReferencePaths(String text, List<FileReference> references) {
        var parts = text.split("\uFFFC", -1);
        var embeddedCount = Math.min(references.size(), Math.max(0, parts.length - 1));
        var result = new StringBuilder();
        for (var index = 0; index < parts.length; index++) {
            result.append(parts[index]);
            if (index < embeddedCount) result.append('@').append(absolutePath(references.get(index).path()));
        }
        for (var index = embeddedCount; index < references.size(); index++) {
            if (!result.isEmpty()) result.append('\n');
            result.append('@').append(absolutePath(references.get(index).path()));
        }
        return result.toString();
    }

    private String absolutePath(java.nio.file.Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    void interruptCurrentTurn() {
        var session = activeSession();
        if (!session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            // 先让待启动和运行中的回合同时失效，迟到回调会被 Coordinator 丢弃。
            var turnHandle = session.handle();
            approvalCoordinator.clearTurn(turnHandle);
            sessionCoordinator.cancel(session.id());
            session.pendingUserBody(null);
            session.pendingUserMessageCount(session.queuedInputs().size());
            claudeProvider.cancel(turnHandle);
            presenter.busy(session);
            startNextQueuedInput(session);
            return;
        }
        var turnHandle = session.handle();
        approvalCoordinator.clearTurn(turnHandle);
        sessionCoordinator.cancel(session.id());
        session.pendingUserBody(null);
        session.pendingUserMessageCount(session.queuedInputs().size());
        presenter.busy(session);
        codexProvider.cancel(turnHandle);
        changeService.finishCommands(session.id().value());
        changeService.refreshSession(session.id().value());
        startNextQueuedInput(session);
    }

    void answerQuestions(BridgeCommand command, boolean cancelled) {
        var request = command.payload();
        approvalCoordinator.answer(
            command.requestId(),
            command.sessionId(),
            command.turnId(),
            command.generation(),
            cancelled || !request.has("answers") || !request.get("answers").isJsonObject()
                ? null
                : request.getAsJsonObject("answers"),
            cancelled,
            command.legacy()
        );
    }

    void addNotice(String title, String body) {
        addEntry(activeSession(), new ConversationEntry(ConversationEntry.Kind.NOTICE, title, body, null));
    }

    void reportError(String title, Throwable error) {
        asyncError(activeSession(), title, error);
    }

    void reportError(ConversationSession session, String title, Throwable error) {
        asyncError(session, title, error);
    }

    void busyChanged(ConversationSession session) {
        presenter.busy(session);
    }

    private void replaceEntry(
        ConversationSession session,
        String itemId,
        ConversationEntry.Kind kind,
        String title,
        String body
    ) {
        for (int index = 0; index < session.transcript().size(); index++) {
            var current = session.transcript().get(index);
            if (!Objects.equals(itemId, current.itemId())) continue;
            var replacement = new ConversationEntry(
                kind, title, body, itemId, current.fileReferencePaths(), current.createdAtEpochMs());
            session.transcript().set(index, replacement);
            presenter.replaceMessage(session, replacement);
            return;
        }
        var replacement = new ConversationEntry(kind, title, body, itemId);
        session.transcript().add(replacement);
        presenter.message(session, replacement);
    }

    private void appendEntry(
        ConversationSession session,
        String itemId,
        ConversationEntry.Kind kind,
        String title,
        String delta
    ) {
        for (var index = 0; index < session.transcript().size(); index++) {
            var current = session.transcript().get(index);
            if (!Objects.equals(itemId, current.itemId())) continue;
            session.transcript().set(index, new ConversationEntry(
                kind,
                title,
                current.body() + delta,
                itemId,
                current.fileReferencePaths(),
                current.createdAtEpochMs()
            ));
            presenter.appendMessage(session, itemId, kind, title, delta);
            return;
        }
        var entry = new ConversationEntry(kind, title, delta, itemId);
        session.transcript().add(entry);
        presenter.message(session, entry);
    }

    private void flushCommandDelta(
        ConversationSession session,
        TurnHandle turnHandle,
        String itemId,
        String title
    ) {
        var commandKey = commandKey(session, itemId);
        scheduledCommandDeltas.remove(commandKey);
        var buffer = pendingCommandDeltas.remove(commandKey);
        if (buffer == null) return;
        String delta;
        synchronized (buffer) { delta = buffer.toString(); }
        if (!delta.isEmpty() && !completedCommandItems.contains(commandKey)) {
            ApplicationManager.getApplication().invokeLater(() ->
                sessionCoordinator.apply(turnHandle, current -> {
                    if (!completedCommandItems.contains(commandKey)) {
                        appendEntry(current, itemId, ConversationEntry.Kind.COMMAND, title, delta);
                    }
                }));
        }
    }

    private String commandKey(ConversationSession session, String itemId) {
        return session.id().value() + "\u0000" + itemId;
    }

    private String fallbackId(ConversationSession session, ConversationEntry.Kind kind) {
        return (session.providerTurnId() == null ? "turn" : session.providerTurnId()) + ":" + kind;
    }

    private void renderStartedItem(ConversationSession session, JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(session, ConversationEntry.Kind.NOTICE));
        var type = string(item, "type", "");
        if (!CodexSettingsState.getInstance().getState().streamResponses
            && !Objects.equals(type, "fileChange")) return;
        switch (type) {
            case "agentMessage" -> replaceEntry(session, id, ConversationEntry.Kind.ASSISTANT, "Codex", "");
            case "plan" -> replaceEntry(session, id, ConversationEntry.Kind.PLAN, UiMessages.message("计划"), "");
            case "commandExecution" -> {
                completedCommandItems.remove(commandKey(session, id));
                replaceEntry(
                    session,
                    id,
                    ConversationEntry.Kind.COMMAND,
                    UiMessages.message("命令"),
                    "$ " + string(item, "command", "") + "\n\n"
                );
            }
            case "mcpToolCall" -> replaceEntry(
                session,
                id,
                ConversationEntry.Kind.MCP,
                UiMessages.message("MCP 工具"),
                string(item, "server", "") + " / " + string(item, "tool", "")
            );
            case "fileChange" -> updateFileChangeDiffs(session.id().value(), item);
            default -> {
            }
        }
    }

    private void renderCompletedItem(ConversationSession session, JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(session, ConversationEntry.Kind.NOTICE));
        switch (string(item, "type", "")) {
            case "userMessage" -> {
                if (session.pendingUserMessageCount() > 0) {
                    session.pendingUserMessageCount(session.pendingUserMessageCount() - 1);
                    session.pendingUserBody(null);
                } else {
                    var content = array(item, "content");
                    addEntry(session, new ConversationEntry(
                        ConversationEntry.Kind.USER,
                        UiMessages.message("你"),
                        userMessageText(content),
                        id,
                        userMessageReferencePaths(content)
                    ));
                }
            }
            case "agentMessage" -> replaceEntry(
                session, id, ConversationEntry.Kind.ASSISTANT, "Codex", string(item, "text", ""));
            case "plan" -> replaceEntry(
                session, id, ConversationEntry.Kind.PLAN, UiMessages.message("计划"), string(item, "text", ""));
            case "commandExecution" -> {
                var commandKey = commandKey(session, id);
                completedCommandItems.add(commandKey);
                scheduledCommandDeltas.remove(commandKey);
                pendingCommandDeltas.remove(commandKey);
                var body = new StringBuilder("$ ").append(string(item, "command", ""));
                var output = string(item, "aggregatedOutput", "");
                if (!output.isBlank()) body.append("\n\n").append(output);
                var hasExitCode = item.has("exitCode") && !item.get("exitCode").isJsonNull();
                var exitCode = hasExitCode ? item.get("exitCode").getAsInt() : 0;
                if (hasExitCode) body.append(UiMessages.message("\n\n退出码：")).append(exitCode);
                var status = string(item, "status", "");
                if (status.isBlank()) status = !hasExitCode || exitCode == 0 ? "completed" : "failed";
                body.append(UiMessages.message("\n\n执行状态：")).append(status);
                replaceEntry(session, id, ConversationEntry.Kind.COMMAND, UiMessages.message("命令"), body.toString());
            }
            case "mcpToolCall" -> replaceEntry(
                session,
                id,
                ConversationEntry.Kind.MCP,
                UiMessages.message("MCP 工具"),
                string(item, "server", "") + " / " + string(item, "tool", "")
                    + UiMessages.message("\n状态：") + string(item, "status", "")
            );
            case "fileChange" -> updateFileChangeDiffs(session.id().value(), item);
            case "contextCompaction" -> addEntry(session, new ConversationEntry(
                ConversationEntry.Kind.NOTICE,
                UiMessages.message("上下文整理"),
                UiMessages.message("Codex 已压缩当前会话上下文。"),
                id
            ));
            default -> {
            }
        }
    }

    private void updateFileChangeDiffs(String sessionId, JsonObject object) {
        // Only completed edits own a baseline; started/failed additions can target an existing user file.
        var status = string(object, "status", "");
        if (!status.isBlank() && !status.equals("completed")) return;
        // 结构化补丁事件只包含本次文件修改，逐项转交给修改捕获服务。
        for (var element : array(object, "changes")) {
            if (!element.isJsonObject()) continue;
            var change = element.getAsJsonObject();
            var kind = change.get("kind");
            var kindName = kind != null && kind.isJsonObject()
                ? string(kind.getAsJsonObject(), "type", "update") : string(change, "kind", "update");
            changeService.trackProviderFile(
                sessionId,
                string(change, "path", ""),
                kindName,
                string(change, "diff", ""),
                kind != null && kind.isJsonObject() ? string(kind.getAsJsonObject(), "move_path", "") : ""
            );
        }
    }

    String userMessageText(JsonArray content) {
        var result = new StringBuilder();
        for (JsonElement element : content) {
            var input = element.getAsJsonObject();
            var referencePath = FileReferenceText.parse(string(input, "text", ""));
            var part = switch (string(input, "type", "")) {
                case "text" -> referencePath == null ? string(input, "text", "") : "@" + mentionPath(referencePath);
                case "mention" -> "@" + mentionPath(string(input, "path", string(input, "name", UiMessages.message("文件"))));
                case "localImage", "image" -> UiMessages.message("[图片] ") + string(input, "path", "");
                case "skill" -> "[Skill] " + string(input, "name", "");
                default -> "";
            };
            if (part.isBlank()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(part);
        }
        return result.toString();
    }

    List<String> userMessageReferencePaths(JsonArray content) {
        var result = new ArrayList<String>();
        for (JsonElement element : content) {
            var input = element.getAsJsonObject();
            var rawPath = switch (string(input, "type", "")) {
                case "mention" -> string(input, "path", string(input, "name", ""));
                case "text" -> FileReferenceText.parse(string(input, "text", ""));
                default -> null;
            };
            if (rawPath == null) continue;
            var path = workspaceInput.resolvePath(rawPath);
            if (path != null) result.add(absolutePath(path));
        }
        return List.copyOf(result);
    }

    private String mentionPath(String rawPath) {
        var path = workspaceInput.resolvePath(rawPath);
        return path == null ? rawPath : absolutePath(path);
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (var session : sessionRegistry.sessions()) {
                if (!Objects.equals(session.provider(), CodexSettingsState.CODEX_CHANNEL)) continue;
                presenter.connection(session, connected, detail);
                // 断开时才作废旧回合。start() 完成后，首条消息可能先于迟到的已连接通知启动。
                if (!connected && session.busy()) {
                    approvalCoordinator.clearTurn(session.handle());
                    changeService.finishCommands(session.id().value());
                    changeService.refreshSession(session.id().value());
                    sessionCoordinator.cancel(session.id());
                    presenter.busy(session);
                }
                if (connected) startNextQueuedInput(session);
            }
            providerSettings.publishProviderStatus();
            if (!connected) return;
            // 连接建立后补发启动阶段被跳过的基础数据请求。
            providerSettings.loadModels();
            listener.connectionRestored();
            loadHistory("");
        });
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> handleNotification(method, params));
    }

    private void handleNotification(String method, JsonObject params) {
        var notificationThreadId = string(params, "threadId", "");
        if (notificationThreadId.isBlank() && params != null) {
            var turn = params.has("turn") && params.get("turn").isJsonObject()
                ? params.getAsJsonObject("turn")
                : null;
            notificationThreadId = string(turn, "threadId", "");
            if (notificationThreadId.isBlank() && params.has("item") && params.get("item").isJsonObject()) {
                notificationThreadId = string(params.getAsJsonObject("item"), "threadId", "");
            }
        }
        var session = sessionForThread(notificationThreadId);
        // 缺少线程标识的通知只能归入当前会话；无法匹配线程的事件不得误投到当前页签。
        if (session == null && notificationThreadId.isBlank()) session = activeSession();
        if (session == null) return;
        if (Objects.equals(method, "thread/name/updated")) {
            if (notificationThreadId.isBlank() || !Objects.equals(session.provider(), CodexSettingsState.CODEX_CHANNEL)) return;
            var title = string(params, "threadName", "").trim();
            if (!title.isBlank() && !Objects.equals(session.title(), title)) {
                titles.named(session);
                session.title(title);
                presenter.threadTitle(session);
            }
            return;
        }
        var turnHandle = session.handle();
        var turnEvent = method.startsWith("turn/") || method.startsWith("item/");
        if (turnEvent && turnHandle == null) return;
        var notificationTurnId = notificationTurnId(params);
        if (turnEvent && session.providerTurnId() != null && !notificationTurnId.isBlank()
            && !Objects.equals(session.providerTurnId(), notificationTurnId)) return;
        // 回合事件由 CodexConversationProvider 归一化后进入统一 TurnEvent 通道。
        if (turnEvent || Objects.equals(method, "thread/tokenUsage/updated")
            || Objects.equals(method, "turn/diff/updated")) return;
        if (Objects.equals(method, "error")
            && (!notificationThreadId.isBlank() || !notificationTurnId.isBlank())) return;
        if (listener.handleFeatureNotification(method, params)) return;
        switch (method) {
            case "error" -> {
                var error = CodexError.from(params);
                addEntry(session, new ConversationEntry(
                    error.willRetry() ? ConversationEntry.Kind.NOTICE : ConversationEntry.Kind.ERROR,
                    error.willRetry() ? UiMessages.message("正在重连") : UiMessages.message("Codex 错误"), error.message(), null));
            }
            case "warning", "configWarning", "deprecationNotice" -> addEntry(session, new ConversationEntry(
                ConversationEntry.Kind.NOTICE,
                UiMessages.message("提示"),
                string(params, "message", params.toString()),
                null
            ));
            default -> {
            }
        }
    }

    private String notificationTurnId(JsonObject params) {
        var direct = string(params, "turnId", "");
        if (!direct.isBlank()) return direct;
        if (params != null && params.has("turn") && params.get("turn").isJsonObject()) {
            return string(params.getAsJsonObject("turn"), "id", "");
        }
        if (params != null && params.has("item") && params.get("item").isJsonObject()) {
            return string(params.getAsJsonObject("item"), "turnId", "");
        }
        return "";
    }

    @Override
    public void onServerRequest(long requestId, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var threadId = string(params, "threadId", "");
            var session = sessionForThread(threadId);
            if (session == null && threadId.isBlank()) session = activeSession();
            if (session == null) {
                approvalCoordinator.decline(requestId);
                return;
            }
            var turnHandle = session.handle();
            var settings = CodexSettingsState.getInstance().getState();
            var targetSession = session;
            approvalCoordinator.handle(
                requestId,
                method,
                params,
                turnHandle,
                settings.approvalPolicy,
                settings.permissionDialogTimeoutSeconds,
                questions -> publishUserQuestions(targetSession, requestId, questions)
            );
        });
    }

    private void publishUserQuestions(ConversationSession session, long requestId, JsonArray questions) {
        presenter.questions(session, requestId, questions);
        var settings = CodexSettingsState.getInstance().getState();
        attentionService.notify(
            UiMessages.message("Codex 有一些问题想问你"),
            session.title(),
            settings.askUserQuestionNotificationEnabled,
            settings.askUserQuestionSoundEnabled
        );
    }

    @Override
    public void onProtocolError(String message, Throwable error) {
        var session = activeSession();
        session.busy(false);
        presenter.busy(session);
        addEntry(session, new ConversationEntry(ConversationEntry.Kind.ERROR, UiMessages.message("连接错误"), message, null));
    }

    private void asyncError(ConversationSession session, String title, Throwable throwable) {
        var error = unwrap(throwable);
        addEntry(session, new ConversationEntry(
            ConversationEntry.Kind.ERROR,
            title,
            Objects.toString(error.getMessage(), error.toString()),
            null
        ));
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
    }

    private void addEntry(ConversationSession session, ConversationEntry entry) {
        session.transcript().add(entry);
        presenter.message(session, entry);
    }

    private static JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(field);
    }

    private static String string(JsonObject object, String field, String fallback) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return fallback;
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public void dispose() {
        titles.clear();
        titleGenerator.close();
        codex.removeListener(this);
        codexProvider.close();
        pendingCommandDeltas.clear();
        scheduledCommandDeltas.clear();
        completedCommandItems.clear();
        confirmedSessionIds.clear();
    }
}
