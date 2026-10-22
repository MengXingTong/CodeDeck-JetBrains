package com.codexgui.changelist.ai;

import com.codexgui.conversation.SessionRegistry;
import com.codexgui.i18n.UiMessages;
import com.codexgui.settings.CodexSettingsState;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

final class NativeLocaleTest {
    @Test
    void nativeControlsUseEnglishAndAnnotationCacheSeparatesLanguages() throws Exception {
        var original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertEquals("en", UiMessages.languageTag());
            SwingUtilities.invokeAndWait(() -> {
                var settings = new CodexSettingsState();
                // Simulate a provider saved by a previous Chinese installation.
                settings.activeProvider("codex").name = "Codex 本地配置";
                var panel = new AiOptionsPanel(settings, CodexSettingsState.AnnotationScope.DIFF, List.of(), () -> {});
                var model = (JComboBox<?>) Arrays.stream(panel.getComponents())
                    .filter(component -> "annotationModel".equals(component.getName())).findFirst().orElseThrow();
                assertEquals("CLI default model", model.getSelectedItem());
                assertEquals("AI annotation model", model.getAccessibleContext().getAccessibleName());
                assertTrue(model.getToolTipText().startsWith("Single-file diff annotations"));
                var provider = (JLabel) panel.getComponent(0);
                assertEquals("GPT · Codex local configuration", provider.getText());
                assertEquals("New chat", new SessionRegistry().active().title());
            });
            var snapshot = new AiDiffSnapshot("项目/文件.java", "before", "after");
            var options = new AiOptions("codex", "test-model", "high", false);
            var english = options.cacheKey(snapshot);
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertNotEquals(english, options.cacheKey(snapshot));
            assertEquals("zh-CN", UiMessages.languageTag());
        } finally {
            Locale.setDefault(original);
        }
    }
}
