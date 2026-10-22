package com.codexgui.service;

import com.codexgui.runtime.JsonRpcProcess;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native app-server smoke test; makes one real title request and archives its test conversation. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_TITLE_CODEX", matches = ".+")
final class CodexTitleGeneratorLiveTest {
    @Test @Timeout(120)
    void realTitleGenerationPersistsANameInsteadOfAMessagePreview() throws Exception {
        var generatorRef = new AtomicReference<CodexTitleGenerator>();
        var processRef = new AtomicReference<JsonRpcProcess>();
        try (var rpc = new JsonRpcProcess(new ProcessBuilder(System.getenv("CODEDECK_REAL_TITLE_CODEX"), "app-server", "--stdio"),
            new JsonRpcProcess.Listener() {
                public void notification(String method, JsonObject params) {
                    if (generatorRef.get() != null) generatorRef.get().onNotification(method, params);
                }
                public void serverRequest(JsonElement id, String method, JsonObject params) {
                    var decline = new JsonObject(); decline.addProperty("decision", "decline");
                    try { processRef.get().respond(id, decline); } catch (Exception ignored) { }
                }
                public void disconnected(String detail) {
                    if (generatorRef.get() != null) generatorRef.get().onConnectionChanged(false, detail);
                }
            }, ignored -> {})) {
            processRef.set(rpc);
            var client = new JsonObject(); client.addProperty("name", "codedeck-title-smoke"); client.addProperty("version", "2.0");
            var init = new JsonObject(); init.add("clientInfo", client);
            var caps = new JsonObject(); caps.addProperty("experimentalApi", true); init.add("capabilities", caps);
            rpc.request("initialize", init).get(25, TimeUnit.SECONDS); rpc.notify("initialized", null);
            var models = rpc.request("model/list", new JsonObject()).get(25, TimeUnit.SECONDS).getAsJsonArray("data");
            var selectedModel = models.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(model -> model.has("isDefault") && model.get("isDefault").getAsBoolean()).findFirst()
                .orElseGet(() -> models.get(0).getAsJsonObject()).get("model").getAsString();
            try (var generator = new CodexTitleGenerator(rpc::request)) {
                generatorRef.set(generator);
                var title = generator.generate(selectedModel, "我发现一个问题，Codex 对话改名后没有同步到页签名称。", "会修复插件的页签标题同步。")
                    .get(100, TimeUnit.SECONDS);
                assertFalse(title.isBlank()); assertTrue(title.codePointCount(0, title.length()) <= 48);
                assertNotEquals("我发现一个问题，Codex 对话改名后没有同步到页签名称。", title);
                var start = CodexTitleGenerator.threadParams("", new JsonObject()); start.addProperty("ephemeral", false);
                var thread = rpc.request("thread/start", start).get(25, TimeUnit.SECONDS).getAsJsonObject("thread");
                var threadId = thread.get("id").getAsString();
                var key = new JsonObject(); key.addProperty("threadId", threadId);
                try {
                    var name = key.deepCopy(); name.addProperty("name", title);
                    rpc.request("thread/name/set", name).get(25, TimeUnit.SECONDS);
                    var read = key.deepCopy(); read.addProperty("includeTurns", false);
                    var stored = rpc.request("thread/read", read).get(25, TimeUnit.SECONDS).getAsJsonObject("thread");
                    assertEquals(title, stored.get("name").getAsString());
                    System.out.println("Native Codex title generated and persisted: " + title);
                } finally {
                    rpc.request("thread/archive", key).get(25, TimeUnit.SECONDS);
                }
            }
        }
    }
}
