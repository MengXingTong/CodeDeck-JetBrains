package com.codexgui.ui;

import com.codexgui.i18n.UiMessages;

import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.service.CodexAppServerService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;

import javax.swing.JFileChooser;
import javax.swing.JPanel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class CodexFeatureCoordinator {
    interface Listener {
        void skillsChanged(JsonArray items, JsonArray errors);
        void skillEnabled(String path, boolean enabled);
        void mcpServersChanged(ConversationSession session, JsonArray items);
        void mcpLog(String level, String serverName, String message);
        void busyChanged(ConversationSession session);
        void threadRolledBack(ConversationSession session, JsonObject result);
        void sendReviewPrompt();
        void toast(String message);
        void toast(ConversationSession session, String message);
        void asyncError(String title, Throwable error);
        void asyncError(ConversationSession session, String title, Throwable error);
    }

    private final Project project;
    private final JPanel owner;
    private final Path root;
    private final CodexAppServerService codex;
    private final SessionRegistry sessionRegistry;
    private final SessionCoordinator sessionCoordinator;
    private final ApprovalCoordinator approvalCoordinator;
    private final Supplier<ConversationSession> activeSession;
    private final Listener listener;
    private boolean mcpLoading;
    private boolean mcpRefreshRequested;
    private boolean mcpReloadRequested;

    CodexFeatureCoordinator(
        Project project,
        JPanel owner,
        Path root,
        CodexAppServerService codex,
        SessionRegistry sessionRegistry,
        SessionCoordinator sessionCoordinator,
        ApprovalCoordinator approvalCoordinator,
        Supplier<ConversationSession> activeSession,
        Listener listener
    ) {
        this.project = project;
        this.owner = owner;
        this.root = root;
        this.codex = codex;
        this.sessionRegistry = sessionRegistry;
        this.sessionCoordinator = sessionCoordinator;
        this.approvalCoordinator = approvalCoordinator;
        this.activeSession = activeSession;
        this.listener = listener;
    }

    void loadSkills(boolean forceReload) {
        loadSkills(forceReload, forceReload);
    }

    private void loadSkills(boolean forceReload, boolean notify) {
        // CLI 尚未完成 initialize 时不能发送请求，等待连接事件重新加载。
        if (!codex.isConnected()) return;
        // 读取当前工作区的原生 Skills，并同步到设置页供浏览和启停。
        codex.listSkills(forceReload).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var items = new JsonArray();
            var errors = new JsonArray();
            for (var entry : array(result, "data")) {
                var group = entry.getAsJsonObject();
                for (var error : array(group, "errors")) errors.add(error.deepCopy());
                for (var skill : array(group, "skills")) {
                    var object = skill.getAsJsonObject();
                    var item = new JsonObject();
                    item.addProperty("name", string(object, "name", "Skill"));
                    item.addProperty("path", string(object, "path", ""));
                    item.addProperty("description", string(object, "description", string(object, "shortDescription", "")));
                    item.addProperty("enabled", !object.has("enabled") || object.get("enabled").getAsBoolean());
                    item.addProperty("scope", string(object, "scope", "repo"));
                    if (object.has("interface") && object.get("interface").isJsonObject()) {
                        item.add("interface", object.getAsJsonObject("interface").deepCopy());
                    }
                    if (object.has("dependencies") && object.get("dependencies").isJsonObject()) {
                        item.add("dependencies", object.getAsJsonObject("dependencies").deepCopy());
                    }
                    items.add(item);
                }
            }
            listener.skillsChanged(items, errors);
            if (notify) listener.toast(items.isEmpty() ? UiMessages.message("当前工作区没有可用的 Skill") : UiMessages.message("Skills 已重新加载"));
        })).exceptionally(error -> {
            listener.asyncError(UiMessages.message("无法读取 Skills"), error);
            return null;
        });
    }

    void setSkillEnabled(JsonObject request) {
        var path = string(request, "path", "");
        var enabled = request.has("enabled") && request.get("enabled").getAsBoolean();
        if (path.isBlank()) {
            listener.toast(UiMessages.message("该 Skill 缺少有效路径"));
            return;
        }
        codex.setSkillEnabled(path, enabled).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var effectiveEnabled = result.has("effectiveEnabled") && result.get("effectiveEnabled").getAsBoolean();
            listener.skillEnabled(path, effectiveEnabled);
            listener.toast(effectiveEnabled ? UiMessages.message("Skill 已启用，Codex 可自动调用") : UiMessages.message("Skill 已停用"));
            loadSkills(true, false);
        })).exceptionally(error -> {
            loadSkills(true, false);
            listener.asyncError(enabled ? UiMessages.message("无法启用 Skill") : UiMessages.message("无法停用 Skill"), error);
            return null;
        });
    }

    void importSkill(String scope) {
        var chooser = new JFileChooser(root.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(Objects.equals(scope, "user") ? UiMessages.message("选择要导入的用户 Skill 目录") : UiMessages.message("选择要导入的项目 Skill 目录"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;

        var source = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(source.resolve("SKILL.md"))) {
            Messages.showErrorDialog(project, UiMessages.message("所选目录中没有 SKILL.md。"), UiMessages.message("导入 Skill 失败"));
            return;
        }

        var parent = Objects.equals(scope, "user")
            ? userSkillsDirectory()
            : root.resolve(".codex").resolve("skills");
        parent = parent.toAbsolutePath().normalize();
        var target = parent.resolve(source.getFileName()).normalize();
        if (!target.startsWith(parent) || Objects.equals(source, target)) {
            listener.toast(Objects.equals(source, target) ? UiMessages.message("该 Skill 已位于目标目录") : UiMessages.message("Skill 目标路径无效"));
            return;
        }
        if (Files.exists(target)) {
            Messages.showErrorDialog(project, UiMessages.message("目标目录已存在：\n") + target, UiMessages.message("导入 Skill 失败"));
            return;
        }

        try {
            Files.createDirectories(parent);
            copySkillDirectory(source, target);
            LocalFileSystem.getInstance().refreshNioFiles(List.of(target), true, true, null);
            listener.toast(Objects.equals(scope, "user") ? UiMessages.message("用户 Skill 已导入") : UiMessages.message("项目 Skill 已导入"));
            loadSkills(true, false);
        } catch (IOException error) {
            deleteImportedDirectory(target);
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("导入 Skill 失败"));
        }
    }

    void openSkill(JsonObject request) {
        var rawPath = string(request, "path", "").trim();
        if (rawPath.isBlank()) {
            listener.toast(UiMessages.message("该 Skill 缺少有效路径"));
            return;
        }
        try {
            var path = Path.of(rawPath);
            var target = Files.isDirectory(path) ? path.resolve("SKILL.md") : path;
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            if (file == null) {
                listener.toast(UiMessages.message("无法打开 Skill 源文件"));
                return;
            }
            new OpenFileDescriptor(project, file).navigate(true);
        } catch (RuntimeException error) {
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("打开 Skill 失败"));
        }
    }

    void compactCurrentThread() {
        var session = activeSession.get();
        if (session.threadId() == null || session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            listener.toast(UiMessages.message("Claude Code 由 CLI 自动管理上下文，无需手动压缩"));
            return;
        }
        codex.compactThread(session.threadId()).thenRun(() -> listener.toast(session, UiMessages.message("上下文压缩完成")))
            .exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(
                    () -> listener.asyncError(session, UiMessages.message("无法压缩上下文"), error));
                return null;
            });
    }

    void reviewCurrentChanges() {
        var session = activeSession.get();
        if (session.threadId() == null || session.busy()) {
            listener.toast(UiMessages.message("请先创建或打开一个会话"));
            return;
        }
        // Claude 没有专用 Review RPC，沿用普通提示词发起审查。
        if (Objects.equals(session.provider(), "claude")) {
            listener.sendReviewPrompt();
            return;
        }
        var turnHandle = sessionCoordinator.beginTurn(session.id());
        listener.busyChanged(session);
        codex.reviewUncommittedChanges(session.threadId()).thenCompose(result -> onEdt(() -> {
            var turn = result.getAsJsonObject("turn");
            if (turn != null) sessionCoordinator.apply(
                turnHandle, current -> current.bindProviderTurn(string(turn, "id", null)));
            return null;
        })).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                approvalCoordinator.clearTurn(turnHandle);
                sessionCoordinator.complete(turnHandle, current -> {
                    listener.busyChanged(current);
                    listener.asyncError(current, UiMessages.message("无法启动代码审查"), error);
                });
            });
            return null;
        });
    }

    void rollbackLastTurn() {
        var session = activeSession.get();
        if (session.threadId() == null || session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            listener.toast(UiMessages.message("Claude Code CLI 暂不支持从此界面回溯上一回合"));
            return;
        }
        if (Messages.showYesNoDialog(
            project,
            UiMessages.message("回退上一轮会话？工作区文件仍由修改面板单独管理。"),
            UiMessages.message("回退上一轮"),
            UiMessages.message("回退"),
            UiMessages.message("取消"),
            Messages.getWarningIcon()
        ) != Messages.YES) return;
        codex.rollbackThread(session.threadId()).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            if (sessionRegistry.find(session.id()) == session) listener.threadRolledBack(session, result);
        })).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(
                () -> listener.asyncError(session, UiMessages.message("无法回退会话"), error));
            return null;
        });
    }

    void showMcpServers() {
        var session = activeSession.get();
        if (Objects.equals(session.provider(), "claude")) {
            listener.toast(UiMessages.message("Claude Code 的 MCP 配置由 Claude CLI 管理"));
            return;
        }
        if (!codex.isConnected()) {
            listener.toast(UiMessages.message("Codex 正在连接，请稍后重试"));
            return;
        }
        readMcpServers(session, ignored -> {}).thenAccept(items -> ApplicationManager.getApplication().invokeLater(() -> {
            var value = new StringBuilder();
            for (var element : items) {
                value.append("• ").append(string(element.getAsJsonObject(), "name", UiMessages.message("未命名服务器"))).append('\n');
            }
            Messages.showInfoMessage(
                project,
                value.isEmpty() ? UiMessages.message("当前没有已配置的 MCP 服务器。") : value.toString(),
                UiMessages.message("Codex MCP 服务器")
            );
        })).exceptionally(error -> {
            listener.asyncError(UiMessages.message("无法读取 MCP 服务器"), error);
            return null;
        });
    }

    synchronized void loadMcpServers(boolean reload) {
        if (!codex.isConnected()) return;
        if (mcpLoading) {
            mcpRefreshRequested = true;
            mcpReloadRequested |= reload;
            return;
        }
        mcpLoading = true;
        var session = activeSession.get();
        CompletableFuture.completedFuture(new JsonObject())
            .thenCompose(ignored -> reload ? codex.reloadMcpServers() : CompletableFuture.completedFuture(new JsonObject()))
            .thenCompose(ignored -> readMcpServers(session, items -> listener.mcpServersChanged(session, items)))
            .thenAccept(items -> {
                listener.mcpServersChanged(session, items);
                if (reload) listener.toast(UiMessages.message("MCP 配置已重新加载"));
            }).exceptionally(error -> {
                listener.mcpLog("error", "", UiMessages.message("读取 MCP 服务器失败：") + errorMessage(error));
                listener.asyncError(UiMessages.message("无法读取 MCP 服务器"), error);
                return null;
            }).whenComplete((ignored, error) -> {
                boolean refresh, nextReload;
                synchronized (this) {
                    mcpLoading = false;
                    refresh = mcpRefreshRequested;
                    nextReload = mcpReloadRequested;
                    mcpRefreshRequested = false;
                    mcpReloadRequested = false;
                }
                if (refresh) loadMcpServers(nextReload);
            });
    }

    private CompletableFuture<JsonArray> readMcpServers(ConversationSession session, Consumer<JsonArray> configured) {
        var config = CompletableFuture.completedFuture(new JsonObject()).thenCompose(ignored -> codex.readConfig(root.toString()));
        var threadId = Objects.equals(session.provider(), "codex") ? session.threadId() : null;
        var statuses = CompletableFuture.completedFuture(new JsonObject()).thenCompose(ignored -> codex.listMcpServers(threadId));
        return readMcpServers(statuses, config, configured,
            message -> listener.mcpLog("warning", "", message));
    }

    private record McpRead(JsonObject value, Throwable error) {
        JsonObject valueOrEmpty() { return value == null ? new JsonObject() : value; }
    }

    static CompletableFuture<JsonArray> readMcpServers(
        CompletableFuture<JsonObject> statuses, CompletableFuture<JsonObject> config,
        Consumer<JsonArray> configured, Consumer<String> warning
    ) {
        var configRead = config.handle((value, error) -> {
            // 状态读取可能正在等待服务器启动，配置应先显示出来，供用户编辑或停用。
            if (error == null) configured.accept(mergeMcpServers(new JsonObject(), value));
            return new McpRead(value, error);
        });
        return statuses.handle(McpRead::new).thenCombine(configRead, (statusRead, configuration) -> {
            if (statusRead.error() != null && configuration.error() != null) {
                throw new CompletionException(configuration.error());
            }
            if (statusRead.error() != null) {
                warning.accept(UiMessages.message("MCP 连接状态暂不可用，已保留配置，可稍后刷新重试：") + errorMessage(statusRead.error()));
            }
            if (configuration.error() != null) {
                warning.accept(UiMessages.message("MCP 配置暂不可用，当前仅显示运行时状态：") + errorMessage(configuration.error()));
            }
            return mergeMcpServers(statusRead.valueOrEmpty(), configuration.valueOrEmpty());
        });
    }

    static JsonArray mergeMcpServers(JsonObject statusResult, JsonObject configResult) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        var configRoot = configResult.has("config") && configResult.get("config").isJsonObject()
            ? configResult.getAsJsonObject("config")
            : new JsonObject();
        var configured = configRoot.has("mcp_servers") && configRoot.get("mcp_servers").isJsonObject()
            ? configRoot.getAsJsonObject("mcp_servers")
            : new JsonObject();

        // 先保留配置中的离线服务器，再用运行时状态覆盖同名字段。
        for (var entry : configured.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            var item = new JsonObject();
            var serverConfig = entry.getValue().getAsJsonObject().deepCopy();
            item.addProperty("name", entry.getKey());
            item.add("config", serverConfig);
            item.addProperty("enabled", !serverConfig.has("enabled") || serverConfig.get("enabled").getAsBoolean());
            item.addProperty("connectionStatus", "unknown");
            item.add("tools", new JsonObject());
            item.add("resources", new JsonArray());
            item.add("resourceTemplates", new JsonArray());
            merged.put(entry.getKey(), item);
        }

        for (var element : array(statusResult, "data")) {
            if (!element.isJsonObject()) continue;
            var status = element.getAsJsonObject();
            var name = string(status, "name", "");
            if (name.isBlank()) continue;
            var item = merged.getOrDefault(name, new JsonObject());
            for (var entry : status.entrySet()) item.add(entry.getKey(), entry.getValue().deepCopy());
            item.addProperty("name", name);
            if (!item.has("config")) {
                item.add("config", new JsonObject());
                item.addProperty("managed", true);
            }
            if (!item.has("enabled")) item.addProperty("enabled", true);
            item.addProperty(
                "connectionStatus",
                status.has("serverInfo") && status.get("serverInfo").isJsonObject() ? "connected" : "failed"
            );
            merged.put(name, item);
        }

        var result = new JsonArray();
        merged.values().forEach(result::add);
        return result;
    }

    void saveMcpServer(JsonObject request) {
        var name = string(request, "name", "").trim();
        var originalName = string(request, "originalName", "").trim();
        if (!validMcpName(name) || !request.has("config") || !request.get("config").isJsonObject()) {
            listener.toast(UiMessages.message("MCP 配置无效：名称仅支持字母、数字、下划线和连字符"));
            return;
        }
        var config = request.getAsJsonObject("config").deepCopy();
        if (string(config, "command", "").isBlank() && string(config, "url", "").isBlank()) {
            listener.toast(UiMessages.message("MCP 配置必须包含 command 或 url"));
            return;
        }
        var operation = codex.writeConfigValue("mcp_servers." + name, config);
        if (!originalName.isBlank() && !originalName.equals(name) && validMcpName(originalName)) {
            operation = operation.thenCompose(
                ignored -> codex.writeConfigValue("mcp_servers." + originalName, JsonNull.INSTANCE));
        }
        operation.thenCompose(ignored -> codex.reloadMcpServers()).thenRun(() -> {
            listener.toast(originalName.isBlank() ? UiMessages.message("MCP 服务器已添加") : UiMessages.message("MCP 服务器配置已保存"));
            loadMcpServers(false);
        }).exceptionally(error -> {
            listener.asyncError(UiMessages.message("无法保存 MCP 服务器"), error);
            return null;
        });
    }

    void deleteMcpServer(JsonObject request) {
        var name = string(request, "name", "").trim();
        if (!validMcpName(name)) return;
        codex.writeConfigValue("mcp_servers." + name, JsonNull.INSTANCE)
            .thenCompose(ignored -> codex.reloadMcpServers())
            .thenRun(() -> {
                listener.toast(UiMessages.message("MCP 服务器已删除"));
                loadMcpServers(false);
            }).exceptionally(error -> {
                listener.asyncError(UiMessages.message("无法删除 MCP 服务器"), error);
                return null;
            });
    }

    void setMcpServerEnabled(JsonObject request) {
        var name = string(request, "name", "").trim();
        if (!validMcpName(name) || !request.has("enabled")) return;
        var enabled = request.get("enabled").getAsBoolean();
        codex.writeConfigValue("mcp_servers." + name + ".enabled", new JsonPrimitive(enabled))
            .thenCompose(ignored -> codex.reloadMcpServers())
            .thenRun(() -> {
                listener.toast(enabled ? UiMessages.message("MCP 服务器已启用") : UiMessages.message("MCP 服务器已停用"));
                loadMcpServers(false);
            }).exceptionally(error -> {
                listener.asyncError(UiMessages.message("无法切换 MCP 服务器"), error);
                return null;
            });
    }

    static boolean validMcpName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,64}");
    }

    void loginMcpServer(JsonObject request) {
        var session = activeSession.get();
        var name = string(request, "name", "").trim();
        if (name.isBlank()) return;
        codex.loginMcpServer(name, session.threadId()).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var authorizationUrl = string(result, "authorizationUrl", "");
            if (authorizationUrl.isBlank()) {
                listener.toast(UiMessages.message("MCP 服务器未返回登录地址"));
                return;
            }
            BrowserUtil.browse(authorizationUrl);
            listener.toast(UiMessages.message("已在浏览器中打开 MCP 登录页面"));
        })).exceptionally(error -> {
            listener.asyncError(UiMessages.message("无法登录 MCP 服务器"), error);
            return null;
        });
    }

    void openMcpConfig() {
        var path = Path.of(System.getProperty("user.home"), ".codex", "config.toml");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            }
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) {
                listener.toast(UiMessages.message("无法打开 Codex 配置文件"));
                return;
            }
            new OpenFileDescriptor(project, file).navigate(true);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), UiMessages.message("打开 MCP 配置失败"));
        }
    }

    void showUsage() {
        var account = codex.readAccount();
        var usage = codex.readUsage();
        var limits = codex.readRateLimits();
        CompletableFuture.allOf(account, usage, limits).thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            var accountData = account.join().getAsJsonObject("account");
            var summary = usage.join().getAsJsonObject("summary");
            var rateLimits = limits.join().getAsJsonObject("rateLimits");
            var primary = rateLimits == null ? null : rateLimits.getAsJsonObject("primary");
            var value = new StringBuilder();
            if (accountData != null) {
                value.append(UiMessages.message("账户：")).append(string(accountData, "email", string(accountData, "type", "OpenAI"))).append('\n');
            }
            if (summary != null) value.append(UiMessages.message("累计 Token：")).append(string(summary, "lifetimeTokens", UiMessages.message("暂无"))).append('\n');
            if (primary != null) value.append(UiMessages.message("当前窗口已使用：")).append(string(primary, "usedPercent", "0")).append('%');
            Messages.showInfoMessage(project, value.isEmpty() ? UiMessages.message("暂无用量数据") : value.toString(), UiMessages.message("Codex 用量"));
        })).exceptionally(error -> {
            listener.asyncError(UiMessages.message("无法读取 Codex 用量"), error);
            return null;
        });
    }

    boolean handleNotification(String method, JsonObject params) {
        switch (method) {
            case "skills/changed" -> loadSkills(false);
            case "mcpServer/startupStatus/updated" -> {
                var name = string(params, "name", string(params, "serverName", ""));
                listener.mcpLog("info", name, UiMessages.message("服务器启动状态已更新，正在重新读取连接状态"));
                loadMcpServers(false);
            }
            case "mcpServer/oauthLogin/completed" -> {
                var name = string(params, "name", string(params, "serverName", ""));
                listener.mcpLog("success", name, UiMessages.message("OAuth 登录已完成，正在重新读取连接状态"));
                loadMcpServers(false);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private Path userSkillsDirectory() {
        var configuredHome = System.getenv("CODEX_HOME");
        var codexHome = configuredHome == null || configuredHome.isBlank()
            ? Path.of(System.getProperty("user.home"), ".codex")
            : Path.of(configuredHome);
        return codexHome.resolve("skills");
    }

    private void copySkillDirectory(Path source, Path target) throws IOException {
        try (var entries = Files.walk(source)) {
            for (var iterator = entries.iterator(); iterator.hasNext(); ) {
                var path = iterator.next();
                var destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private void deleteImportedDirectory(Path target) {
        if (!Files.exists(target)) return;
        try (var entries = Files.walk(target)) {
            for (var path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理对象只可能是本次导入创建的新目录，失败时保留现场供用户检查。
        }
    }

    private <T> CompletableFuture<T> onEdt(Supplier<T> operation) {
        var result = new CompletableFuture<T>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private static JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(field);
    }

    private static String string(JsonObject object, String field, String fallback) {
        if (object == null || !object.has(field)) return fallback;
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String errorMessage(Throwable throwable) {
        var error = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        return Objects.toString(error.getMessage(), error.toString());
    }
}
