package com.codexgui.changelist.ai;

import com.codexgui.settings.CodexSettingsState;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.codexgui.settings.CodexSettingsState.AnnotationScope.*;

class AiOptionsPanelTest {
    @Test void editableModelEffortAndFastAreSavedAndPassedToTheNextRequest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = new CodexSettingsState(); var published = new AtomicInteger();
            var panel = new AiOptionsPanel(settings, DIFF, List.of("model-a", "model-b"), published::incrementAndGet);
            combo(panel, "annotationModel").getEditor().setItem("manual-model");
            // Reading options commits typed text even before Swing has dispatched focusLost.
            assertEquals("manual-model", panel.options().model());
            combo(panel, "annotationEffort").setSelectedItem("xhigh");
            check(panel).doClick();
            var options = panel.options();
            var command = CodexCli.command(List.of("codex.exe"), options, Path.of("schema"), Path.of("output"));
            assertEquals("manual-model", command.get(command.indexOf("--model") + 1));
            assertTrue(command.contains("model_reasoning_effort=\"xhigh\""));
            assertTrue(command.contains("service_tier=\"fast\""));
            assertEquals(3, published.get());
            assertTrue(settings.annotationModel(DIFF).customModels.contains("manual-model"));
            assertFalse(settings.activeProvider("codex").customModels.contains("manual-model"));
            assertEquals("", settings.getState().model);
            assertEquals("high", settings.getState().reasoningEffort);
            assertEquals("standard", settings.getState().serviceTier);
            var reopened = new AiOptionsPanel(settings, DIFF, List.of(), () -> fail("opening must not save settings"));
            assertEquals(options, reopened.options());
            assertEquals("manual-model", combo(reopened, "annotationModel").getSelectedItem());
        });
    }
    @Test void privateProviderModelsDoNotOverwriteTheLocalOrClaudeModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = privateProviderSettings();
            var panel = new AiOptionsPanel(settings, DIFF, List.of("local-only"), () -> {});
            var model = combo(panel, "annotationModel");
            assertFalse(items(model).contains("local-only"));
            assertTrue(items(model).contains("team-choice"));
            model.setSelectedItem("team-choice");
            assertEquals("team-choice", panel.options().model());
            assertEquals("local-model", settings.getState().model);
            assertEquals("claude-model", settings.getState().claudeModel);
            assertEquals("team-model", settings.activeProvider("codex").model);
            assertEquals(1, settings.activeProvider("codex").revision);
            model.getEditor().setItem("");
            assertEquals("", panel.options().model(), "blank private model must never inherit the local provider model");
        });
    }
    @Test void runningOptionsStayFrozenUntilTheTaskFinishesAndNeverOverwritePreferences() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = new CodexSettingsState(); settings.setAnnotationOptions(DIFF, "before", "high", false);
            var panel = new AiOptionsPanel(settings, DIFF, List.of(), () -> fail("restoring a task must not save settings"));
            var running = panel.options(); panel.setControlsEnabled(false);
            settings.setAnnotationOptions(DIFF, "after", "ultra", true);
            assertEquals(running, panel.options());
            assertFalse(combo(panel, "annotationModel").isEnabled()); assertFalse(check(panel).isEnabled());
            var reopened = new AiOptionsPanel(settings, DIFF, List.of(), () -> fail("restore must not mutate settings"));
            reopened.restore(running); reopened.setControlsEnabled(false);
            assertEquals(running, reopened.options());
            assertEquals("after", settings.annotationModel(DIFF).model);
            reopened.setControlsEnabled(true);
            assertEquals("after", reopened.options().model()); assertEquals("ultra", reopened.options().effort());
            assertTrue(reopened.options().fast());
        });
    }
    @Test void changesFromChatDoNotAlterAnnotationOptionsOrResetTypedText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = new CodexSettingsState(); var changes = new AtomicInteger();
            settings.setAnnotationOptions(DIFF, "model-a", "medium", false);
            var panel = new AiOptionsPanel(settings, DIFF, List.of("model-a", "model-b"), () -> {});
            panel.onChanged(changes::incrementAndGet);
            settings.setGptInferenceOptions("model-b", "low", true); panel.setControlsEnabled(true);
            assertEquals("model-a", combo(panel, "annotationModel").getSelectedItem());
            assertEquals("medium", combo(panel, "annotationEffort").getSelectedItem()); assertFalse(check(panel).isSelected());
            assertEquals(0, changes.get());
            combo(panel, "annotationModel").getEditor().setItem("still-typing"); panel.setControlsEnabled(true);
            assertEquals("still-typing", combo(panel, "annotationModel").getEditor().getItem());
            assertEquals("still-typing", panel.options().model());
        });
    }
    @Test void switchingProviderBeforeAnEditIsCommittedDoesNotCopyTheOldModelIntoIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = privateProviderSettings(); settings.setActiveProvider("codex", CodexSettingsState.CODEX_LOCAL_PROVIDER_ID);
            var panel = new AiOptionsPanel(settings, DIFF, List.of(), () -> fail("stale provider edit must be discarded"));
            combo(panel, "annotationModel").getEditor().setItem("typed-for-local");
            settings.setActiveProvider("codex", "team");
            assertEquals("team-model", panel.options().model());
            assertEquals("local-model", settings.getState().model);
        });
    }
    @Test void diffAndBatchPanelsSaveIndependentOptionsAndReuseThemWhenReopened() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = new CodexSettingsState();
            settings.setGptInferenceOptions("chat-model", "ultra", true);
            var diff = new AiOptionsPanel(settings, DIFF, List.of(), () -> {});
            var batch = new AiOptionsPanel(settings, BATCH, List.of(), () -> {});
            combo(diff, "annotationModel").setSelectedItem("diff-model");
            combo(diff, "annotationEffort").setSelectedItem("low");
            combo(batch, "annotationModel").setSelectedItem("batch-model");
            combo(batch, "annotationEffort").setSelectedItem("xhigh");
            check(batch).doClick();
            assertEquals("diff-model", diff.options().model());
            assertEquals("low", diff.options().effort());
            assertFalse(diff.options().fast());
            assertEquals("batch-model", batch.options().model());
            assertEquals("xhigh", batch.options().effort());
            assertTrue(batch.options().fast());
            assertEquals(diff.options(), new AiOptionsPanel(settings, DIFF, List.of(), () -> {}).options());
            assertEquals(batch.options(), new AiOptionsPanel(settings, BATCH, List.of(), () -> {}).options());
            assertEquals("chat-model", settings.getState().model);
            assertEquals("ultra", settings.getState().reasoningEffort);
            assertEquals("fast", settings.getState().serviceTier);
            assertFalse(settings.annotationModel(BATCH).customModels.contains("diff-model"));
        });
    }
    private static CodexSettingsState privateProviderSettings() {
        var settings = new CodexSettingsState(); settings.getState().model = "local-model"; settings.getState().claudeModel = "claude-model";
        var profile = new CodexSettingsState.ProviderProfile("team", "codex", "Team", false);
        profile.model = "team-model"; profile.customModels.add("team-choice"); settings.getState().providers.add(profile);
        settings.setActiveProvider("codex", "team");
        settings.setAnnotationOptions(DIFF, "team-model", "high", false);
        return settings;
    }
    private static JComboBox<?> combo(AiOptionsPanel panel, String name) {
        return (JComboBox<?>) Arrays.stream(panel.getComponents()).filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }
    private static JCheckBox check(AiOptionsPanel panel) {
        return (JCheckBox) Arrays.stream(panel.getComponents()).filter(c -> c instanceof JCheckBox).findFirst().orElseThrow();
    }
    private static List<String> items(JComboBox<?> box) {
        return java.util.stream.IntStream.range(0, box.getItemCount()).mapToObj(i -> box.getItemAt(i).toString()).toList();
    }
}
