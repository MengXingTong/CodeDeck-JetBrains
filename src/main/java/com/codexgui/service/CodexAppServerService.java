package com.codexgui.service;

import com.codexgui.i18n.UiMessages;

import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class CodexAppServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(CodexAppServerService.class);
    private static final Gson GSON = new Gson();
    private static final String FILE_REFERENCE_MARKER = "\uFFFC";
    static final long INITIALIZE_TIMEOUT_SECONDS = 20;
    static final long RPC_TIMEOUT_SECONDS = 60;
    static final long OAUTH_TIMEOUT_SECONDS = 300;
    static final long STOP_TIMEOUT_SECONDS = 2;

    public enum LifecycleState { STOPPED, STARTING, READY, STOPPING, FAILED, DISPOSED }

    static final class Lifecycle {
        private LifecycleState state = LifecycleState.STOPPED;
        private long generation;

        synchronized long beginStart() {
            if (state == LifecycleState.DISPOSED) throw new IllegalStateException(UiMessages.message("Codex 服务已释放"));
            state = LifecycleState.STARTING;
            return ++generation;
        }

        synchronized boolean transition(long expectedGeneration, LifecycleState next) {
            if (generation != expectedGeneration || state == LifecycleState.DISPOSED) return false;
            state = next;
            return true;
        }

        synchronized void beginStop() {
            if (state != LifecycleState.DISPOSED) state = LifecycleState.STOPPING;
        }

        synchronized void dispose() { state = LifecycleState.DISPOSED; }
        synchronized LifecycleState state() { return state; }
        synchronized long generation() { return generation; }
        synchronized boolean isCurrent(long expectedGeneration) { return generation == expectedGeneration; }
    }

    private record ProcessContext(long generation, Process process, BufferedWriter writer) {}
    private record RequestKey(long generation, long requestId) {}
    private record PendingRequest(CompletableFuture<JsonObject> future, ScheduledFuture<?> timeout) {}

    private final Project project;
    private final AtomicLong requestSequence = new AtomicLong(1);
    private final ConcurrentHashMap<RequestKey, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CodexEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Object writerLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Lifecycle lifecycle = new Lifecycle();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "codedeck-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "codedeck-rpc-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ProcessContext context;
    private volatile CompletableFuture<Void> startFuture;
    private volatile CompletableFuture<Void> stopFuture;

    public CodexAppServerService(Project project) {
        this.project = project;
    }

    public static CodexAppServerService getInstance(@NotNull Project project) {
        return project.getService(CodexAppServerService.class);
    }

    public void addListener(CodexEventListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(CodexEventListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return lifecycle.state() == LifecycleState.READY;
    }

    public LifecycleState lifecycleState() {
        return lifecycle.state();
    }

    public CompletableFuture<Void> start() {
        synchronized (lifecycleLock) {
            if (lifecycle.state() == LifecycleState.READY) return CompletableFuture.completedFuture(null);
            if (lifecycle.state() == LifecycleState.DISPOSED) {
                return CompletableFuture.failedFuture(new IllegalStateException(UiMessages.message("Codex 服务已释放")));
            }
            if (lifecycle.state() == LifecycleState.STOPPING && stopFuture != null) {
                return stopFuture.thenCompose(ignored -> start());
            }
            if (startFuture != null && !startFuture.isDone()) return startFuture;
            var generation = lifecycle.beginStart();
            startFuture = new CompletableFuture<>();
            lifecycleExecutor.execute(() -> startBlocking(generation, startFuture));
            return startFuture;
        }
    }

    public CompletableFuture<Void> restart() {
        return stop(UiMessages.message("正在重启 Codex CLI")).thenCompose(ignored -> start());
    }

    CompletableFuture<Void> stop(String detail) {
        synchronized (lifecycleLock) {
            if (lifecycle.state() == LifecycleState.DISPOSED || lifecycle.state() == LifecycleState.STOPPED) {
                return CompletableFuture.completedFuture(null);
            }
            if (lifecycle.state() == LifecycleState.STOPPING && stopFuture != null) return stopFuture;
            lifecycle.beginStop();
            stopFuture = CompletableFuture.runAsync(
                () -> stopCurrentProcess(detail, LifecycleState.STOPPED), lifecycleExecutor);
            return stopFuture;
        }
    }

    private void startBlocking(long generation, CompletableFuture<Void> result) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.snapshot(CodexSettingsState.CODEX_CHANNEL);
        var provider = settings.provider();
        var configuredExecutable = settings.codexExecutable().isBlank() ? "codex" : settings.codexExecutable();
        var executable = CodexExecutableResolver.resolve(configuredExecutable, SystemInfo.isWindows);
        try {
            var command = createCommand(executable, provider);
            var builder = new ProcessBuilder(command);
            CodexProviderConfiguration.applyEnvironment(builder, provider);
            if (project.getBasePath() != null) builder.directory(Path.of(project.getBasePath()).toFile());
            var process = builder.start();
            var startedContext = new ProcessContext(
                generation,
                process,
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))
            );
            synchronized (lifecycleLock) {
                if (!lifecycle.isCurrent(generation) || lifecycle.state() != LifecycleState.STARTING) {
                    terminateProcess(process);
                    throw new CancellationException(UiMessages.message("Codex 启动已取消"));
                }
                context = startedContext;
            }

            // 独立读取标准输出和错误输出，避免 app-server 的任一管道阻塞。
            AppExecutorUtil.getAppExecutorService().execute(() -> readProtocolLoop(startedContext));
            AppExecutorUtil.getAppExecutorService().execute(() -> readErrorLoop(startedContext));

            var capabilities = new JsonObject();
            capabilities.addProperty("experimentalApi", true);
            capabilities.addProperty("requestAttestation", false);
            var clientInfo = new JsonObject();
            clientInfo.addProperty("name", "codedeck-jetbrains");
            clientInfo.addProperty("title", "CodeDeck for JetBrains");
            clientInfo.addProperty("version", "2.1.1");
            var params = new JsonObject();
            params.add("clientInfo", clientInfo);
            params.add("capabilities", capabilities);

            request(startedContext, "initialize", params, INITIALIZE_TIMEOUT_SECONDS)
                .whenComplete((ignored, error) -> lifecycleExecutor.execute(
                    () -> completeStart(generation, executable, startedContext, result, error)));
        } catch (Exception error) {
            failStart(generation, executable, result, error);
        }
    }

    private void completeStart(
        long generation,
        String executable,
        ProcessContext startedContext,
        CompletableFuture<Void> result,
        Throwable initializeError
    ) {
        if (!isCurrent(startedContext) || lifecycle.state() != LifecycleState.STARTING) {
            result.completeExceptionally(new CancellationException(UiMessages.message("Codex 启动已取消")));
            return;
        }
        if (initializeError != null) {
            failStart(generation, executable, result, initializeError);
            return;
        }
        try {
            notifyServer(startedContext, "initialized", null);
            if (!lifecycle.transition(generation, LifecycleState.READY)) {
                throw new CancellationException(UiMessages.message("Codex 启动已取消"));
            }
            result.complete(null);
            fireConnectionChanged(true, UiMessages.message("Codex CLI 已连接"));
        } catch (Exception error) {
            failStart(generation, executable, result, error);
        }
    }

    private void failStart(long generation, String executable, CompletableFuture<Void> result, Throwable error) {
        var cause = unwrap(error);
        var message = startupFailureMessage(executable, cause instanceof Exception exception ? exception : new Exception(cause));
        LOG.warn(message, cause);
        stopGeneration(generation, UiMessages.message("Codex CLI 启动失败"), LifecycleState.FAILED);
        lifecycle.transition(generation, LifecycleState.FAILED);
        result.completeExceptionally(new IllegalStateException(message, cause));
    }

    private String startupFailureMessage(String executable, Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)) {
            cause = cause.getCause();
        }
        var detail = cause.getMessage();
        if (detail == null || detail.isBlank()) detail = cause.getClass().getSimpleName();
        return UiMessages.message("无法启动 Codex CLI（") + executable + "）：" + detail
            + UiMessages.message("。请安装 OpenAI Codex CLI，或在“设置 → 工具 → CodeDeck”中指定可执行文件。");
    }

    static List<String> createCommand(String executable, CodexSettingsState.ProviderProfile provider) {
        return createCommand(executable, provider.snapshot());
    }

    static List<String> createCommand(String executable, CodexSettingsState.ProviderProfileSnapshot provider) {
        var arguments = new java.util.ArrayList<>(CodexProviderConfiguration.arguments(provider));
        arguments.add("app-server");
        arguments.add("--stdio");

        return ExecutableCommand.build(executable, arguments);
    }

    public CompletableFuture<JsonObject> listThreads(String searchTerm) {
        return listThreadPages(threadListParams(searchTerm), params -> request("thread/list", params));
    }

    static JsonObject threadListParams(String searchTerm) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("sortKey", "updated_at");
        params.addProperty("sortDirection", "desc");
        // 省略 sourceKinds 会使用服务端默认来源，无法保证跨客户端历史完整。
        var sources = new JsonArray();
        List.of("cli", "vscode", "appServer", "exec", "unknown").forEach(sources::add);
        params.add("sourceKinds", sources);
        if (searchTerm != null && !searchTerm.isBlank()) params.addProperty("searchTerm", searchTerm.trim());
        return params;
    }

    static CompletableFuture<JsonObject> listThreadPages(
        JsonObject params, Function<JsonObject, CompletableFuture<JsonObject>> requestPage
    ) {
        return collectThreadPages(params.deepCopy(), requestPage, new JsonArray(), new JsonObject(), new HashSet<>(), new HashSet<>());
    }

    private static CompletableFuture<JsonObject> collectThreadPages(
        JsonObject params, Function<JsonObject, CompletableFuture<JsonObject>> requestPage,
        JsonArray threads, JsonObject result, Set<String> seenIds, Set<String> seenCursors
    ) {
        return requestPage.apply(params.deepCopy()).thenCompose(page -> {
            if (result.size() == 0) page.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("data") && !entry.getKey().equals("nextCursor"))
                .forEach(entry -> result.add(entry.getKey(), entry.getValue()));
            var data = page.getAsJsonArray("data");
            if (data != null) for (var element : data) {
                if (!element.isJsonObject()) continue;
                var thread = element.getAsJsonObject();
                if (!thread.has("id") || thread.get("id").isJsonNull()) continue;
                if (seenIds.add(thread.get("id").getAsString())) threads.add(thread);
            }
            var nextCursor = page.has("nextCursor") && !page.get("nextCursor").isJsonNull()
                ? page.get("nextCursor").getAsString() : "";
            if (nextCursor.isBlank()) {
                result.add("data", threads);
                result.add("nextCursor", com.google.gson.JsonNull.INSTANCE);
                return CompletableFuture.completedFuture(result);
            }
            if (!seenCursors.add(nextCursor) || seenCursors.size() > 1000) {
                return CompletableFuture.failedFuture(new IOException(UiMessages.message("Codex 历史分页游标重复或超过分页上限")));
            }
            params.addProperty("cursor", nextCursor);
            return collectThreadPages(params, requestPage, threads, result, seenIds, seenCursors);
        });
    }

    public CompletableFuture<JsonObject> listModels() {
        return listModels(null, new JsonArray(), new JsonObject());
    }

    private CompletableFuture<JsonObject> listModels(String cursor, JsonArray models, JsonObject page) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("includeHidden", false);
        if (cursor != null && !cursor.isBlank()) params.addProperty("cursor", cursor);
        return request("model/list", params).thenCompose(result -> {
            // 模型目录按游标读取完整结果，避免只显示首批模型。
            var data = result.getAsJsonArray("data");
            if (data != null) data.forEach(models::add);
            if (page.size() == 0) result.entrySet().stream()
                .filter(entry -> !Objects.equals(entry.getKey(), "data") && !Objects.equals(entry.getKey(), "nextCursor"))
                .forEach(entry -> page.add(entry.getKey(), entry.getValue()));
            var nextCursor = result.has("nextCursor") && !result.get("nextCursor").isJsonNull()
                ? result.get("nextCursor").getAsString() : "";
            if (!nextCursor.isBlank()) return listModels(nextCursor, models, page);
            page.add("data", models);
            return CompletableFuture.completedFuture(page);
        });
    }

    public CompletableFuture<JsonObject> listSkills(boolean forceReload) {
        var params = new JsonObject();
        var roots = new JsonArray();
        if (project.getBasePath() != null) roots.add(project.getBasePath());
        params.add("cwds", roots);
        params.addProperty("forceReload", forceReload);
        return request("skills/list", params);
    }

    public CompletableFuture<JsonObject> setSkillEnabled(String path, boolean enabled) {
        var params = new JsonObject();
        params.addProperty("enabled", enabled);
        if (path != null && !path.isBlank()) params.addProperty("path", path);
        return request("skills/config/write", params);
    }

    public CompletableFuture<JsonObject> startThread(
        String model,
        String effort,
        String serviceTier,
        String approvalPolicy,
        String sandbox,
        String developerInstructions
    ) {
        var params = new JsonObject();
        if (project.getBasePath() != null) params.addProperty("cwd", project.getBasePath());
        if (model != null && !model.isBlank()) params.addProperty("model", model);
        if (serviceTier != null && !serviceTier.isBlank() && !Objects.equals(serviceTier, "standard")) params.addProperty("serviceTier", serviceTier);
        params.addProperty("approvalPolicy", approvalPolicy);
        params.addProperty("sandbox", sandbox);
        params.addProperty("ephemeral", false);
        params.addProperty("threadSource", "codedeck");
        params.addProperty("experimentalRawEvents", true);
        // 只传递用户在插件设置中明确保存的指令，不注入第三方增强提示词。
        if (developerInstructions != null && !developerInstructions.isBlank()) {
            params.addProperty("developerInstructions", developerInstructions);
        }
        return request("thread/start", params);
    }

    public CompletableFuture<JsonObject> resumeThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("excludeTurns", true);
        return request("thread/resume", params).thenCompose(result -> {
            var turns = new JsonArray();
            return listThreadTurns(threadId, null, turns).thenApply(ignored -> {
                var thread = result.getAsJsonObject("thread");
                if (thread != null) thread.add("turns", turns);
                return result;
            });
        });
    }

    public CompletableFuture<JsonObject> openHistoryThread(String threadId) {
        return openHistoryThread(threadId, this::request);
    }

    static CompletableFuture<JsonObject> openHistoryThread(
        String threadId, BiFunction<String, JsonObject, CompletableFuture<JsonObject>> request
    ) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("excludeTurns", true);
        return request.apply("thread/resume", params).handle((result, error) -> {
            if (error == null) {
                result.addProperty("historyReadOnly", false);
                return CompletableFuture.completedFuture(result);
            }
            var cause = error;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            // 只对写入权冲突降级；认证、连接和其他协议错误保留原始失败。
            if (cause.getMessage() == null
                || !cause.getMessage().contains("thread " + threadId + " already has an active writer")) {
                return CompletableFuture.<JsonObject>failedFuture(cause);
            }
            var readParams = new JsonObject();
            readParams.addProperty("threadId", threadId);
            readParams.addProperty("includeTurns", false);
            return request.apply("thread/read", readParams).thenApply(snapshot -> {
                snapshot.addProperty("historyReadOnly", true);
                return snapshot;
            });
        }).thenCompose(Function.identity()).thenCompose(result -> {
            var turns = new JsonArray();
            return listThreadTurns(threadId, null, turns, request).thenApply(ignored -> {
                var thread = result.getAsJsonObject("thread");
                if (thread != null) thread.add("turns", turns);
                return result;
            });
        });
    }

    private CompletableFuture<Void> listThreadTurns(String threadId, String cursor, JsonArray turns) {
        return listThreadTurns(threadId, cursor, turns, this::request);
    }

    private static CompletableFuture<Void> listThreadTurns(
        String threadId, String cursor, JsonArray turns,
        BiFunction<String, JsonObject, CompletableFuture<JsonObject>> request
    ) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("limit", 100);
        params.addProperty("sortDirection", "asc");
        params.addProperty("itemsView", "full");
        if (cursor != null && !cursor.isBlank()) params.addProperty("cursor", cursor);
        return request.apply("thread/turns/list", params).thenCompose(result -> {
            // 按升序逐页合并完整 turn，保证历史消息顺序与原会话一致。
            var data = result.getAsJsonArray("data");
            if (data != null) data.forEach(turns::add);
            var nextCursor = result.has("nextCursor") && !result.get("nextCursor").isJsonNull()
                ? result.get("nextCursor").getAsString() : "";
            if (nextCursor.isBlank()) return CompletableFuture.completedFuture(null);
            return listThreadTurns(threadId, nextCursor, turns, request);
        });
    }

    public CompletableFuture<JsonObject> readThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("includeTurns", true);
        return request("thread/read", params);
    }

    public CompletableFuture<JsonObject> setThreadName(String threadId, String name) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("name", name);
        return request("thread/name/set", params);
    }

    public CompletableFuture<JsonObject> rollbackThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("numTurns", 1);
        return request("thread/rollback", params);
    }

    public CompletableFuture<JsonObject> compactThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        return request("thread/compact/start", params);
    }

    public CompletableFuture<JsonObject> reviewUncommittedChanges(String threadId) {
        var target = new JsonObject();
        target.addProperty("type", "uncommittedChanges");
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("target", target);
        params.addProperty("delivery", "inline");
        return request("review/start", params);
    }

    public CompletableFuture<JsonObject> readAccount() {
        return request("account/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> readUsage() {
        return request("account/usage/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> readRateLimits() {
        return request("account/rateLimits/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> listMcpServers(String threadId) {
        return request("mcpServerStatus/list", mcpServerListParams(threadId));
    }

    static JsonObject mcpServerListParams(String threadId) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        // 设置页只展示工具和认证状态；完整清单还会等待各服务器枚举资源。
        params.addProperty("detail", "toolsAndAuthOnly");
        if (threadId != null && !threadId.isBlank()) params.addProperty("threadId", threadId);
        return params;
    }

    public CompletableFuture<JsonObject> reloadMcpServers() {
        return request("config/mcpServer/reload", new JsonObject());
    }

    public CompletableFuture<JsonObject> readConfig(String cwd) {
        var params = new JsonObject();
        if (cwd != null && !cwd.isBlank()) params.addProperty("cwd", cwd);
        params.addProperty("includeLayers", false);
        return request("config/read", params);
    }

    public CompletableFuture<JsonObject> writeConfigValue(String keyPath, JsonElement value) {
        var params = new JsonObject();
        params.addProperty("keyPath", keyPath);
        params.add("value", value == null ? com.google.gson.JsonNull.INSTANCE : value);
        params.addProperty("mergeStrategy", "replace");
        return request("config/value/write", params);
    }

    public CompletableFuture<JsonObject> loginMcpServer(String name, String threadId) {
        var params = new JsonObject();
        params.addProperty("name", name);
        if (threadId != null && !threadId.isBlank()) params.addProperty("threadId", threadId);
        return request("mcpServer/oauth/login", params);
    }

    public CompletableFuture<JsonObject> startTurn(
        String threadId,
        String text,
        List<Attachment> attachments,
        List<FileReference> fileReferences,
        String model,
        String effort,
        String serviceTier,
        String approvalPolicy,
        String sandboxMode
    ) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("approvalPolicy", approvalPolicy);
        if (model != null && !model.isBlank()) params.addProperty("model", model);
        if (effort != null && !effort.isBlank()) params.addProperty("effort", effort);
        if (serviceTier != null && !serviceTier.isBlank() && !Objects.equals(serviceTier, "standard")) params.addProperty("serviceTier", serviceTier);
        params.add("sandboxPolicy", sandboxPolicy(sandboxMode, approvalPolicy));
        params.add("input", turnInput(text, attachments, fileReferences));
        return request("turn/start", params);
    }

    public CompletableFuture<JsonObject> steerTurn(
        String threadId,
        String expectedTurnId,
        String text,
        List<Attachment> attachments,
        List<FileReference> fileReferences
    ) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("expectedTurnId", expectedTurnId);
        params.add("input", turnInput(text, attachments, fileReferences));
        return request("turn/steer", params);
    }

    static JsonArray turnInput(String text, List<Attachment> attachments, List<FileReference> fileReferences) {
        var input = new JsonArray();
        addTextAndFileReferences(input, text, fileReferences);
        for (var attachment : attachments) input.add(attachmentInput(attachment));
        return input;
    }

    private static void addTextAndFileReferences(JsonArray input, String text, List<FileReference> fileReferences) {
        var parts = Objects.requireNonNullElse(text, "").split(FILE_REFERENCE_MARKER, -1);
        var embeddedCount = Math.min(fileReferences.size(), Math.max(0, parts.length - 1));
        for (var index = 0; index < parts.length; index++) {
            if (!parts[index].isEmpty()) input.add(textInput(parts[index]));
            if (index < embeddedCount) input.add(fileReferenceInput(fileReferences.get(index)));
        }
        // 兼容没有占位符的旧调用，不能静默丢失文件引用。
        for (var index = embeddedCount; index < fileReferences.size(); index++) {
            input.add(fileReferenceInput(fileReferences.get(index)));
        }
    }

    private static JsonObject textInput(String text) {
        var input = new JsonObject();
        input.addProperty("type", "text");
        input.addProperty("text", text);
        input.add("text_elements", new JsonArray());
        return input;
    }

    public CompletableFuture<JsonObject> interruptTurn(String threadId, String turnId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("turnId", turnId);
        return request("turn/interrupt", params);
    }

    public void respondToServerRequest(long requestId, JsonObject result) {
        var response = new JsonObject();
        response.addProperty("id", requestId);
        response.add("result", result);
        writeMessage(requireReadyContext(), response);
    }

    private static JsonObject attachmentInput(Attachment attachment) {
        var input = new JsonObject();
        switch (attachment.kind()) {
            case IMAGE -> {
                input.addProperty("type", "localImage");
                input.addProperty("path", attachment.path().toAbsolutePath().toString());
            }
            case FILE -> {
                return textInput(FileReferenceText.format(attachment.path()));
            }
        }
        return input;
    }

    private static JsonObject fileReferenceInput(FileReference reference) {
        // Native mention items do not place local paths in the model-visible user message.
        return textInput(FileReferenceText.format(reference.path()));
    }

    static JsonObject sandboxPolicy(String mode, String approvalPolicy) {
        var policy = new JsonObject();
        var automatic = Objects.equals(approvalPolicy, "never");
        switch (Objects.requireNonNullElse(mode, "workspace-write")) {
            case "read-only" -> {
                policy.addProperty("type", "readOnly");
                policy.addProperty("networkAccess", automatic);
            }
            case "danger-full-access" -> policy.addProperty("type", "dangerFullAccess");
            default -> {
                policy.addProperty("type", "workspaceWrite");
                policy.add("writableRoots", new JsonArray());
                policy.addProperty("networkAccess", automatic);
                policy.addProperty("excludeTmpdirEnvVar", false);
                policy.addProperty("excludeSlashTmp", false);
            }
        }
        return policy;
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        var activeContext = requireReadyContext();
        var timeoutSeconds = timeoutSeconds(method);
        return request(activeContext, method, params, timeoutSeconds);
    }

    BiFunction<String, JsonObject, CompletableFuture<JsonObject>> scopedRequests() {
        var activeContext = requireReadyContext();
        // 后台标题请求必须绑定当前供应商进程，切换供应商后不能续发到新连接。
        return (method, params) -> request(activeContext, method, params, timeoutSeconds(method));
    }

    static long timeoutSeconds(String method) {
        return Objects.equals(method, "mcpServer/oauth/login") ? OAUTH_TIMEOUT_SECONDS : RPC_TIMEOUT_SECONDS;
    }

    private CompletableFuture<JsonObject> request(
        ProcessContext activeContext,
        String method,
        JsonObject params,
        long timeoutSeconds
    ) {
        var id = requestSequence.getAndIncrement();
        var key = new RequestKey(activeContext.generation(), id);
        var request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params == null ? new JsonObject() : params);
        var future = new CompletableFuture<JsonObject>();
        var timeout = timeoutExecutor.schedule(() -> {
            var pending = pendingRequests.remove(key);
            if (pending != null) pending.future().completeExceptionally(
                new TimeoutException(UiMessages.message("Codex RPC 超时：") + method));
        }, timeoutSeconds, TimeUnit.SECONDS);
        pendingRequests.put(key, new PendingRequest(future, timeout));
        try {
            writeMessage(activeContext, request);
        } catch (RuntimeException error) {
            var pending = pendingRequests.remove(key);
            if (pending != null) pending.timeout().cancel(false);
            future.completeExceptionally(error);
        }
        return future;
    }

    private void notifyServer(ProcessContext activeContext, String method, JsonObject params) {
        var notification = new JsonObject();
        notification.addProperty("method", method);
        if (params != null) notification.add("params", params);
        writeMessage(activeContext, notification);
    }

    private ProcessContext requireReadyContext() {
        var activeContext = context;
        if (lifecycle.state() != LifecycleState.READY || activeContext == null) {
            throw new IllegalStateException(UiMessages.message("Codex app-server 尚未就绪"));
        }
        return activeContext;
    }

    private void writeMessage(ProcessContext activeContext, JsonObject message) {
        synchronized (writerLock) {
            try {
                if (context != activeContext || !lifecycle.isCurrent(activeContext.generation())) {
                    throw new IOException(UiMessages.message("Codex app-server 进程已过期"));
                }
                activeContext.writer().write(GSON.toJson(message));
                activeContext.writer().newLine();
                activeContext.writer().flush();
            } catch (IOException error) {
                throw new IllegalStateException(UiMessages.message("无法写入 Codex app-server"), error);
            }
        }
    }

    private void readProtocolLoop(ProcessContext activeContext) {
        try (var reader = new BufferedReader(new InputStreamReader(activeContext.process().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!isCurrent(activeContext)) return;
                handleProtocolMessage(activeContext.generation(), line);
            }
        } catch (Exception error) {
            if (isCurrent(activeContext)) fireProtocolError(UiMessages.message("Codex CLI 连接已断开"), error);
        } finally {
            if (isCurrent(activeContext)) lifecycleExecutor.execute(
                () -> stopGeneration(activeContext.generation(), UiMessages.message("Codex CLI 已停止"), LifecycleState.FAILED));
        }
    }

    private void readErrorLoop(ProcessContext activeContext) {
        try (var reader = new BufferedReader(new InputStreamReader(activeContext.process().getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) LOG.warn("codex app-server: " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void handleProtocolMessage(long generation, String line) {
        if (!lifecycle.isCurrent(generation)) return;
        try {
            var message = GSON.fromJson(line, JsonObject.class);
            if (message.has("method")) {
                var method = message.get("method").getAsString();
                var params = message.has("params") && message.get("params").isJsonObject()
                    ? message.getAsJsonObject("params") : new JsonObject();
                if (message.has("id")) {
                    var id = message.get("id").getAsLong();
                    fireServerRequest(id, method, params);
                } else {
                    fireNotification(method, params);
                }
                return;
            }

            if (!message.has("id")) return;
            var id = message.get("id").getAsLong();
            var pending = pendingRequests.remove(new RequestKey(generation, id));
            if (pending == null) return;
            pending.timeout().cancel(false);
            var future = pending.future();
            if (message.has("error")) {
                future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
            } else {
                JsonElement result = message.get("result");
                future.complete(result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
            }
        } catch (Exception error) {
            fireProtocolError(UiMessages.message("无法解析 Codex app-server 消息"), error);
        }
    }

    private void fireConnectionChanged(boolean state, String detail) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onConnectionChanged(state, detail)));
    }

    private void fireNotification(String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onNotification(method, params)));
    }

    private void fireServerRequest(long id, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onServerRequest(id, method, params)));
    }

    private void fireProtocolError(String message, Throwable error) {
        LOG.warn(message, error);
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onProtocolError(message, error)));
    }

    private boolean isCurrent(ProcessContext activeContext) {
        return context == activeContext && lifecycle.isCurrent(activeContext.generation());
    }

    private void stopGeneration(long generation, String detail, LifecycleState finalState) {
        var activeContext = context;
        if (!lifecycle.isCurrent(generation) || activeContext == null || activeContext.generation() != generation) return;
        stopCurrentProcess(detail, finalState);
    }

    private void stopCurrentProcess(String detail, LifecycleState finalState) {
        ProcessContext activeContext;
        boolean notifyDisconnected;
        CompletableFuture<Void> interruptedStart;
        synchronized (lifecycleLock) {
            if (lifecycle.state() == LifecycleState.DISPOSED) finalState = LifecycleState.DISPOSED;
            activeContext = context;
            context = null;
            notifyDisconnected = lifecycle.state() == LifecycleState.READY || activeContext != null;
            interruptedStart = startFuture != null && !startFuture.isDone() ? startFuture : null;
            lifecycle.transition(lifecycle.generation(), finalState);
        }
        if (activeContext != null) terminateProcess(activeContext.process());
        failPending(activeContext == null ? null : activeContext.generation(), detail);
        if (interruptedStart != null) interruptedStart.completeExceptionally(new CancellationException(detail));
        if (notifyDisconnected) fireConnectionChanged(false, detail);
    }

    private void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void failPending(Long generation, String detail) {
        var error = new IllegalStateException(detail);
        for (var entry : pendingRequests.entrySet()) {
            if (generation != null && entry.getKey().generation() != generation) continue;
            if (!pendingRequests.remove(entry.getKey(), entry.getValue())) continue;
            entry.getValue().timeout().cancel(false);
            entry.getValue().future().completeExceptionally(error);
        }
    }

    private Throwable unwrap(Throwable error) {
        var current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.ExecutionException
            || current instanceof java.util.concurrent.CompletionException)) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void dispose() {
        listeners.clear();
        lifecycle.dispose();
        stopCurrentProcess(UiMessages.message("项目已关闭"), LifecycleState.DISPOSED);
        failPending(null, UiMessages.message("项目已关闭"));
        lifecycleExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }
}
