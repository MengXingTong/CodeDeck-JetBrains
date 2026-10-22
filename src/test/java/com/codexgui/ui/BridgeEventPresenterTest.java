package com.codexgui.ui;

import com.codexgui.model.ConversationEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BridgeEventPresenterTest {
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
