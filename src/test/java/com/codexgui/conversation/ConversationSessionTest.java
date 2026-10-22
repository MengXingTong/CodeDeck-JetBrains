package com.codexgui.conversation;

import com.codexgui.model.FileReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConversationSessionTest {
    @Test
    void readOnlyHistoryBelongsToItsSessionAndDoesNotCarryIntoANewConversation() {
        var occupied = new ConversationSession(SessionId.of("occupied"));
        var writable = new ConversationSession(SessionId.of("other"));
        occupied.threadId("external-thread");
        occupied.historyReadOnly(true);

        assertTrue(occupied.historyReadOnly());
        assertFalse(writable.historyReadOnly());
        occupied.clearConversation();
        assertNull(occupied.threadId());
        assertFalse(occupied.historyReadOnly());
    }

    @Test
    void retainsRemovedReferenceMetadataUntilTheDraftIsCleared() {
        var session = new ConversationSession(SessionId.of("session"));
        var reference = new FileReference("stable-id", "Source.cpp", Path.of("D:/Project/Source.cpp"), false);

        session.addFileReference(reference);
        session.fileReferences().clear();

        assertEquals(reference, session.knownFileReference(reference.id()));
        session.clearFileReferences();
        assertNull(session.knownFileReference(reference.id()));
    }
}
