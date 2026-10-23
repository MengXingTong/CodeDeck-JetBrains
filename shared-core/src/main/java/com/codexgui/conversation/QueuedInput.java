package com.codexgui.conversation;

import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.model.EditorFileContext;

import java.util.List;
import java.util.UUID;

public record QueuedInput(
    String id,
    String text,
    EditorFileContext editorContext,
    String inputText,
    String display,
    List<Attachment> attachments,
    List<FileReference> fileReferences
) {
    public QueuedInput {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        text = text == null ? "" : text;
        inputText = inputText == null ? "" : inputText;
        display = display == null ? "" : display;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
    }

    public QueuedInput(String inputText, String display, List<Attachment> attachments, List<FileReference> fileReferences) {
        this(null, inputText, null, inputText, display, attachments, fileReferences);
    }

    public String editableText() {
        var parts = text.split("\uFFFC", -1);
        var result = new StringBuilder();
        var embedded = Math.min(fileReferences.size(), parts.length - 1);
        for (var index = 0; index < parts.length; index++) {
            result.append(parts[index]);
            if (index < embedded) result.append('@').append(fileReferences.get(index).path().toAbsolutePath().normalize());
        }
        for (var index = embedded; index < fileReferences.size(); index++) {
            if (!result.isEmpty()) result.append('\n');
            result.append('@').append(fileReferences.get(index).path().toAbsolutePath().normalize());
        }
        return result.toString();
    }
}
