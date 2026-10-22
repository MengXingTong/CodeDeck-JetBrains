package com.codexgui.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class CodexTitleGeneratorTest {
    private record Call(String method, JsonObject params) {}
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static CodexTitleGenerator fixture(List<Call> calls) {
        return new CodexTitleGenerator((method, params) -> {
            calls.add(new Call(method, params));
            return CompletableFuture.completedFuture(switch (method) {
                case "config/read" -> json("{\"config\":{\"mcp_servers\":{\"slow\":{\"command\":\"tool\"}}}}");
                case "thread/start" -> json("{\"thread\":{\"id\":\"title-thread\"}}");
                case "turn/start" -> json("{\"turn\":{\"id\":\"title-turn\"}}");
                default -> new JsonObject();
            });
        });
    }

    @Test
    void generatesFromRealTurnOutputInAnEphemeralThreadWithoutNamingEvents() {
        var calls = new ArrayList<Call>();
        try (var generator = fixture(calls)) {
            var result = generator.generate("chosen-model", "我发现对话名称不会同步回页签。", "会修复页签标题同步。");
            var start = calls.stream().filter(call -> call.method().equals("thread/start")).findFirst().orElseThrow().params();
            assertTrue(start.get("ephemeral").getAsBoolean());
            assertEquals("chosen-model", start.get("model").getAsString());
            assertEquals("read-only", start.get("sandbox").getAsString());
            assertFalse(start.getAsJsonObject("config").getAsJsonObject("mcp_servers").getAsJsonObject("slow").get("enabled").getAsBoolean());
            assertFalse(start.getAsJsonObject("config").getAsJsonObject("features").get("shell_tool").getAsBoolean());
            var turn = calls.stream().filter(call -> call.method().equals("turn/start")).findFirst().orElseThrow().params();
            assertTrue(turn.has("outputSchema"));
            assertFalse(result.isDone());
            generator.onNotification("turn/completed", json("{\"threadId\":\"unrelated\",\"turn\":{\"status\":\"completed\"}}"));
            assertFalse(result.isDone());
            generator.onNotification("item/agentMessage/delta", json("{\"threadId\":\"title-thread\",\"itemId\":\"answer\",\"delta\":\"{\\\"title\\\":\\\"页签标题同步修复\\\"}\"}"));
            generator.onNotification("item/completed", json("{\"threadId\":\"title-thread\",\"item\":{\"id\":\"answer\",\"type\":\"agentMessage\",\"text\":\"{\\\"title\\\":\\\"页签标题同步修复\\\"}\"}}"));
            generator.onNotification("turn/completed", json("{\"threadId\":\"title-thread\",\"turn\":{\"status\":\"completed\"}}"));
            assertEquals("页签标题同步修复", result.join());
            assertTrue(calls.stream().anyMatch(call -> call.method().equals("thread/unsubscribe")));
            assertFalse(calls.stream().anyMatch(call -> call.method().equals("thread/name/set")));
        }
    }

    @Test
    void retriesDoNotFinishTheTaskAndInvalidResultsAreRejectedAndCleanedUp() {
        var calls = new ArrayList<Call>();
        try (var generator = fixture(calls)) {
            var result = generator.generate("", "question", "answer");
            generator.onNotification("error", json("{\"threadId\":\"title-thread\",\"willRetry\":true}"));
            assertFalse(result.isDone());
            generator.onNotification("item/agentMessage/delta", json("{\"threadId\":\"title-thread\",\"itemId\":\"answer\",\"delta\":\"{\\\"title\\\":\\\"  \\\"}\"}"));
            generator.onNotification("turn/completed", json("{\"threadId\":\"title-thread\",\"turn\":{\"status\":\"completed\"}}"));
            assertThrows(CompletionException.class, result::join);
            assertTrue(calls.stream().anyMatch(call -> call.method().equals("thread/unsubscribe")));
        }
    }

    @Test
    void disconnectCancelsPendingTitleTurns() {
        var calls = new ArrayList<Call>();
        try (var generator = fixture(calls)) {
            var result = generator.generate("", "question", "answer");
            generator.onConnectionChanged(false, "closed");
            assertThrows(CompletionException.class, result::join);
            assertTrue(calls.stream().anyMatch(call -> call.method().equals("turn/interrupt")));
        }
    }
}
