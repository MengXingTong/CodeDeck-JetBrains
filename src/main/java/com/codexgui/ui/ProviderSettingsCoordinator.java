package com.codexgui.ui;

import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.service.AttentionService;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexModelCatalog;
import com.codexgui.service.NotificationSoundPlayer;
import com.codexgui.service.ProviderModelService;
import com.codexgui.settings.CodexProjectSettingsState;
import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.ProviderCredentialStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.JFileChooser;
import javax.swing.JPanel;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

final class ProviderSettingsCoordinator implements Disposable {
    private static final Logger LOG = Logger.getInstance(ProviderSettingsCoordinator.class);

    interface Listener {
        void settingsChanged();
        void modelOptionsChanged();
        void providerStatusChanged(JsonArray providers);
        void providerSaveResult(boolean success, String message);
        void startNewConversation(String title, String provider);
        void toast(String message);
        void asyncError(String title, Throwable error);
    }

    private final Project project;
    private final JPanel owner;
    private final Path root;
    private final CodexAppServerService codex;
    private final ClaudeCodeService claude;
    private final AttentionService attentionService;
    private final SessionRegistry sessionRegistry;
    private final ProviderModelService providerModelService = new ProviderModelService();
    private final Listener listener;
    private final CompletableFuture<List<String>> localModelCatalog;
    private volatile boolean disposed;

    private final ConcurrentHashMap<String, JsonArray> providerModelOptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activatingProviderByChannel = new ConcurrentHashMap<>();

    ProviderSettingsCoordinator(
        Project project,
        JPanel owner,
        Path root,
        CodexAppServerService codex,
        ClaudeCodeService claude,
        AttentionService attentionService,
        SessionRegistry sessionRegistry,
        Listener listener
    ) {
        this(project, owner, root, codex, claude, attentionService, sessionRegistry, listener,
            CompletableFuture.supplyAsync(CodexModelCatalog::load, AppExecutorUtil.getAppExecutorService()));
    }

    ProviderSettingsCoordinator(
        Project project, JPanel owner, Path root, CodexAppServerService codex, ClaudeCodeService claude,
        AttentionService attentionService, SessionRegistry sessionRegistry, Listener listener,
        CompletableFuture<List<String>> localModelCatalog
    ) {
        this.project = project;
        this.owner = owner;
        this.root = root;
        this.codex = codex;
        this.claude = claude;
        this.attentionService = attentionService;
        this.sessionRegistry = sessionRegistry;
        this.listener = listener;
        this.localModelCatalog = localModelCatalog;
    }

    void loadModels() {
        loadModels(activeSession().provider());
    }

    void loadModels(String channel) {
        var profile = CodexSettingsState.getInstance().activeProvider(channel);
        if (!profile.builtIn) {
            loadProviderModels(profile);
            return;
        }
        // Claude 本地配置由 CLI 在首个回合回填真实模型，不预设静态模型目录。
        if (Objects.equals(channel, CodexSettingsState.CLAUDE_CHANNEL)) return;
        var profileId = profile.id;
        var profileRevision = profile.revision;
        // 与 Diff 共用本机目录；不等待连接，也不把缓存建议写成用户选择。
        localModelCatalog.thenAccept(modelIds -> ApplicationManager.getApplication().invokeLater(() -> {
            var current = CodexSettingsState.getInstance().provider(profileId);
            if (disposed || current == null || current.revision != profileRevision || modelIds.isEmpty()) return;
            var models = new JsonArray();
            modelIds.forEach(models::add);
            // 接口目录优先，迟到的磁盘读取不能覆盖已刷新的列表。
            if (providerModelOptions.putIfAbsent(profileId, models) == null
                && Objects.equals(activeSession().provider(), channel)
                && Objects.equals(CodexSettingsState.getInstance().activeProviderId(channel), profileId)) {
                listener.modelOptionsChanged();
            }
        }));
        // Codex CLI 尚未完成 initialize 时不能发送请求，等待连接事件重新加载。
        if (!codex.isConnected()) return;
        codex.listModels().thenAccept(result -> {
            var modelIds = new ArrayList<String>();
            String defaultModel = null;
            var modelItems = array(result, "data");
            if (modelItems.isEmpty()) modelItems = array(result, "models");
            for (var element : modelItems) {
                var model = element.getAsJsonObject();
                if (model.has("hidden") && model.get("hidden").getAsBoolean()) continue;
                var id = string(model, "model", "");
                if (id.isBlank()) id = string(model, "id", string(model, "slug", ""));
                if (!id.isBlank()) modelIds.add(id);
                if (model.has("isDefault") && model.get("isDefault").getAsBoolean()) defaultModel = id;
            }
            var resolvedDefault = defaultModel;
            ApplicationManager.getApplication().invokeLater(
                () -> applyModelOptions(profileId, profileRevision, modelIds, resolvedDefault));
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> listener.asyncError("无法读取 Codex 模型列表", error));
            return null;
        });
    }

    void selectProvider(String requestedProvider, String requestedTitle) {
        var session = activeSession();
        var selected = providerChannel(requestedProvider);
        var changesChannel = !Objects.equals(selected, session.provider());
        if (changesChannel && session.busy()) {
            listener.toast("任务运行期间不能切换供应商");
            return;
        }
        // 跨渠道切换时复用当前页签，并让目标渠道从空白会话开始。
        if (changesChannel) {
            listener.startNewConversation(requestedTitle, selected);
            listener.toast(Objects.equals(selected, "claude") ? "已切换到 Claude 渠道" : "已切换到 GPT 渠道");
            return;
        }

        // 同渠道选择只刷新当前绑定，不清空已有会话。
        session.provider(selected);
        var settingsService = CodexSettingsState.getInstance();
        settingsService.getState().activeProvider = selected;
        var profile = settingsService.activeProvider(selected);
        session.providerProfileId(profile.id);
        session.providerRevision(profile.revision);
        if (session.threadId() == null && session.transcript().isEmpty() && !requestedTitle.isBlank()) {
            session.title(requestedTitle.trim());
        }
        listener.settingsChanged();
        publishProviderStatus();
        loadModels(selected);
    }

    void publishProviderStatus() {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profiles = List.copyOf(settings.providers);
        CompletableFuture.supplyAsync(
            () -> claude.isAvailable(settings.claudeExecutable),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(claudeAvailable -> {
            var providers = new JsonArray();
            for (var profile : profiles) {
                var localAvailable = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                    ? claudeAvailable
                    : codex.isConnected();
                providers.add(providerJson(profile, localAvailable, settingsService));
            }
            listener.providerStatusChanged(providers);
        });
    }

    void activateProviderProfile(String id) {
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(id);
        if (profile == null) return;
        if (hasBusySession(profile.channel)) {
            listener.toast("任务运行期间不能切换供应商配置");
            return;
        }
        if (!profile.builtIn && (!validProviderUrl(profile.baseUrl) || !ProviderCredentialStore.has(profile.id))) {
            listener.toast("请先补全接口地址和认证凭据");
            return;
        }
        if (Objects.equals(settingsService.activeProviderId(profile.channel), profile.id)) return;

        // 内置配置由本机 CLI 提供模型；自定义配置必须先验证远端模型目录。
        if (profile.builtIn) {
            activatingProviderByChannel.remove(profile.channel);
            completeProviderActivation(profile);
            return;
        }
        var profileChannel = profile.channel;
        var pendingProviderId = activatingProviderByChannel.put(profileChannel, profile.id);
        if (Objects.equals(pendingProviderId, profile.id)) return;

        var profileId = profile.id;
        var profileRevision = profile.revision;
        var apiKey = ProviderCredentialStore.get(profile.id);
        // 凭据在检测开始前被外部清除时终止本次启用，避免留下永久检测状态。
        if (apiKey == null || apiKey.isBlank()) {
            activatingProviderByChannel.remove(profileChannel, profileId);
            publishProviderStatus();
            listener.toast("请先补全接口地址和认证凭据");
            return;
        }
        publishProviderStatus();
        listener.toast("正在读取供应商模型目录...");
        providerModelService.listModels(profile.snapshot(), apiKey).thenAccept(modelIds ->
            ApplicationManager.getApplication().invokeLater(
                () -> completeCustomProviderActivation(profileChannel, profileId, profileRevision, modelIds)
            )
        ).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(
                () -> failProviderActivation(profileChannel, profileId, profileRevision, error)
            );
            return null;
        });
    }

    void saveProviderProfile(JsonObject request) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var requestedId = string(request, "id", "");
        var existing = requestedId.isBlank() ? null : settingsService.provider(requestedId);
        if (existing != null && existing.builtIn) {
            listener.providerSaveResult(false, "内置供应商不能编辑");
            return;
        }
        var channel = providerChannel(string(request, "channel", CodexSettingsState.CODEX_CHANNEL));
        var name = string(request, "name", "").trim();
        var baseUrl = string(request, "baseUrl", "").trim();
        var apiKey = string(request, "apiKey", "").trim();
        if (name.isBlank() || baseUrl.isBlank()) {
            listener.providerSaveResult(false, "供应商名称和接口地址不能为空");
            return;
        }
        if (!validProviderUrl(baseUrl)) {
            listener.providerSaveResult(false, "接口地址必须是有效的 http 或 https 地址");
            return;
        }
        if (existing == null && apiKey.isBlank()) {
            listener.providerSaveResult(false, "新增供应商时必须填写认证凭据");
            return;
        }
        var duplicate = settings.providers.stream().anyMatch(item -> !Objects.equals(item.id, requestedId)
            && Objects.equals(item.channel, channel) && item.name.equalsIgnoreCase(name));
        if (duplicate) {
            listener.providerSaveResult(false, "同一渠道下不能使用重复的供应商名称");
            return;
        }
        var activeEdit = existing != null && Objects.equals(settingsService.activeProviderId(existing.channel), existing.id);
        if (activeEdit && hasBusySession(existing.channel)) {
            listener.providerSaveResult(false, "任务运行期间不能修改正在使用的供应商");
            return;
        }

        // 先写入凭据，失败时不修改供应商资料，前端可保留草稿继续重试。
        var profile = existing == null ? new CodexSettingsState.ProviderProfile() : existing;
        if (existing == null) profile.id = "provider-" + UUID.randomUUID();
        try {
            if (!apiKey.isBlank()) ProviderCredentialStore.set(profile.id, apiKey);
            else if (bool(request, "clearApiKey", false)) ProviderCredentialStore.remove(profile.id);
        } catch (RuntimeException error) {
            LOG.warn("保存供应商认证凭据失败", error);
            listener.providerSaveResult(false, "无法安全保存认证凭据");
            return;
        }

        // 供应商表单只保存连接信息，模型由启用后的模型选择器独立维护。
        profile.channel = channel;
        profile.name = name;
        profile.baseUrl = baseUrl;
        profile.wireApi = Objects.equals(string(request, "wireApi", "responses"), "chat") ? "chat" : "responses";
        if (existing != null) profile.revision++;
        if (existing == null) settings.providers.add(profile);
        providerModelOptions.remove(profile.id);

        if (activeEdit) applyProviderRuntimeChange(profile.channel);
        listener.settingsChanged();
        publishProviderStatus();
        listener.providerSaveResult(true, existing == null ? "供应商已添加" : "供应商配置已保存");
    }

    void deleteProviderProfile(String id) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profile = settingsService.provider(id);
        if (profile == null || profile.builtIn) return;
        var active = Objects.equals(settingsService.activeProviderId(profile.channel), profile.id);
        if (active && hasBusySession(profile.channel)) {
            listener.toast("任务运行期间不能删除正在使用的供应商");
            return;
        }

        settings.providers.remove(profile);
        providerModelOptions.remove(profile.id);
        ProviderCredentialStore.remove(profile.id);
        if (active) {
            var fallbackId = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                ? CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID
                : CodexSettingsState.CODEX_LOCAL_PROVIDER_ID;
            settingsService.setActiveProvider(profile.channel, fallbackId);
            applyProviderRuntimeChange(profile.channel);
        }
        listener.settingsChanged();
        publishProviderStatus();
        listener.toast("供应商已删除");
    }

    void updateSetting(String key, String value) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        // 模型值按供应商分别保存，避免切换后把另一套模型名称带入 CLI。
        switch (key) {
            case "model" -> {
                var provider = activeSession().provider();
                var profile = settingsService.activeProvider(provider);
                if (!profile.builtIn) profile.model = value;
                else if (Objects.equals(provider, "claude")) settings.claudeModel = value;
                else settings.model = value;
            }
            case "effort" -> settings.reasoningEffort = value;
            case "serviceTier" -> settings.serviceTier = Objects.equals(value, "fast") ? "fast" : "standard";
            case "approval" -> settings.approvalPolicy = value;
            case "sandbox" -> settings.sandboxMode = value;
            default -> {
            }
        }
        listener.settingsChanged();
    }

    void updateBehaviorSetting(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        var key = string(request, "key", "");
        switch (key) {
            case "sendShortcut" -> settings.sendShortcut = Objects.equals(string(request, "value", "enter"), "cmdEnter") ? "cmdEnter" : "enter";
            case "permissionDialogTimeoutSeconds" -> settings.permissionDialogTimeoutSeconds = Math.max(30, Math.min(3600, integer(request, "value")));
            case "streamResponses" -> settings.streamResponses = bool(request, "value", true);
            case "showThinking" -> settings.showThinking = bool(request, "value", true);
            case "sendOpenedFilePath" -> settings.sendOpenedFilePath = bool(request, "value", false);
            case "diffExpandedByDefault" -> settings.diffExpandedByDefault = bool(request, "value", false);
            case "preserveFileLineEndings" -> settings.preserveFileLineEndings = bool(request, "value", true);
            case "newSessionConfirmEnabled" -> settings.newSessionConfirmEnabled = bool(request, "value", true);
            case "askUserQuestionNotificationEnabled" -> settings.askUserQuestionNotificationEnabled = bool(request, "value", false);
            case "askUserQuestionSoundEnabled" -> settings.askUserQuestionSoundEnabled = bool(request, "value", false);
            case "taskCompletionNotificationEnabled" -> settings.taskCompletionNotificationEnabled = bool(request, "value", false);
            case "taskCompletionSoundEnabled" -> settings.taskCompletionSoundEnabled = bool(request, "value", false);
            case "systemNotificationOnlyWhenUnfocused" -> settings.systemNotificationOnlyWhenUnfocused = bool(request, "value", false);
            case "soundOnlyWhenUnfocused" -> settings.soundOnlyWhenUnfocused = bool(request, "value", false);
            case "notificationSound" -> {
                var sound = string(request, "value", "default");
                settings.notificationSound = NotificationSoundPlayer.BUILT_IN_SOUND_IDS.contains(sound)
                    || Objects.equals(sound, "custom") ? sound : "default";
            }
            case "customSoundPath" -> settings.customSoundPath = string(request, "value", "").trim();
            default -> {
                return;
            }
        }
        listener.settingsChanged();
    }

    void browseNotificationSound() {
        var chooser = new JFileChooser(root.toFile());
        chooser.setDialogTitle("选择自定义提示音");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("音频文件（WAV、MP3、AIFF）", "wav", "mp3", "aif", "aiff"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        var settings = CodexSettingsState.getInstance().getState();
        settings.notificationSound = "custom";
        settings.customSoundPath = chooser.getSelectedFile().getAbsolutePath();
        listener.settingsChanged();
    }

    void playConfiguredSound(boolean reportError) {
        var settings = CodexSettingsState.getInstance().getState();
        if (Objects.equals(settings.notificationSound, "custom") && settings.customSoundPath.isBlank()) {
            if (reportError) listener.toast("请先选择自定义音频文件");
            return;
        }
        attentionService.playConfigured(settings).exceptionally(error -> {
            if (reportError) ApplicationManager.getApplication().invokeLater(
                () -> listener.toast("提示音播放失败：" + errorMessage(error)));
            return null;
        });
    }

    void toggleStreaming() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.streamResponses = !settings.streamResponses;
        listener.settingsChanged();
    }

    void addCustomModel() {
        var model = Messages.showInputDialog(
            project,
            "输入供应商接受的完整模型 ID：",
            "添加自定义模型",
            Messages.getQuestionIcon()
        );
        if (model == null) return;
        model = model.trim();
        if (model.isBlank()) {
            listener.toast("模型 ID 不能为空");
            return;
        }

        // 自定义模型归属于当前渠道的当前供应商，并立即成为本页签的选择。
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profile = settingsService.activeProvider(activeSession().provider());
        profile.customModels.remove(model);
        profile.customModels.add(0, model);
        if (!profile.builtIn) profile.model = model;
        else if (Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)) settings.claudeModel = model;
        else settings.model = model;
        listener.settingsChanged();
        listener.toast("已添加并选择自定义模型：" + model);
    }

    void removeCustomModel(String model) {
        model = Objects.requireNonNullElse(model, "").trim();
        if (model.isBlank()) return;
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profile = settingsService.activeProvider(activeSession().provider());
        if (!profile.customModels.remove(model)) return;

        // 删除当前选择时回退到同一供应商的下一项，不能带入另一渠道的模型。
        if (Objects.equals(activeModel(settings), model)) {
            var loaded = providerModelOptions.get(profile.id);
            var remoteModels = loaded == null ? List.<String>of() : modelIds(loaded);
            var nextModel = selectProviderModel(modelOptions(profile, remoteModels), "", "");
            if (!profile.builtIn) profile.model = nextModel;
            else if (Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)) settings.claudeModel = nextModel;
            else settings.model = nextModel;
        }
        listener.settingsChanged();
        listener.toast("已移除自定义模型：" + model);
    }

    void toggleThinking() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.showThinking = !settings.showThinking;
        listener.settingsChanged();
    }

    void saveInstructions(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.globalInstructions = string(request, "global", "").trim();
        settings.projectInstructions = string(request, "project", "").trim();
        CodexProjectSettingsState.getInstance(project).getState().projectInstructions = "";
        listener.settingsChanged();
        listener.toast("指令已保存到用户级配置，将从下一个新会话开始生效");
    }

    void savePrompt(JsonObject request) {
        // 同一入口处理新建和编辑，保持提示词库结构简单。
        var settings = CodexSettingsState.getInstance().getState();
        var id = string(request, "id", "").trim();
        var name = string(request, "name", "").trim();
        var description = string(request, "description", "").trim();
        var instructions = string(request, "instructions", "").trim();
        if (name.isBlank() || instructions.isBlank()) {
            listener.toast("提示词名称和内容不能为空");
            return;
        }
        CodexSettingsState.PromptPreset target = null;
        for (var prompt : settings.prompts) if (Objects.equals(prompt.id, id)) target = prompt;
        if (target == null) {
            target = new CodexSettingsState.PromptPreset(UUID.randomUUID().toString(), name, description, instructions);
            settings.prompts.add(target);
        } else {
            target.name = name;
            target.description = description;
            target.instructions = instructions;
        }
        settings.activePromptId = target.id;
        listener.settingsChanged();
        listener.toast("提示词已保存");
    }

    void deletePrompt(String id) {
        // 删除后清理当前启用项，避免新会话继续引用已移除的提示词。
        var settings = CodexSettingsState.getInstance().getState();
        settings.prompts.removeIf(prompt -> Objects.equals(prompt.id, id));
        if (Objects.equals(settings.activePromptId, id)) settings.activePromptId = "";
        listener.settingsChanged();
    }

    void selectPrompt(String id) {
        // 仅保存启用项，实际指令在创建新会话时合并。
        var settings = CodexSettingsState.getInstance().getState();
        settings.activePromptId = settings.prompts.stream().anyMatch(prompt -> Objects.equals(prompt.id, id)) ? id : "";
        listener.settingsChanged();
        listener.toast(settings.activePromptId.isBlank() ? "已停用提示词" : "提示词将在下一个新会话生效");
    }

    void saveAgent(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        var id = string(request, "id", "").trim();
        var name = string(request, "name", "").trim();
        var instructions = string(request, "instructions", "").trim();
        if (name.isBlank() || instructions.isBlank()) {
            listener.toast("Agent 名称和身份指令不能为空");
            return;
        }

        // 同一入口同时处理新建与编辑，保持 Agent 配置列表简单可维护。
        CodexSettingsState.AgentProfile target = null;
        for (var agent : settings.agents) if (Objects.equals(agent.id, id)) target = agent;
        if (target == null) {
            target = new CodexSettingsState.AgentProfile(UUID.randomUUID().toString(), name, instructions);
            settings.agents.add(target);
        } else {
            target.name = name;
            target.instructions = instructions;
        }
        settings.activeAgentId = target.id;
        listener.settingsChanged();
        listener.toast("Agent 身份已保存");
    }

    void deleteAgent(String id) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.agents.removeIf(agent -> Objects.equals(agent.id, id));
        if (Objects.equals(settings.activeAgentId, id)) settings.activeAgentId = "";
        listener.settingsChanged();
    }

    void selectAgent(String id) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.activeAgentId = settings.agents.stream().anyMatch(agent -> Objects.equals(agent.id, id)) ? id : "";
        listener.settingsChanged();
        listener.toast(settings.activeAgentId.isBlank() ? "已停用 Agent 身份" : "Agent 身份将在下一个新会话生效");
    }

    String activeModel(CodexSettingsState.StateData settings) {
        var provider = activeSession().provider();
        var profile = CodexSettingsState.getInstance().activeProvider(provider);
        if (!profile.builtIn) return profile.model;
        return Objects.equals(provider, "claude") ? settings.claudeModel : settings.model;
    }

    JsonArray providerModels(CodexSettingsState.StateData settings) {
        var provider = activeSession().provider();
        var profile = CodexSettingsState.getInstance().activeProvider(provider);
        var loaded = providerModelOptions.get(profile.id);
        if (loaded != null) {
            var models = new JsonArray();
            modelOptions(profile, modelIds(loaded)).forEach(models::add);
            return models;
        }
        var models = new JsonArray();
        profile.customModels.forEach(models::add);
        var model = profile.builtIn
            ? (Objects.equals(provider, "claude") ? settings.claudeModel : settings.model)
            : profile.model;
        if (!model.isBlank() && !profile.customModels.contains(model)) models.add(model);
        return models;
    }

    JsonArray customModels() {
        var profile = CodexSettingsState.getInstance().activeProvider(activeSession().provider());
        var models = new JsonArray();
        profile.customModels.forEach(models::add);
        return models;
    }

    JsonArray agentsJson(List<CodexSettingsState.AgentProfile> agents) {
        var result = new JsonArray();
        for (var agent : agents) {
            var item = new JsonObject();
            item.addProperty("id", agent.id);
            item.addProperty("name", agent.name);
            item.addProperty("instructions", agent.instructions);
            result.add(item);
        }
        return result;
    }

    JsonArray promptsJson(List<CodexSettingsState.PromptPreset> prompts) {
        var result = new JsonArray();
        for (var prompt : prompts) {
            var item = new JsonObject();
            item.addProperty("id", prompt.id);
            item.addProperty("name", prompt.name);
            item.addProperty("description", prompt.description);
            item.addProperty("instructions", prompt.instructions);
            result.add(item);
        }
        return result;
    }

    String developerInstructions() {
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = new ArrayList<String>();
        if (!settings.globalInstructions.isBlank()) instructions.add(settings.globalInstructions.trim());
        var projectInstructions = sharedInstructions(settings);
        if (!projectInstructions.isBlank()) instructions.add(projectInstructions.trim());
        settings.agents.stream()
            .filter(agent -> Objects.equals(agent.id, settings.activeAgentId))
            .findFirst()
            .map(agent -> agent.instructions)
            .filter(value -> !value.isBlank())
            .ifPresent(value -> instructions.add(value.trim()));
        return String.join("\n\n", instructions);
    }

    String sharedInstructions(CodexSettingsState.StateData settings) {
        if (!settings.projectInstructions.isBlank()) return settings.projectInstructions;
        // 旧项目级服务可能尚不可用，迁移不能阻断整个聊天设置和模型列表的发布。
        var projectSettings = project == null ? null : CodexProjectSettingsState.getInstance(project);
        if (projectSettings == null) return "";
        var projectInstructions = projectSettings.getState().projectInstructions;
        if (projectInstructions.isBlank()) return "";

        // 旧版项目级配置迁移到统一用户配置，迁移后清空原存储避免重复拼接。
        settings.projectInstructions = projectInstructions.trim();
        projectSettings.getState().projectInstructions = "";
        return settings.projectInstructions;
    }

    static String providerChannel(String value) {
        return Objects.equals(value, "claude") ? "claude" : "codex";
    }

    static String selectProviderModel(List<String> modelIds, String currentModel, String suggestedModel) {
        if (modelIds.isEmpty()) return "";
        if (modelIds.contains(currentModel)) return currentModel;
        if (modelIds.contains(suggestedModel)) return suggestedModel;
        return modelIds.getFirst();
    }

    void loadProviderModels(CodexSettingsState.ProviderProfile profile) {
        var apiKey = ProviderCredentialStore.get(profile.id);
        if (apiKey == null || apiKey.isBlank()) return;
        var profileId = profile.id;
        var profileRevision = profile.revision;
        providerModelService.listModels(profile.snapshot(), apiKey).thenAccept(modelIds ->
            ApplicationManager.getApplication().invokeLater(
                () -> applyModelOptions(profileId, profileRevision, modelIds, null)
            )
        ).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> listener.asyncError("无法读取供应商模型列表", error));
            return null;
        });
    }

    private void applyModelOptions(String profileId, int profileRevision, List<String> modelIds, String defaultModel) {
        if (disposed) return;
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(profileId);
        if (profile == null || profile.revision != profileRevision || modelIds.isEmpty()) return;

        // 自定义模型固定排在接口目录前；接口刷新不能覆盖用户手动指定的选择。
        var settings = settingsService.getState();
        var currentModel = profile.builtIn ? settings.model : profile.model;
        var options = modelOptions(profile, modelIds);
        var nextModel = selectProviderModel(options, currentModel, defaultModel);
        if (profile.builtIn) settings.model = nextModel;
        else profile.model = nextModel;
        var models = new JsonArray();
        modelIds.stream().distinct().forEach(models::add);
        providerModelOptions.put(profile.id, models);

        var activeProfile = settingsService.activeProvider(profile.channel);
        if (!Objects.equals(activeSession().provider(), profile.channel)
            || !Objects.equals(activeProfile.id, profile.id)) return;
        if (!Objects.equals(currentModel, nextModel) && !currentModel.isBlank()) {
            listener.toast("当前供应商不支持模型 " + currentModel + "，已切换到 " + nextModel);
        }
        listener.modelOptionsChanged();
    }

    private JsonObject providerJson(
        CodexSettingsState.ProviderProfile profile,
        boolean localAvailable,
        CodexSettingsState settingsService
    ) {
        var json = new JsonObject();
        var hasApiKey = profile.builtIn || ProviderCredentialStore.has(profile.id);
        var configured = profile.builtIn || (!profile.baseUrl.isBlank() && hasApiKey);
        json.addProperty("id", profile.id);
        json.addProperty("channel", profile.channel);
        json.addProperty("name", profile.name);
        json.addProperty("baseUrl", profile.baseUrl);
        json.addProperty("wireApi", profile.wireApi);
        json.addProperty("builtIn", profile.builtIn);
        json.addProperty("hasApiKey", hasApiKey);
        json.addProperty("available", profile.builtIn ? localAvailable : configured);
        json.addProperty("active", Objects.equals(settingsService.activeProviderId(profile.channel), profile.id));
        json.addProperty("activating", Objects.equals(activatingProviderByChannel.get(profile.channel), profile.id));
        if (profile.builtIn) {
            var executable = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                ? claude.resolvedExecutable(settingsService.getState().claudeExecutable)
                : settingsService.getState().codexExecutable;
            json.addProperty("executable", executable);
        }
        return json;
    }

    private void completeCustomProviderActivation(
        String channel,
        String profileId,
        int profileRevision,
        List<String> modelIds
    ) {
        // 同一渠道只接受最后一次启用请求的结果，避免慢请求覆盖后续选择。
        if (!activatingProviderByChannel.remove(channel, profileId)) return;
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(profileId);
        // 请求期间配置被编辑或删除时丢弃旧结果，避免启用过期连接信息。
        if (profile == null || profile.revision != profileRevision) {
            if (profile != null) listener.toast("供应商配置已变化，请重新启用");
            publishProviderStatus();
            return;
        }
        // 模型目录返回期间可能启动了任务，此时仍保持原供应商。
        if (hasBusySession(channel)) {
            listener.toast("任务运行期间不能切换供应商配置");
            publishProviderStatus();
            return;
        }

        var selectedModel = selectProviderModel(modelOptions(profile, modelIds), profile.model, "");
        if (selectedModel.isBlank()) {
            listener.toast("无法启用供应商：模型目录未返回可用模型");
            publishProviderStatus();
            return;
        }
        profile.model = selectedModel;
        var models = new JsonArray();
        modelIds.stream().distinct().forEach(models::add);
        providerModelOptions.put(profile.id, models);
        completeProviderActivation(profile);
    }

    private void failProviderActivation(String channel, String profileId, int profileRevision, Throwable error) {
        // 用户已经选择其它供应商时忽略旧请求的失败结果。
        if (!activatingProviderByChannel.remove(channel, profileId)) return;
        var profile = CodexSettingsState.getInstance().provider(profileId);
        // 只报告当前版本的失败，旧请求不能覆盖用户随后完成的编辑。
        if (profile != null && profile.revision == profileRevision) {
            listener.toast("无法启用供应商：" + errorMessage(error));
        }
        publishProviderStatus();
    }

    private void completeProviderActivation(CodexSettingsState.ProviderProfile profile) {
        var settingsService = CodexSettingsState.getInstance();
        // 配置按渠道全局启用；空白会话可直接跟随，已有会话保留旧版本并阻止误发。
        settingsService.setActiveProvider(profile.channel, profile.id);
        var session = activeSession();
        if (Objects.equals(session.provider(), profile.channel)
            && session.threadId() == null && session.transcript().isEmpty()) {
            session.providerProfileId(profile.id);
            session.providerRevision(profile.revision);
        }
        applyProviderRuntimeChange(profile.channel);
        listener.settingsChanged();
        publishProviderStatus();
        listener.toast("已启用供应商：" + profile.name);
    }

    private boolean validProviderUrl(String value) {
        try {
            var uri = URI.create(value);
            return uri.getHost() != null
                && (Objects.equals(uri.getScheme(), "http") || Objects.equals(uri.getScheme(), "https"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasBusySession(String channel) {
        return sessionRegistry.sessions().stream()
            .anyMatch(session -> session.busy() && Objects.equals(session.provider(), channel));
    }

    private void applyProviderRuntimeChange(String channel) {
        if (!Objects.equals(channel, CodexSettingsState.CODEX_CHANNEL)) {
            loadModels(channel);
            return;
        }
        codex.restart().thenRun(() -> loadModels(channel)).exceptionally(error -> {
            listener.asyncError("切换 GPT 供应商失败", error);
            return null;
        });
    }

    private ConversationSession activeSession() {
        return sessionRegistry.active();
    }

    private List<String> modelOptions(CodexSettingsState.ProviderProfile profile, List<String> loadedModels) {
        var models = new LinkedHashSet<String>();
        models.addAll(profile.customModels);
        models.addAll(loadedModels);
        return List.copyOf(models);
    }

    private List<String> modelIds(JsonArray models) {
        var result = new ArrayList<String>();
        for (var model : models) result.add(model.getAsString());
        return result;
    }

    private String errorMessage(Throwable throwable) {
        var error = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        return Objects.toString(error.getMessage(), error.toString());
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

    private static int integer(JsonObject object, String field) {
        if (object == null || !object.has(field)) return 0;
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean bool(JsonObject object, String field, boolean fallback) {
        if (object == null || !object.has(field)) return fallback;
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        providerModelOptions.clear();
        activatingProviderByChannel.clear();
    }
}
