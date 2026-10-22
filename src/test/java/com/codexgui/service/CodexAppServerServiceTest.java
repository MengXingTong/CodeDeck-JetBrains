package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.conversation.QueuedInput;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexAppServerServiceTest {
    @Test
    void sendsTheReferencedComponentPathInModelVisibleTextInsteadOfMentionMetadata() {
        var path = Path.of("D:/devmain_server/UnrealEngine/Projects/JinYongPVP/Source/JinYongPVP/GamePlay/GameMode/Components/InteractionDropComponent.h");
        var reference = FileReference.fromPath(path);
        var question = "这是什么组件？我改了这个组件很多代码，在做什么？";
        var input = CodexAppServerService.turnInput("\uFFFC" + question, List.of(), List.of(reference));

        assertEquals(2, input.size());
        assertEquals("text", input.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(FileReferenceText.format(path) + question, modelVisibleText(input));
        assertTrue(modelVisibleText(input).contains(path.toAbsolutePath().normalize().toString()));
        assertFalse(modelVisibleText(input).contains("\uFFFC"));
        assertTrue(input.asList().stream().noneMatch(item -> item.getAsJsonObject().get("type").getAsString().equals("mention")));
    }

    @Test
    void keepsReferenceOrderAndTextBoundariesForSpacesDirectoriesAndDeletedPaths() {
        var first = FileReference.fromPath(Path.of("D:/项目/Deleted Component.h"));
        var second = new FileReference("directory", "Source", Path.of("D:/项目/Source"), true);
        var input = CodexAppServerService.turnInput("比较\uFFFC与\uFFFC的关系", List.of(), List.of(second, first));

        assertEquals("比较" + FileReferenceText.format(second.path()) + "与" + FileReferenceText.format(first.path()) + "的关系", modelVisibleText(input));
    }

    @Test
    void legacyReferencesAndFileAttachmentsAreVisibleWhileImagesStayNativeImages() {
        var reference = FileReference.fromPath(Path.of("D:/项目/Source/Component.h"));
        var file = new Attachment(Attachment.Kind.FILE, "notes.txt", Path.of("D:/项目/notes.txt"));
        var image = new Attachment(Attachment.Kind.IMAGE, "photo.png", Path.of("D:/项目/photo.png"));
        var input = CodexAppServerService.turnInput("说明这个文件", List.of(file, image), List.of(reference));

        assertEquals("说明这个文件" + FileReferenceText.format(reference.path()) + FileReferenceText.format(file.path()), modelVisibleText(input));
        assertEquals("localImage", input.get(input.size() - 1).getAsJsonObject().get("type").getAsString());
        assertEquals(image.path().toAbsolutePath().toString(), input.get(input.size() - 1).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void queuedInputKeepsItsReferenceAfterTheComposerIsCleared() {
        var reference = FileReference.fromPath(Path.of("D:/Project/Component.h"));
        var references = new ArrayList<>(List.of(reference));
        var queued = new QueuedInput("\uFFFC解释这个组件", "", List.of(), references);
        references.clear();
        var input = CodexAppServerService.turnInput(queued.inputText(), queued.attachments(), queued.fileReferences());
        assertEquals(FileReferenceText.format(reference.path()) + "解释这个组件", modelVisibleText(input));
    }

    private static String modelVisibleText(JsonArray input) {
        var text = new StringBuilder();
        for (var part : input) {
            var item = part.getAsJsonObject();
            if (item.get("type").getAsString().equals("text")) text.append(item.get("text").getAsString());
        }
        return text.toString();
    }

    @Test
    void mcpSettingsRequestToolsAndAuthWithoutWaitingForResourceInventory() {
        var params = CodexAppServerService.mcpServerListParams("thread-a");
        assertEquals("toolsAndAuthOnly", params.get("detail").getAsString());
        assertEquals("thread-a", params.get("threadId").getAsString());
        assertFalse(CodexAppServerService.mcpServerListParams(null).has("threadId"));
        assertFalse(CodexAppServerService.mcpServerListParams("  ").has("threadId"));
    }

    @Test
    void addsCustomProviderOverridesBeforeAppServerCommand() {
        var provider = new CodexSettingsState.ProviderProfile("open-router", "codex", "Open \"Router\"", false);
        provider.baseUrl = "https://openrouter.ai/api/v1";
        provider.wireApi = "responses";

        var command = CodexAppServerService.createCommand("codex.exe", provider);

        assertTrue(command.contains("model_provider=\"codex_gui\""));
        assertTrue(command.contains("model_providers.codex_gui.name=\"Open \\\"Router\\\"\""));
        assertTrue(command.contains("model_providers.codex_gui.base_url=\"https://openrouter.ai/api/v1\""));
        assertTrue(command.contains("model_providers.codex_gui.env_key=\"CODEX_GUI_PROVIDER_KEY\""));
        assertTrue(command.contains("model_providers.codex_gui.wire_api=\"responses\""));
        assertTrue(command.indexOf("model_provider=\"codex_gui\"") < command.indexOf("app-server"));
    }

    @Test
    void keepsLocalProviderCommandFreeOfOverrides() {
        var provider = new CodexSettingsState.ProviderProfile("codex-local", "codex", "Codex 本地配置", true);

        var command = CodexAppServerService.createCommand("codex.exe", provider);

        assertFalse(command.stream().anyMatch(value -> value.startsWith("model_provider=")));
        assertTrue(command.contains("app-server"));
        assertTrue(command.contains("--stdio"));
    }

    @Test
    void lifecycleRejectsOldProcessGenerationAcrossRestartAndDispose() {
        var lifecycle = new CodexAppServerService.Lifecycle();

        var firstGeneration = lifecycle.beginStart();
        assertEquals(CodexAppServerService.LifecycleState.STARTING, lifecycle.state());
        assertTrue(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.READY));

        lifecycle.beginStop();
        assertEquals(CodexAppServerService.LifecycleState.STOPPING, lifecycle.state());
        assertTrue(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.STOPPED));
        var secondGeneration = lifecycle.beginStart();

        assertFalse(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.READY));
        assertTrue(lifecycle.transition(secondGeneration, CodexAppServerService.LifecycleState.FAILED));
        lifecycle.dispose();
        assertFalse(lifecycle.transition(secondGeneration, CodexAppServerService.LifecycleState.READY));
        assertEquals(CodexAppServerService.LifecycleState.DISPOSED, lifecycle.state());
    }

    @Test
    void appliesDedicatedRpcTimeouts() {
        assertEquals(60, CodexAppServerService.timeoutSeconds("thread/list"));
        assertEquals(300, CodexAppServerService.timeoutSeconds("mcpServer/oauth/login"));
        assertEquals(20, CodexAppServerService.INITIALIZE_TIMEOUT_SECONDS);
        assertEquals(2, CodexAppServerService.STOP_TIMEOUT_SECONDS);
    }

    @Test
    void historyIncludesSharedThreadsAcrossClientsAndProjects() {
        var params = CodexAppServerService.threadListParams("  fix  ");

        assertFalse(params.has("cwd"));
        assertEquals("fix", params.get("searchTerm").getAsString());
        assertEquals(JsonParser.parseString("[\"cli\",\"vscode\",\"appServer\",\"exec\",\"unknown\"]"), params.get("sourceKinds"));
        assertFalse(params.has("modelProviders"));
    }

    @Test
    void historyReadsMoreThanOneHundredSharedThreadsAndDeduplicatesPages() {
        var requests = new ArrayList<JsonObject>();
        var initial = CodexAppServerService.threadListParams("fix");
        var result = CodexAppServerService.listThreadPages(initial, params -> {
            requests.add(params);
            var page = new JsonObject();
            var data = new com.google.gson.JsonArray();
            var start = requests.size() == 1 ? 0 : 99;
            var end = requests.size() == 1 ? 100 : 105;
            for (var i = start; i < end; i++) {
                var thread = new JsonObject();
                thread.addProperty("id", "thread-" + i);
                thread.addProperty("cwd", i % 2 == 0 ? "D:/ProjectA" : "D:/ProjectB");
                thread.addProperty("source", i % 2 == 0 ? "vscode" : "appServer");
                data.add(thread);
            }
            page.add("data", data);
            if (requests.size() == 1) page.addProperty("nextCursor", "page-2");
            else page.add("nextCursor", com.google.gson.JsonNull.INSTANCE);
            return CompletableFuture.completedFuture(page);
        }).join();

        assertEquals(105, result.getAsJsonArray("data").size());
        assertEquals("thread-104", result.getAsJsonArray("data").get(104).getAsJsonObject().get("id").getAsString());
        assertEquals(2, requests.size());
        assertFalse(requests.getFirst().has("cursor"));
        assertEquals("page-2", requests.getLast().get("cursor").getAsString());
        assertEquals(initial.get("sourceKinds"), requests.getLast().get("sourceKinds"));
        assertEquals("fix", requests.getLast().get("searchTerm").getAsString());
        assertFalse(initial.has("cursor"));
        assertTrue(result.get("nextCursor").isJsonNull());
    }

    @Test
    void historyRejectsRepeatingCursorsInsteadOfLoopingForever() {
        var calls = new ArrayList<JsonObject>();
        var failure = CodexAppServerService.listThreadPages(CodexAppServerService.threadListParams(""), params -> {
            calls.add(params);
            return CompletableFuture.completedFuture(JsonParser.parseString("""
                {"data":[],"nextCursor":"same-cursor"}
                """).getAsJsonObject());
        });

        var error = assertThrows(CompletionException.class, failure::join);
        assertTrue(error.getCause().getMessage().contains("分页游标重复"));
        assertEquals(2, calls.size());
    }

    @Test
    void historyPropagatesPageFailureInsteadOfReturningAPartialList() {
        var error = new IllegalStateException("connection closed");
        var calls = new ArrayList<JsonObject>();
        var failure = CodexAppServerService.listThreadPages(CodexAppServerService.threadListParams(null), params -> {
            calls.add(params);
            if (calls.size() == 2) return CompletableFuture.failedFuture(error);
            return CompletableFuture.completedFuture(JsonParser.parseString("""
                {"data":[{"id":"first"}],"nextCursor":"page-2"}
                """).getAsJsonObject());
        });

        assertEquals(error, assertThrows(CompletionException.class, failure::join).getCause());
    }

    @Test
    void occupiedHistoryLoadsReadOnlyMetadataAndAllTurnsWithoutResumingAgain() {
        var methods = new ArrayList<String>();
        var requests = new ArrayList<JsonObject>();
        var result = CodexAppServerService.openHistoryThread("occupied", (method, params) -> {
            methods.add(method);
            requests.add(params);
            return switch (method) {
                case "thread/resume" -> CompletableFuture.failedFuture(new CompletionException(new java.io.IOException(
                    "{\"code\":-32600,\"message\":\"thread occupied already has an active writer\"}")));
                case "thread/read" -> CompletableFuture.completedFuture(JsonParser.parseString("""
                    {"thread":{"id":"occupied","name":"运行中的历史"}}
                    """).getAsJsonObject());
                case "thread/turns/list" -> CompletableFuture.completedFuture(JsonParser.parseString(params.has("cursor") ? """
                    {"data":[{"id":"turn-2","items":[{"type":"agentMessage","text":"已保存回复"}]}]}
                    """ : """
                    {"data":[{"id":"turn-1","items":[]}],"nextCursor":"older-page"}
                    """).getAsJsonObject());
                default -> throw new AssertionError(method);
            };
        }).join();

        assertEquals(java.util.List.of("thread/resume", "thread/read", "thread/turns/list", "thread/turns/list"), methods);
        assertTrue(result.get("historyReadOnly").getAsBoolean());
        assertFalse(requests.get(1).get("includeTurns").getAsBoolean());
        assertEquals("asc", requests.get(2).get("sortDirection").getAsString());
        assertEquals("full", requests.get(2).get("itemsView").getAsString());
        assertEquals("older-page", requests.get(3).get("cursor").getAsString());
        var turns = result.getAsJsonObject("thread").getAsJsonArray("turns");
        assertEquals(2, turns.size());
        assertEquals("turn-1", turns.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("已保存回复", turns.get(1).getAsJsonObject().getAsJsonArray("items")
            .get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void reopeningReleasedHistoryRestoresWritingWithoutReadFallback() {
        var methods = new ArrayList<String>();
        var result = CodexAppServerService.openHistoryThread("released", (method, params) -> {
            methods.add(method);
            assertEquals("released", params.get("threadId").getAsString());
            if (method.equals("thread/resume")) {
                assertTrue(params.get("excludeTurns").getAsBoolean());
                return CompletableFuture.completedFuture(JsonParser.parseString("""
                    {"thread":{"id":"released","turns":[]}}
                    """).getAsJsonObject());
            }
            assertEquals("thread/turns/list", method);
            return CompletableFuture.completedFuture(JsonParser.parseString("""
                {"data":[{"id":"latest","items":[]}]}
                """).getAsJsonObject());
        }).join();

        assertFalse(result.get("historyReadOnly").getAsBoolean());
        assertEquals(java.util.List.of("thread/resume", "thread/turns/list"), methods);
        assertEquals(1, result.getAsJsonObject("thread").getAsJsonArray("turns").size());
    }

    @Test
    void historyDoesNotHideUnrelatedResumeFailures() {
        for (var message : java.util.List.of("connection closed", "thread other already has an active writer")) {
            var methods = new ArrayList<String>();
            var cause = new java.io.IOException(message);
            var failure = CodexAppServerService.openHistoryThread("requested", (method, params) -> {
                methods.add(method);
                return CompletableFuture.failedFuture(cause);
            });

            assertEquals(cause, assertThrows(CompletionException.class, failure::join).getCause());
            assertEquals(java.util.List.of("thread/resume"), methods);
        }
    }

    @Test
    void occupiedHistoryPropagatesReadAndPaginationFailures() {
        for (var failedMethod : java.util.List.of("thread/read", "thread/turns/list")) {
            var cause = new java.io.IOException("history unavailable");
            var failure = CodexAppServerService.openHistoryThread("occupied", (method, params) -> {
                if (method.equals("thread/resume")) return CompletableFuture.failedFuture(
                    new java.io.IOException("thread occupied already has an active writer"));
                if (method.equals(failedMethod)) return CompletableFuture.failedFuture(cause);
                return CompletableFuture.completedFuture(JsonParser.parseString("""
                    {"thread":{"id":"occupied"}}
                    """).getAsJsonObject());
            });

            assertEquals(cause, assertThrows(CompletionException.class, failure::join).getCause());
        }
    }

    @Test
    void automaticWorkspaceSandboxAllowsCommandNetworkAccess() {
        var automatic = CodexAppServerService.sandboxPolicy("workspace-write", "never");
        var approvalRequired = CodexAppServerService.sandboxPolicy("workspace-write", "on-request");

        assertTrue(automatic.get("networkAccess").getAsBoolean());
        assertFalse(approvalRequired.get("networkAccess").getAsBoolean());
    }
}
