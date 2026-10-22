package com.codexgui.model;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

public record EditorFileContext(String path, int startLine, int endLine, String selectedText, List<DiffSelection> diffSelections) {
    public record DiffSelection(String side, int startLine, int endLine, String text) {
        public String label() { return side + ":" + lines(startLine, endLine); }
    }
    public EditorFileContext(String path, int startLine, int endLine, String selectedText) {
        this(path, startLine, endLine, selectedText, List.of());
    }
    public EditorFileContext {
        path = Objects.requireNonNullElse(path, "");
        selectedText = Objects.requireNonNullElse(selectedText, "");
        diffSelections = diffSelections == null ? List.of() : List.copyOf(diffSelections);
        if (selectedText.isEmpty()) {
            startLine = 0;
            endLine = 0;
            diffSelections = List.of();
        }
    }

    public boolean hasSelection() {
        return !selectedText.isEmpty();
    }

    public String displayLabel() {
        if (!hasSelection()) return path;
        return path + ":" + selectionLabel();
    }

    public String selectionLabel() {
        if (!hasSelection()) return "";
        if (!diffSelections.isEmpty()) return diffSelections.stream().map(DiffSelection::label).collect(Collectors.joining(", "));
        return lines(startLine, endLine);
    }

    private static String lines(int start, int end) {
        return start == end ? "L" + start : "L" + start + "-L" + end;
    }

    public String appendTo(String userText) {
        var context = new StringBuilder(userText == null ? "" : userText);
        context.append("\n\n[当前编辑器上下文]\n文件: ").append(path);
        if (!diffSelections.isEmpty()) {
            context.append("\nDiff 选区（before 为变更前，after 为变更后；行号对应原始文件）:");
            for (var selection : diffSelections) {
                context.append("\n[").append(selection.label()).append("]\n").append(selection.text());
            }
        } else if (hasSelection()) {
            context.append("\n行号: ").append(startLine);
            if (endLine != startLine) context.append('-').append(endLine);
            context.append("\n选中内容:\n").append(selectedText);
        }
        return context.toString().trim();
    }
}
