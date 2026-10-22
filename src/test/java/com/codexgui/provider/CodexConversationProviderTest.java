package com.codexgui.provider;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.conversation.TurnId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.codexgui.service.CodexAppServerService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodexConversationProviderTest {
    private final TurnHandle handle = new TurnHandle(new SessionId("session-a"), new TurnId("local-turn"), 3);

    @Test
    void decodesFixtureWithTheOriginalTurnHandle() throws IOException {
        var lines = resource("protocol/codex/turn-events.jsonl").lines().filter(line -> !line.isBlank()).toList();

        var started = assertInstanceOf(TurnEvent.Started.class, decode(lines.get(0)));
        assertEquals(handle, started.handle());
        assertEquals("thread-1", started.conversationId());
        assertEquals("turn-1", started.providerTurnId());

        var delta = assertInstanceOf(TurnEvent.Delta.class, decode(lines.get(1)));
        assertEquals(handle, delta.handle());
        assertEquals("message-1", delta.itemId());
        assertEquals("你好", delta.text());

        var completed = assertInstanceOf(TurnEvent.Completed.class, decode(lines.get(2)));
        assertEquals(handle, completed.handle());
    }

    @Test
    void normalizesItemPayloadAndKeepsItImmutable() {
        var params = JsonParser.parseString("""
            {"item":{"id":"cmd-1","type":"commandExecution","command":"gradlew test"}}
            """).getAsJsonObject();

        var event = assertInstanceOf(TurnEvent.Item.class,
            CodexConversationProvider.decodeNotification(handle, "item/started", params));

        assertEquals(TurnEvent.Item.Phase.STARTED, event.phase());
        assertEquals("commandExecution", event.kind());
        assertEquals("gradlew test", event.data().get("command"));
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("status", "changed"));
    }

    @Test
    void readsUsageFromTheTokenUsageSnapshot() {
        var params = JsonParser.parseString("""
            {"tokenUsage":{"last":{"totalTokens":1250},"modelContextWindow":10000}}
            """).getAsJsonObject();

        var event = assertInstanceOf(TurnEvent.Usage.class,
            CodexConversationProvider.decodeNotification(handle, "thread/tokenUsage/updated", params));

        assertEquals(1250, event.usedTokens());
        assertEquals(10000, event.maxTokens());
    }

    @Test
    void keepsReceivingTheSameTurnAfterAnAutomaticReconnect() {
        var service = new CodexAppServerService(null);
        var events = new ArrayList<TurnEvent>();
        try (var provider = new CodexConversationProvider(service)) {
            provider.registerTurn(handle, "thread-a", events::add);
            provider.onNotification("turn/started", params("""
                {"threadId":"thread-a","turn":{"id":"turn-a"}}
                """));
            provider.onNotification("error", params("""
                {"threadId":"thread-a","turnId":"turn-a","willRetry":true,
                "error":{"message":"Reconnecting... 2/5","additionalDetails":"tls handshake eof"}}
                """));
            provider.onNotification("item/agentMessage/delta", params("""
                {"threadId":"thread-a","turnId":"turn-a","itemId":"message-a","delta":"恢复后的回复"}
                """));
            provider.onNotification("turn/completed", params("""
                {"threadId":"thread-a","turn":{"id":"turn-a","status":"completed"}}
                """));
            provider.onNotification("item/agentMessage/delta", params("""
                {"threadId":"thread-a","turnId":"turn-a","delta":"迟到事件"}
                """));

            assertEquals(5, events.size());
            var notice = assertInstanceOf(TurnEvent.Notice.class, events.get(2));
            assertEquals(handle, notice.handle());
            assertEquals("Reconnecting... 2/5\ntls handshake eof", notice.message());
            assertEquals("恢复后的回复", assertInstanceOf(TurnEvent.Delta.class, events.get(3)).text());
            assertInstanceOf(TurnEvent.Completed.class, events.get(4));
        } finally {
            service.dispose();
        }
    }

    @Test
    void retryForAnotherTurnCannotInterruptTheCurrentTurn() {
        var service = new CodexAppServerService(null);
        var events = new ArrayList<TurnEvent>();
        try (var provider = new CodexConversationProvider(service)) {
            provider.registerTurn(handle, "thread-a", events::add);
            provider.onNotification("turn/started", params("""
                {"threadId":"thread-a","turn":{"id":"turn-a"}}
                """));
            provider.onNotification("error", params("""
                {"threadId":"thread-a","turnId":"old-turn","willRetry":true,"error":{"message":"retry"}}
                """));
            provider.onNotification("item/agentMessage/delta", params("""
                {"threadId":"thread-a","turnId":"turn-a","delta":"继续"}
                """));

            assertEquals(3, events.size());
            assertEquals("继续", assertInstanceOf(TurnEvent.Delta.class, events.getLast()).text());
        } finally {
            service.dispose();
        }
    }

    @Test
    void terminalErrorReadsTheNestedCause() {
        var event = CodexConversationProvider.decodeNotification(handle, "error", params("""
            {"willRetry":false,"error":{"message":"重试次数已用完","additionalDetails":"tls handshake eof"}}
            """));

        var failed = assertInstanceOf(TurnEvent.Failed.class, event);
        assertEquals("重试次数已用完\ntls handshake eof", failed.error().getMessage());
    }

    @Test
    void failedAndInterruptedCompletionNeverReportSuccess() {
        var failed = assertInstanceOf(TurnEvent.Failed.class,
            CodexConversationProvider.decodeNotification(handle, "turn/completed", params("""
                {"turn":{"status":"failed","error":{"message":"连接失败"}}}
                """)));
        assertEquals("连接失败", failed.error().getMessage());
        var stopped = assertInstanceOf(TurnEvent.Failed.class,
            CodexConversationProvider.decodeNotification(handle, "turn/completed", params("""
                {"turn":{"status":"interrupted"}}
                """)));
        assertTrue(stopped.cancelled());
    }

    private static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private TurnEvent decode(String line) {
        var envelope = JsonParser.parseString(line).getAsJsonObject();
        return CodexConversationProvider.decodeNotification(
            handle, envelope.get("method").getAsString(), envelope.getAsJsonObject("params"));
    }

    private static String resource(String path) throws IOException {
        try (var input = CodexConversationProviderTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少测试资源：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
