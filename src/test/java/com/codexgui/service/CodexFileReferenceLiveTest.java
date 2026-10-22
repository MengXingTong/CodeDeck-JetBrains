package com.codexgui.service;

import com.codexgui.model.FileReference;
import com.codexgui.runtime.JsonRpcProcess;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in end-to-end check: the marker exists only in the referenced file, never in the prompt. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_REFERENCE_CODEX", matches = ".+")
final class CodexFileReferenceLiveTest {
    @TempDir Path root;

    @Test @Timeout(120)
    void modelReadsTheExplicitReferenceAndReturnsTheFilesPrivateMarker() throws Exception {
        var marker = "reference-" + UUID.randomUUID();
        var file = root.resolve("交互 Component.h");
        Files.writeString(file, "// CODEDECK_REFERENCE_TOKEN = " + marker + "\n");
        var completed = new CompletableFuture<Void>();
        var response = new StringBuilder();
        var processRef = new AtomicReference<JsonRpcProcess>();
        try (var rpc = new JsonRpcProcess(new ProcessBuilder(System.getenv("CODEDECK_REAL_REFERENCE_CODEX"), "app-server", "--stdio")
            .directory(root.toFile()), new JsonRpcProcess.Listener() {
                public void notification(String method, JsonObject params) {
                    if (method.equals("item/completed") && params.has("item")) {
                        var item = params.getAsJsonObject("item");
                        if (item.has("type") && item.get("type").getAsString().equals("agentMessage")) {
                            response.append(item.get("text").getAsString());
                        }
                    }
                    if (method.equals("turn/completed")) {
                        var turn = params.getAsJsonObject("turn");
                        if (turn.get("status").getAsString().equals("completed")) completed.complete(null);
                        else completed.completeExceptionally(new IllegalStateException(turn.toString()));
                    }
                }
                public void serverRequest(JsonElement id, String method, JsonObject params) {
                    var decline = new JsonObject(); decline.addProperty("decision", "decline");
                    try { processRef.get().respond(id, decline); } catch (Exception error) { completed.completeExceptionally(error); }
                }
                public void disconnected(String detail) { completed.completeExceptionally(new IllegalStateException(detail)); }
            }, ignored -> {})) {
            processRef.set(rpc);
            var client = new JsonObject(); client.addProperty("name", "codedeck-reference-smoke"); client.addProperty("version", "2.0");
            var init = new JsonObject(); init.add("clientInfo", client);
            var capabilities = new JsonObject(); capabilities.addProperty("experimentalApi", true); init.add("capabilities", capabilities);
            rpc.request("initialize", init).get(25, TimeUnit.SECONDS); rpc.notify("initialized", null);
            var config = rpc.request("config/read", new JsonObject()).get(25, TimeUnit.SECONDS);
            var start = CodexTitleGenerator.threadParams("", config);
            start.addProperty("cwd", root.toString());
            start.addProperty("baseInstructions", "Read local files referenced in the user message with shell tools. Return only the requested value. Do not modify any file.");
            var features = start.getAsJsonObject("config").getAsJsonObject("features");
            features.addProperty("shell_tool", true); features.addProperty("unified_exec", true);
            var threadId = rpc.request("thread/start", start).get(25, TimeUnit.SECONDS)
                .getAsJsonObject("thread").get("id").getAsString();
            var params = new JsonObject(); params.addProperty("threadId", threadId);
            params.addProperty("approvalPolicy", "never"); params.addProperty("effort", "low");
            params.add("sandboxPolicy", CodexAppServerService.sandboxPolicy("read-only", "on-request"));
            var input = CodexAppServerService.turnInput("\uFFFC读取引用文件，只回答 CODEDECK_REFERENCE_TOKEN 的完整值。",
                List.of(), List.of(FileReference.fromPath(file)));
            assertFalse(input.toString().contains(marker)); params.add("input", input);
            rpc.request("turn/start", params).get(25, TimeUnit.SECONDS);
            completed.get(90, TimeUnit.SECONDS);
            assertTrue(response.toString().contains(marker), "Model did not read the reference: " + response);
            System.out.println("Native Codex read the referenced file and returned its private marker.");
        }
    }
}
