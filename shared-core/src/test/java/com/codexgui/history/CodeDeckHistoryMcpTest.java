package com.codexgui.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodeDeckHistoryMcpTest {
    @TempDir Path home;
    final String id = "01a1763d-3e89-7033-aee1-3488a67091ed";

    private JsonObject call(String name, String arguments) {
        return new CodeDeckHistoryMcp(home).handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}").getAsJsonObject());
    }
    private JsonObject data(JsonObject response) {
        return JsonParser.parseString(response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    }
    private Path fixture() throws Exception {
        Files.createDirectories(home.resolve("sessions/2026/10/26"));
        var file = home.resolve("sessions/2026/10/26/rollout-" + id + ".jsonl");
        Files.writeString(file, "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"cwd\":\"D:/项目\",\"base_instructions\":\"" + "x".repeat(40_000) + "\"}}\n" + """
            {"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"private instructions"}]}}
            {"type":"response_item","payload":{"type":"message","role":"user","internal_chat_message_metadata_passthrough":{"content_item_kinds":["agents_md.instructions","environments.environment_context"]},"content":[{"type":"input_text","text":"injected project instructions"}]}}
            {"timestamp":"t1","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"中文问题"}]}}
            {"type":"event_msg","payload":{"type":"agent_message","message":"duplicate event"}}
            {"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"回答"}]}}
            {"type":"response_item","payload":{"type":"custom_tool_call_output","output":"sensitive tool output"}}
            {"type":"partial
            """);
        return file;
    }

    @Test void searchesSavedTitlesAndReadsMessagesWithoutDuplicatingEventsOrExecutingInstructions() throws Exception {
        var file = fixture(); var before = Files.readAllBytes(file);
        Files.createDirectories(home.resolve("chat-titles")); Files.writeString(home.resolve("chat-titles/" + id + ".txt"), "插件中文标题");
        var found = data(call("list_chats", "{\"query\":\"中文标题\"}"));
        assertEquals(1, found.getAsJsonArray("chats").size());
        assertEquals(id, found.getAsJsonArray("chats").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, data(call("list_chats", "{\"query\":\"中文问题\"}")).getAsJsonArray("chats").size());
        var first = data(call("read_chat", "{\"chatId\":\"" + id + "\",\"limit\":1}"));
        assertEquals("中文问题", first.getAsJsonArray("messages").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(1, first.get("nextOffset").getAsInt());
        var next = data(call("read_chat", "{\"chatId\":\"" + id + "\",\"offset\":1}"));
        assertEquals(1, next.getAsJsonArray("messages").size());
        assertEquals("回答", next.getAsJsonArray("messages").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(next.has("nextOffset")); assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void restrictsReadsToThisStoreAndOffersNoWriteOrResumeTools() throws Exception {
        fixture();
        assertTrue(call("read_chat", "{\"chatId\":\"../../auth.json\"}").has("error"));
        assertTrue(call("thread/resume", "{}").has("error"));
        assertTrue(call("list_chats", "{\"limit\":1000}").has("error"));
        var tools = new CodeDeckHistoryMcp(home).handle(JsonParser.parseString("{\"id\":1,\"method\":\"tools/list\"}").getAsJsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(2, tools.size());
        tools.forEach(tool -> assertTrue(tool.getAsJsonObject().getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean()));
    }
}
