package com.codexgui.provider;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CodexErrorTest {
    @Test
    void readsTheReportedRetryWithoutDumpingProtocolFields() {
        var params = JsonParser.parseString("""
            {"error":{"message":"Reconnecting... 2/5",
            "codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":null}},
            "additionalDetails":"stream disconnected before completion: tls handshake eof","misalignment":null},
            "willRetry":true,"threadId":"thread-a","turnId":"turn-a"}
            """).getAsJsonObject();

        var error = CodexError.from(params);

        assertTrue(error.willRetry());
        assertEquals("Reconnecting... 2/5\nstream disconnected before completion: tls handshake eof", error.message());
    }

    @Test
    void preservesLegacyMessagesAndDoesNotRepeatDetails() {
        var params = JsonParser.parseString("""
            {"message":"stream disconnected: tls handshake eof","additionalDetails":"tls handshake eof"}
            """).getAsJsonObject();

        assertEquals(new CodexError("stream disconnected: tls handshake eof", false), CodexError.from(params));
    }

    @Test
    void missingAndNullErrorsHaveAReadableFallback() {
        assertEquals(new CodexError("Codex 请求失败", false), CodexError.from(null));
        assertEquals(new CodexError("Codex 正在重试连接", true), CodexError.from(
            JsonParser.parseString("{\"error\":null,\"willRetry\":true}").getAsJsonObject()));
    }
}
