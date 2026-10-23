package com.codexgui.ui;

import com.codexgui.model.ConversationEntry;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BridgeEventPresenterTest {
    @Test
    void settingsRefreshDoesNotOverwriteWorkspaceContextWhenCreatingOrActivatingATab() {
        var registry = new SessionRegistry();
        var presenter = new BridgeEventPresenter(null, null, registry, null);
        var settings = new CodexSettingsState.StateData();
        settings.sendOpenedFilePath = true;
        var state = new JsonObject();

        // New/activate both refresh settings while focus is already inside the chat.
        presenter.addBehaviorSettings(state, settings, registry.active());

        assertEquals(true, state.get("sendOpenedFilePath").getAsBoolean());
        for (var key : List.of("activeFile", "activeFileStartLine", "activeFileEndLine", "activeFileSelectionLabel")) {
            assertFalse(state.has(key), "Only an editor context event may replace " + key);
        }
    }

    @Test
    void mapsConversationEntryWithoutDroppingStableMetadata() {
        var entry = new ConversationEntry(
            ConversationEntry.Kind.ASSISTANT,
            "回答",
            "正文",
            "item-1",
            List.of("D:/Project/Main.cpp"),
            1234L
        );

        var json = BridgeEventPresenter.entryJson(entry);

        assertEquals("assistant", json.get("kind").getAsString());
        assertEquals("item-1", json.get("itemId").getAsString());
        assertEquals(1234L, json.get("createdAtEpochMs").getAsLong());
        assertEquals("D:/Project/Main.cpp", json.getAsJsonArray("fileReferencePaths").get(0).getAsString());
    }
}
