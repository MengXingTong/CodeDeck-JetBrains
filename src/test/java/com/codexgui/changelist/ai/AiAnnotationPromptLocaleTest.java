package com.codexgui.changelist.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

final class AiAnnotationPromptLocaleTest {
    @Test
    void annotationLanguageFollowsLocaleWithoutTranslatingSourceOrChangingTheDiffContract() {
        var snapshot = new AiDiffSnapshot("项目/订单.java", "// 原始代码\nint total = 1;\n", "// 原始代码\nint total = 2;\n");
        var english = JsonParser.parseString(AiAnnotationPrompt.create(snapshot, Locale.ENGLISH)).getAsJsonObject();
        var chinese = JsonParser.parseString(AiAnnotationPrompt.create(snapshot, Locale.SIMPLIFIED_CHINESE)).getAsJsonObject();
        assertTrue(english.get("instructions").getAsString().contains("in English"));
        assertTrue(chinese.get("instructions").getAsString().contains("用简体中文回答"));
        for (var request : new com.google.gson.JsonObject[]{english, chinese}) {
            var instructions = request.get("instructions").getAsString();
            assertTrue(instructions.contains("changedRanges"));
            assertTrue(instructions.contains("endLine"));
            assertTrue(instructions.contains("UFUNCTION"));
            assertEquals("annotate", request.get("task").getAsString());
            assertEquals("项目/订单.java", request.getAsJsonObject("diff").get("file").getAsString());
            assertTrue(request.getAsJsonObject("diff").get("before").getAsString().contains("原始代码"));
        }
        assertEquals(english.get("diff"), chinese.get("diff"));
    }
}
