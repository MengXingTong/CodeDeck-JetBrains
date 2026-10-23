package com.codexgui.ui;

import com.codexgui.i18n.UiMessages;

import com.codexgui.bridge.BridgeCommand;
import com.codexgui.bridge.BridgeProtocolCodec;
import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.provider.ClaudeConversationProvider;
import com.codexgui.provider.CodexConversationProvider;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.AttentionService;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.service.ProjectFileSearch;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx;
import com.intellij.openapi.editor.ex.FocusChangeListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

final class CodexToolWindowController implements Disposable {
    private static final Logger LOG = Logger.getInstance(CodexToolWindowController.class);
    private final Project project;
    private final CodexAppServerService codex;
    private final ClaudeCodeService claude;
    private final ClaudeConversationProvider claudeProvider;
    private final CodexConversationProvider codexProvider;
    private final ConversationChangeTracker changeService;
    private final BridgeProtocolCodec bridgeCodec = new BridgeProtocolCodec();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final SessionCoordinator sessionCoordinator = new SessionCoordinator(sessionRegistry);
    private final ApprovalCoordinator approvalCoordinator;
    private final AttentionService attentionService;
    private final ChangeReviewCoordinator changeReview;
    private final WorkspaceInputCoordinator workspaceInput;
    private final ProviderSettingsCoordinator providerSettings;
    private final ConversationCoordinator conversations;
    private final CodexFeatureCoordinator codexFeatures;
    private final BridgeEventPresenter presenter;
    private final EditorContextCapture editorContext;
    private final Consumer<ConversationChangeTracker.ChangeUpdate> changeListener;

    CodexToolWindowController(Project project, JPanel owner, JBCefBrowser browser) {
        this.project = project;
        this.codex = CodexAppServerService.getInstance(project);
        this.changeService = ConversationChangeTracker.getInstance(project);
        this.editorContext = new EditorContextCapture(project, changeService.getRoot());
        this.presenter = new BridgeEventPresenter(browser, bridgeCodec, sessionRegistry, changeService.getRoot());
        this.claude = ClaudeCodeService.getInstance(project);
        this.claudeProvider = new ClaudeConversationProvider(claude);
        this.codexProvider = new CodexConversationProvider(codex);
        this.approvalCoordinator = new ApprovalCoordinator(project, codex, sessionCoordinator::isCurrent);
        this.attentionService = new AttentionService(project);
        this.changeReview = new ChangeReviewCoordinator(project, changeService, this::toast);
        this.workspaceInput = new WorkspaceInputCoordinator(
            project,
            owner,
            browser,
            changeService.getRoot(),
            this::activeSession,
            new WorkspaceInputCoordinator.Listener() {
                @Override
                public void attachmentsChanged(ConversationSession session) {
                    publishAttachments(session);
                }

                @Override
                public void fileReferencesChanged(ConversationSession session) {
                    publishFileReferences(session);
                }

                @Override
                public void nativeDragChanged(boolean active) {
                    publishNativeDragState(active);
                }

                @Override
                public void nativeDrop(double x, double y) {
                    publishNativeDropPosition(x, y);
                }

                @Override
                public void projectFilesLoaded(long requestId, List<ProjectFileSearch.Candidate> files) {
                    publishProjectFiles(requestId, files);
                }
            }
        );
        this.providerSettings = new ProviderSettingsCoordinator(
            project,
            owner,
            changeService.getRoot(),
            codex,
            claude,
            attentionService,
            sessionRegistry,
            new ProviderSettingsCoordinator.Listener() {
                @Override
                public void settingsChanged() {
                    publishSettings();
                }

                @Override
                public void modelOptionsChanged() {
                    publishModelOptions();
                }

                @Override
                public void providerStatusChanged(JsonArray providers) {
                    publishProviderStatus(providers);
                }

                @Override
                public void providerSaveResult(boolean success, String message) {
                    publishProviderSaveResult(success, message);
                }

                @Override
                public void startNewConversation(String title, String provider) {
                    newConversation(title, true, provider);
                }

                @Override
                public void toast(String message) {
                    CodexToolWindowController.this.toast(message);
                }

                @Override
                public void asyncError(String title, Throwable error) {
                    CodexToolWindowController.this.asyncError(title, error);
                }
            }
        );
        this.conversations = new ConversationCoordinator(
            project,
            owner,
            codex,
            claude,
            claudeProvider,
            codexProvider,
            changeService,
            approvalCoordinator,
            sessionRegistry,
            sessionCoordinator,
            changeReview,
            providerSettings,
            workspaceInput,
            attentionService,
            presenter,
            new ConversationCoordinator.Listener() {
                @Override
                public void settingsChanged() {
                    publishSettings();
                }

                @Override
                public EditorFileContext currentEditorContext() {
                    return CodexToolWindowController.this.currentEditorContext();
                }

                @Override
                public boolean handleNativeCommand(String text) {
                    return CodexToolWindowController.this.handleNativeCommand(text);
                }

                @Override
                public boolean handleFeatureNotification(String method, JsonObject params) {
                    return handleCodexFeatureNotification(method, params);
                }

                @Override
                public void connectionRestored() {
                    reloadCodexFeatures();
                }
            }
        );
        this.codexFeatures = new CodexFeatureCoordinator(
            project,
            owner,
            changeService.getRoot(),
            codex,
            sessionRegistry,
            sessionCoordinator,
            approvalCoordinator,
            this::activeSession,
            new CodexFeatureCoordinator.Listener() {
                @Override
                public void skillsChanged(JsonArray items, JsonArray errors) {
                    presenter.skills(items, errors);
                }

                @Override
                public void skillEnabled(String path, boolean enabled) {
                    presenter.skillEnabled(path, enabled);
                }

                @Override
                public void mcpServersChanged(ConversationSession session, JsonArray items) {
                    presenter.mcpServers(session, items);
                }

                @Override
                public void mcpLog(String level, String serverName, String message) {
                    CodexToolWindowController.this.mcpLog(level, serverName, message);
                }

                @Override
                public void busyChanged(ConversationSession session) {
                    conversations.busyChanged(session);
                }

                @Override
                public void threadRolledBack(ConversationSession session, JsonObject result) {
                    renderThread(session, result);
                }

                @Override
                public void sendReviewPrompt() {
                    sendInput(UiMessages.message("请审查当前工作区中尚未提交的修改，并优先报告具体问题和风险。"));
                }

                @Override
                public void toast(String message) {
                    CodexToolWindowController.this.toast(message);
                }

                @Override
                public void toast(ConversationSession session, String message) {
                    CodexToolWindowController.this.toast(session, message);
                }

                @Override
                public void asyncError(String title, Throwable error) {
                    CodexToolWindowController.this.asyncError(title, error);
                }

                @Override
                public void asyncError(ConversationSession session, String title, Throwable error) {
                    CodexToolWindowController.this.asyncError(session, title, error);
                }
            }
        );
        var initialSession = sessionRegistry.active();
        var settingsService = CodexSettingsState.getInstance();
        initialSession.provider(ProviderSettingsCoordinator.providerChannel(settingsService.getState().activeProvider));
        var initialProvider = settingsService.activeProvider(initialSession.provider());
        initialSession.providerProfileId(initialProvider.id);
        initialSession.providerRevision(initialProvider.revision);
        this.changeListener = update -> publishChanges(update.sessionId(), update.changes());

        workspaceInput.install(this);

        changeService.addListener(changeListener);
        project.getMessageBus().connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override
                public void selectionChanged(FileEditorManagerEvent event) {
                    publishFileContext();
                }

                @Override
                public void fileClosed(FileEditorManager source, VirtualFile file) {
                    changeReview.fileClosed(file);
                    publishFileContext();
                }
            }
        );
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(SelectionEvent event) {
                if (event.getEditor().getProject() == project) {
                    editorContext.remember(event.getEditor()); publishFileContext();
                }
            }
        }, this);
        if (EditorFactory.getInstance().getEventMulticaster() instanceof EditorEventMulticasterEx multicaster) {
            multicaster.addFocusChangeListener(new FocusChangeListener() {
                @Override public void focusGained(com.intellij.openapi.editor.Editor editor) {
                    if (editor.getProject() == project) { editorContext.remember(editor); publishFileContext(); }
                }
            }, this);
        }
        codex.start().thenRun(() -> {
            providerSettings.loadModels();
            conversations.loadHistory("");
        }).exceptionally(error -> {
            asyncError(UiMessages.message("连接 Codex CLI 失败"), error);
            return null;
        });
    }

    void handleBridgeMessage(String payload) {
        var decoded = bridgeCodec.decodeCommand(payload);
        if (decoded instanceof BridgeProtocolCodec.Rejected rejected) {
            presenter.protocolEvent(rejected.error(), true);
            return;
        }
        var command = ((BridgeProtocolCodec.Decoded) decoded).command();
        var request = command.payload();
        ApplicationManager.getApplication().invokeLater(() -> {
            // 关闭会话只确认目标事务，不切换活动会话，避免批量关闭后活动指针落在已关闭页签。
            if (command.type() != BridgeCommand.Type.CLOSE_SESSION) conversations.activateSession(command.sessionId().value());
            if (activeSession().historyReadOnly() && switch (command.type()) {
                case SEND, STEER, QUEUED_INPUT, STOP, RENAME, COMPACT, REVIEW, REWIND -> true;
                default -> false;
            }) {
                toast(UiMessages.message("当前查看历史快照，请先继续原会话或复制为新会话"));
                return;
            }
            switch (command.type()) {
                case READY -> bootstrap();
                case RECONNECT -> codex.restart().thenRun(providerSettings::loadModels).exceptionally(error -> {
                    asyncError(UiMessages.message("重新连接 Codex CLI 失败"), error);
                    return null;
                });
                case SEND -> conversations.sendInput(
                    string(request, "text", ""),
                    request.has("referenceIds") ? array(request, "referenceIds") : null
                );
                case STEER -> conversations.steerInput(
                    string(request, "text", ""),
                    request.has("referenceIds") ? array(request, "referenceIds") : null
                );
                case QUEUED_INPUT -> conversations.queuedInputAction(
                    string(request, "id", ""), string(request, "action", ""), string(request, "text", "")
                );
                case STOP -> conversations.interruptCurrentTurn();
                case NEW -> newConversation(
                    string(request, "title", ""),
                    bool(request, "skipConfirmation", false),
                    string(request, "provider", activeSession().provider())
                );
                case CLOSE_SESSION -> conversations.closeSession(command.sessionId().value());
                case ACTIVATE_SESSION -> conversations.publishCurrentSession();
                case HISTORY -> conversations.loadHistory(string(request, "search", ""));
                case OPEN_THREAD -> conversations.openThread(string(request, "id", ""));
                case RESUME_THREAD -> conversations.resumeHistoryThread(string(request, "id", ""));
                case FORK_THREAD -> conversations.forkHistoryThread(string(request, "id", ""));
                case RENAME -> conversations.renameCurrentThread();
                case EXPORT -> conversations.exportConversation();
                case PICK_FILE -> workspaceInput.chooseAttachment(false);
                case PICK_IMAGE -> workspaceInput.chooseAttachment(true);
                case DROP_FILES -> workspaceInput.addPendingDroppedInputs();
                case CANCEL_DROP -> workspaceInput.cancelDrop();
                case COMPOSER_BOUNDS -> workspaceInput.updateComposerDropRegion(
                    doubleValue(request, "left"),
                    doubleValue(request, "top"),
                    doubleValue(request, "right"),
                    doubleValue(request, "bottom")
                );
                case LIST_PROJECT_FILES -> workspaceInput.searchProjectFiles(
                    string(request, "query", ""), requestIdAsLong(command.requestId())
                );
                case REMOVE_ATTACHMENT -> workspaceInput.removeAttachment(integer(request, "index"));
                case REMOVE_FILE_REFERENCE -> workspaceInput.removeFileReference(string(request, "id", ""));
                case REMOVE_FILE_REFERENCES -> workspaceInput.removeFileReferences(request.getAsJsonArray("ids"));
                case ADD_FILE_REFERENCES -> workspaceInput.addFileReferences(request.getAsJsonArray("paths"));
                case REORDER_FILE_REFERENCES -> workspaceInput.reorderFileReferences(request.getAsJsonArray("ids"));
                case ACCEPT_CHANGE -> changeReview.accept(activeSessionId(), integer(request, "index"));
                case REVERT_CHANGE -> changeReview.revert(activeSessionId(), integer(request, "index"));
                case ACCEPT_ALL -> changeReview.acceptAll(activeSessionId());
                case REVERT_ALL -> changeReview.revertAll(activeSessionId());
                case OPEN_CHANGE -> changeReview.open(activeSessionId(), activeSession().provider(), integer(request, "index"));
                case OPEN_CHANGE_LOCATION -> changeReview.openLocation(activeSessionId(), integer(request, "index"));
                case COMPACT -> codexFeatures.compactCurrentThread();
                case REVIEW -> codexFeatures.reviewCurrentChanges();
                case REWIND -> codexFeatures.rollbackLastTurn();
                case MCP -> codexFeatures.showMcpServers();
                case USAGE -> codexFeatures.showUsage();
                case SETTING -> providerSettings.updateSetting(string(request, "key", ""), string(request, "value", ""));
                case ADD_CUSTOM_MODEL -> providerSettings.addCustomModel();
                case REMOVE_CUSTOM_MODEL -> providerSettings.removeCustomModel(string(request, "model", ""));
                case SELECT_PROVIDER -> providerSettings.selectProvider(
                    string(request, "provider", "codex"),
                    string(request, "title", "")
                );
                case ACTIVATE_PROVIDER_PROFILE -> providerSettings.activateProviderProfile(string(request, "id", ""));
                case SAVE_PROVIDER_PROFILE -> providerSettings.saveProviderProfile(request);
                case DELETE_PROVIDER_PROFILE -> providerSettings.deleteProviderProfile(string(request, "id", ""));
                case CHECK_PROVIDERS -> providerSettings.publishProviderStatus();
                case BEHAVIOR_SETTING -> {
                    providerSettings.updateBehaviorSetting(request);
                    if ("sendOpenedFilePath".equals(string(request, "key", ""))) publishFileContext();
                }
                case BROWSE_NOTIFICATION_SOUND -> providerSettings.browseNotificationSound();
                case TEST_NOTIFICATION_SOUND -> providerSettings.playConfiguredSound(true);
                case TOGGLE_STREAMING -> providerSettings.toggleStreaming();
                case TOGGLE_THINKING -> providerSettings.toggleThinking();
                case SAVE_INSTRUCTIONS -> providerSettings.saveInstructions(request);
                case SAVE_PROMPT -> providerSettings.savePrompt(request);
                case DELETE_PROMPT -> providerSettings.deletePrompt(string(request, "id", ""));
                case SELECT_PROMPT -> providerSettings.selectPrompt(string(request, "id", ""));
                case SAVE_AGENT -> providerSettings.saveAgent(request);
                case DELETE_AGENT -> providerSettings.deleteAgent(string(request, "id", ""));
                case SELECT_AGENT -> providerSettings.selectAgent(string(request, "id", ""));
                case LOAD_MCP -> codexFeatures.loadMcpServers(false);
                case RELOAD_MCP -> codexFeatures.loadMcpServers(true);
                case LOAD_SKILLS -> codexFeatures.loadSkills(false);
                case RELOAD_SKILLS -> codexFeatures.loadSkills(true);
                case SET_SKILL_ENABLED -> codexFeatures.setSkillEnabled(request);
                case IMPORT_SKILL -> codexFeatures.importSkill(string(request, "scope", "repo"));
                case OPEN_SKILL -> codexFeatures.openSkill(request);
                case OPEN_MCP_CONFIG -> codexFeatures.openMcpConfig();
                case LOGIN_MCP -> codexFeatures.loginMcpServer(request);
                case SAVE_MCP -> codexFeatures.saveMcpServer(request);
                case DELETE_MCP -> codexFeatures.deleteMcpServer(request);
                case SET_MCP_ENABLED -> codexFeatures.setMcpServerEnabled(request);
                case COPY_TEXT -> copyText(request);
                case ANSWER_QUESTIONS -> conversations.answerQuestions(command, false);
                case CANCEL_QUESTIONS -> conversations.answerQuestions(command, true);
                case CONVERSATION_SEARCH -> searchConversation();
                case OPEN_FILE -> openFileLocation(request);
                case REVEAL_FILE -> revealFileLocation(request);
                case OPEN_URL -> BrowserUtil.browse(string(request, "url", ""));
                case OPEN_SETTINGS -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "CodeDeck");
            }
        });
    }

    private long requestIdAsLong(String requestId) {
        try {
            return Long.parseLong(requestId);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void bootstrap() {
        var session = activeSession();
        presenter.pageReady();
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = providerSettings.sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("uiLocale", UiMessages.languageTag());
        state.addProperty("connected", codex.isConnected());
        state.addProperty("sessionId", session.id().value());
        state.addProperty("busy", session.busy());
        state.addProperty("queuedCount", session.queuedInputs().size());
        state.addProperty("title", session.title());
        if (session.threadId() != null) state.addProperty("threadId", session.threadId());
        state.addProperty("historyReadOnly", session.historyReadOnly());
        state.addProperty("model", providerSettings.activeModel(settings));
        state.add("models", providerSettings.providerModels(settings));
        state.add("customModels", providerSettings.customModels());
        state.addProperty("provider", session.provider());
        state.addProperty("providerProfileId", session.providerProfileId());
        state.addProperty("showThinking", settings.showThinking);
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        presenter.addBehaviorSettings(
            state,
            settings,
            session
        );
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("projectInstructions", instructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", providerSettings.promptsJson(settings.prompts));
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", providerSettings.agentsJson(settings.agents));
        state.add("attachments", presenter.attachmentsJson(session));
        state.add("fileReferences", presenter.fileReferencesJson(session));
        presenter.bootstrap(session, state);
        publishFileContext();
        session.transcript().forEach(entry -> presenter.message(session, entry));
        publishChanges(session.id().value(), changeService.listSummaries(session.id().value()));
        codexFeatures.loadSkills(false);
        providerSettings.publishProviderStatus();
        // 页面可能晚于 CLI 连接完成，准备完成后重新请求模型，避免模型事件丢失。
        providerSettings.loadModels();
    }

    private String activeSessionId() { return sessionRegistry.activeSessionId().value(); }

    private ConversationSession activeSession() { return sessionRegistry.active(); }

    private void sendInput(String text) {
        conversations.sendInput(text);
    }

    private boolean handleNativeCommand(String text) {
        switch (text) {
            case "/new" -> conversations.newConversation();
            case "/compact" -> codexFeatures.compactCurrentThread();
            case "/review" -> codexFeatures.reviewCurrentChanges();
            case "/rewind" -> codexFeatures.rollbackLastTurn();
            case "/mcp" -> codexFeatures.showMcpServers();
            case "/usage" -> codexFeatures.showUsage();
            case "/help" -> conversations.addNotice(
                UiMessages.message("Codex 原生命令"),
                "/new · /compact · /review · /rewind · /mcp · /usage"
            );
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean handleCodexFeatureNotification(String method, JsonObject params) {
        return codexFeatures.handleNotification(method, params);
    }

    private void reloadCodexFeatures() {
        codexFeatures.loadSkills(false);
        codexFeatures.loadMcpServers(false);
    }

    private void publishModelOptions() {
        var settings = CodexSettingsState.getInstance().getState();
        var state = new JsonObject();
        state.add("models", providerSettings.providerModels(settings));
        state.add("customModels", providerSettings.customModels());
        state.addProperty("model", providerSettings.activeModel(settings));
        presenter.partialBootstrap(activeSession(), state);
    }

    private void publishProviderSaveResult(boolean success, String message) {
        presenter.providerSaveResult(success, message);
    }

    private void publishProviderStatus(JsonArray providers) {
        presenter.providers(providers);
    }

    private void renderThread(ConversationSession session, JsonObject result) {
        conversations.renderThread(session, result);
    }

    private void newConversation(String requestedTitle, boolean skipConfirmation, String requestedProvider) {
        conversations.newConversation(requestedTitle, skipConfirmation, requestedProvider);
    }

    private void publishNativeDragState(boolean active) {
        presenter.nativeDrag(active);
    }

    private void publishNativeDropPosition(double x, double y) {
        presenter.nativeDrop(x, y);
    }

    private void publishProjectFiles(long requestId, List<ProjectFileSearch.Candidate> files) {
        presenter.projectFiles(requestId, files);
    }

    private void openFileLocation(JsonObject request) {
        var rawPath = string(request, "path", "").trim();
        if (rawPath.isBlank()) return;
        var line = Math.max(1, integer(request, "line"));
        var column = Math.max(1, integer(request, "column"));
        try {
            var path = resolveReportedFilePath(rawPath);
            // 先刷新本地文件，再按用户可见的行列位置打开编辑器。
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) {
                toast(UiMessages.message("无法打开文件：") + rawPath);
                return;
            }
            new OpenFileDescriptor(project, file, line - 1, column - 1).navigate(true);
        } catch (RuntimeException error) {
            // 路径格式无效时给出提示，避免点击链接导致界面线程异常。
            toast(UiMessages.message("无法打开文件：") + rawPath);
        }
    }

    private void revealFileLocation(JsonObject request) {
        var rawPath = string(request, "path", "").trim();
        if (rawPath.isBlank()) return;
        try {
            var path = resolveReportedFilePath(rawPath);
            // 文件存在时在系统文件管理器中选中。
            if (Files.exists(path)) {
                RevealFileAction.openFile(path);
                return;
            }
            // 文件缺失时退回到仍存在的父目录。
            var parent = path.getParent();
            if (parent != null && Files.exists(parent)) {
                RevealFileAction.openDirectory(parent);
                return;
            }
            toast(UiMessages.message("无法打开文件所在目录：") + rawPath);
        } catch (RuntimeException error) {
            // 路径格式无效时保持与编辑器跳转一致的可见反馈。
            toast(UiMessages.message("无法打开文件所在目录：") + rawPath);
        }
    }

    private Path resolveReportedFilePath(String rawPath) {
        // 相对路径以当前项目为基准，绝对路径直接使用 AI 返回的位置。
        var path = Path.of(normalizeReportedFilePath(rawPath));
        if (!path.isAbsolute() && project.getBasePath() != null) path = Path.of(project.getBasePath()).resolve(path);
        return path.normalize();
    }

    static String normalizeReportedFilePath(String rawPath) {
        var path = rawPath == null ? "" : rawPath.trim();
        // 浏览器 URL 形式可能给 Windows 盘符补一个前导斜杠，例如 /E:/src/Main.cpp。
        if (path.matches("^/[A-Za-z]:[\\\\/].*")) path = path.substring(1);
        var result = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            var current = path.charAt(i);
            if (current == '\\' && i + 1 < path.length() && isMarkdownEscape(path.charAt(i + 1))) {
                result.append(path.charAt(++i));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static boolean isMarkdownEscape(char value) {
        return "`*_{}[]()#+.!|>~:-".indexOf(value) >= 0;
    }

    private void publishAttachments(ConversationSession session) {
        presenter.attachments(session);
    }

    private void publishFileReferences(ConversationSession session) {
        presenter.fileReferences(session);
    }

    private void publishChanges(List<ConversationChangeTracker.ChangeSummary> changes) {
        publishChanges(activeSessionId(), changes);
    }

    private void publishChanges(String sessionId, List<ConversationChangeTracker.ChangeSummary> changes) {
        presenter.changes(sessionId, changes);
    }

    private void searchConversation() {
        var query = Messages.showInputDialog(project, UiMessages.message("输入要在当前会话中查找的文字："), UiMessages.message("搜索会话"), Messages.getQuestionIcon());
        if (query == null || query.isBlank()) return;
        var count = activeSession().transcript().stream()
            .filter(entry -> entry.body().toLowerCase().contains(query.toLowerCase())).count();
        toast(UiMessages.message("找到 ") + count + UiMessages.message(" 条匹配消息"));
    }

    private EditorFileContext currentEditorContext() {
        return editorContext.current();
    }

    private void publishFileContext() {
        var settings = CodexSettingsState.getInstance().getState();
        presenter.fileContext(settings.sendOpenedFilePath ? currentEditorContext() : null);
    }

    private void publishSettings() {
        var session = activeSession();
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = providerSettings.sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("provider", session.provider());
        state.addProperty("model", providerSettings.activeModel(settings));
        state.add("models", providerSettings.providerModels(settings));
        state.add("customModels", providerSettings.customModels());
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        state.addProperty("showThinking", settings.showThinking);
        presenter.addBehaviorSettings(
            state,
            settings,
            session
        );
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("projectInstructions", instructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", providerSettings.promptsJson(settings.prompts));
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", providerSettings.agentsJson(settings.agents));
        presenter.partialBootstrap(session, state);
    }

    private void copyText(JsonObject request) {
        var text = string(request, "text", "");
        if (text.isEmpty() || text.length() > 1_000_000) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
    }

    private void toast(String message) {
        toast(activeSession(), message);
    }

    private void toast(ConversationSession session, String message) {
        presenter.toast(session, message);
    }

    private void mcpLog(String level, String serverName, String message) {
        presenter.mcpLog(level, serverName, message);
    }

    private void asyncError(String title, Throwable throwable) {
        conversations.reportError(title, throwable);
    }

    private void asyncError(ConversationSession session, String title, Throwable throwable) {
        conversations.reportError(session, title, throwable);
    }

    private JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(field);
    }

    private String string(JsonObject object, String field, String fallback) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return fallback;
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int integer(JsonObject object, String field) {
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private boolean bool(JsonObject object, String field, boolean fallback) {
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long longValue(JsonObject object, String field) {
        try {
            return object.get(field).getAsLong();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private double doubleValue(JsonObject object, String field) {
        try {
            return object.get(field).getAsDouble();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private String joinStrings(JsonArray values) {
        var result = new StringBuilder();
        for (var value : values) {
            if (!value.isJsonPrimitive()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(value.getAsString());
        }
        return result.toString();
    }

    @Override
    public void dispose() {
        approvalCoordinator.clear();
        conversations.dispose();
        changeService.removeListener(changeListener);
        attentionService.close();
        changeReview.dispose();
        workspaceInput.dispose();
        providerSettings.dispose();
    }

}
