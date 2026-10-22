package com.codexgui.ui;

import com.codexgui.model.EditorFileContext;
import com.intellij.diff.util.Side;
import com.intellij.openapi.editor.Document;
import java.util.ArrayList;
import java.util.List;

/** Reads native editor text and maps unified rows back to original file lines. Never edits documents. */
final class DiffSelectionContext {
    @FunctionalInterface interface LineMapper { int originalLine(Side side, int unifiedLine); }
    private DiffSelectionContext() {}

    static EditorFileContext side(String path, Document document, int start, int end, Side side) {
        if (start >= end) return new EditorFileContext(path, 0, 0, "");
        String text = document.getCharsSequence().subSequence(start, end).toString();
        int first = document.getLineNumber(start) + 1, last = document.getLineNumber(end - 1) + 1;
        return new EditorFileContext(path, first, last, text,
                List.of(new EditorFileContext.DiffSelection(name(side), first, last, text)));
    }

    static EditorFileContext unified(String path, Document document, int start, int end, Side preferred, LineMapper mapper) {
        if (start >= end) return new EditorFileContext(path, 0, 0, "");
        var ranges = new ArrayList<EditorFileContext.DiffSelection>();
        var block = new StringBuilder(); Side blockSide = null; int blockStart = 0, blockEnd = 0;
        var text = document.getCharsSequence();
        int first = document.getLineNumber(start), last = document.getLineNumber(end - 1);
        for (int line = first; line <= last; line++) {
            Side side = preferred; int original = mapper.originalLine(side, line);
            if (original < 0) { side = side.other(); original = mapper.originalLine(side, line); }
            // Folding/outdated mappings must not be reported as source-file line numbers.
            if (original < 0) return new EditorFileContext(path, 0, 0, "");
            original++;
            if (blockSide != side || original != blockEnd + 1) {
                if (blockSide != null) ranges.add(new EditorFileContext.DiffSelection(name(blockSide), blockStart, blockEnd, block.toString()));
                block.setLength(0); blockSide = side; blockStart = original;
            }
            blockEnd = original;
            int lineEnd = line + 1 < document.getLineCount() ? document.getLineStartOffset(line + 1) : document.getTextLength();
            block.append(text, Math.max(start, document.getLineStartOffset(line)), Math.min(end, lineEnd));
        }
        ranges.add(new EditorFileContext.DiffSelection(name(blockSide), blockStart, blockEnd, block.toString()));
        boolean singleSide = ranges.stream().map(EditorFileContext.DiffSelection::side).distinct().count() == 1;
        return new EditorFileContext(path, singleSide ? ranges.getFirst().startLine() : 0,
                singleSide ? ranges.getLast().endLine() : 0, text.subSequence(start, end).toString(), ranges);
    }
    private static String name(Side side) { return side == Side.LEFT ? "before" : "after"; }
}
