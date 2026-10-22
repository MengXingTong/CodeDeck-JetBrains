package com.codexgui.i18n;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

final class MessagesTest {
    @Test
    void chineseVariantsUseChineseAndOtherLanguagesFallBackToEnglish() {
        for (var locale : new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE}) {
            assertEquals("zh-CN", Messages.languageTag(locale));
            assertEquals("新会话", Messages.message("新会话", locale));
        }
        for (var locale : new Locale[]{Locale.ENGLISH, Locale.US, Locale.UK, Locale.FRENCH, Locale.ROOT}) {
            assertEquals("en", Messages.languageTag(locale));
            assertEquals("New chat", Messages.message("新会话", locale));
            assertEquals("AI annotations", Messages.message("AI 注释", locale));
        }
        assertEquals("custom model ID", Messages.message("custom model ID", Locale.ENGLISH));
    }

    @Test
    void catalogIsCompleteEnglishAndPreservesControlCharactersInBackendMessages() throws Exception {
        try (var input = Messages.class.getResourceAsStream("/i18n/en.json")) {
            assertNotNull(input);
            var catalog = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(catalog.size() > 900);
            for (var entry : catalog.entrySet()) {
                assertFalse(entry.getValue().getAsString().isBlank(), entry.getKey());
                assertFalse(entry.getValue().getAsString().matches("(?s).*\\p{IsHan}.*"), entry.getKey());
                assertEquals(entry.getValue().getAsString(), Messages.message(entry.getKey(), Locale.ENGLISH));
            }
        }
        assertEquals("\n\nExit code: ", Messages.message("\n\n退出码：", Locale.ENGLISH));
        assertTrue(Messages.message("\n\n[当前编辑器上下文]\n文件: ", Locale.ENGLISH).contains("\nFile: "));
    }
}
