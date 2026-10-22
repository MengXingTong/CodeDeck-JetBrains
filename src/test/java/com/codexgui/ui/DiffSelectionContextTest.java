package com.codexgui.ui;

import com.codexgui.model.EditorFileContext;
import com.intellij.diff.util.Side;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.DiffVcsDataKeys;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vcs.FilePath;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

final class DiffSelectionContextTest {
    private static final String TEXT = "keep\noldOne\noldTwo\nnewOne\nnewTwo\nlast\n";
    private static final int[] BEFORE = {0, 1, 2, -1, -1, 3, 4}, AFTER = {0, -1, -1, 1, 2, 3, 4};
    private static final DiffSelectionContext.LineMapper MAPPING = (side, line) -> (side == Side.LEFT ? BEFORE : AFTER)[line];

    @Test void unifiedDeletedRowsUseBeforeSourceLinesAndPreservePartialSelection() {
        int start = TEXT.indexOf("oldOne") + 2, end = TEXT.indexOf("oldTwo") + 3;
        var context = DiffSelectionContext.unified("Source/File.cpp", document(TEXT), start, end, Side.RIGHT, MAPPING);
        assertEquals("dOne\nold", context.selectedText());
        assertEquals(List.of(new EditorFileContext.DiffSelection("before", 2, 3, "dOne\nold")), context.diffSelections());
        assertEquals("Source/File.cpp:before:L2-L3", context.displayLabel());
        assertEquals(2, context.startLine()); assertEquals(3, context.endLine());
    }
    @Test void unifiedAddedRowsUseOriginalAfterLinesInsteadOfSyntheticRowNumbers() {
        int start = TEXT.indexOf("newOne"), end = TEXT.indexOf("last");
        var context = DiffSelectionContext.unified("a.cpp", document(TEXT), start, end, Side.RIGHT, MAPPING);
        assertEquals(List.of(new EditorFileContext.DiffSelection("after", 2, 3, "newOne\nnewTwo\n")), context.diffSelections());
        assertEquals(2, context.startLine()); assertEquals(3, context.endLine(), "End at next line start excludes that line");
    }
    @Test void unifiedMixedSelectionSendsBothVersionsSeparately() {
        var context = DiffSelectionContext.unified("a.cpp", document(TEXT), TEXT.indexOf("oldOne"), TEXT.indexOf("last"), Side.RIGHT, MAPPING);
        assertEquals(List.of(
                new EditorFileContext.DiffSelection("before", 2, 3, "oldOne\noldTwo\n"),
                new EditorFileContext.DiffSelection("after", 2, 3, "newOne\nnewTwo\n")), context.diffSelections());
        assertEquals(0, context.startLine()); assertEquals(0, context.endLine(), "Mixed versions have no single source range");
        assertEquals("before:L2-L3, after:L2-L3", context.selectionLabel());
        String input = context.appendTo("解释区别");
        assertTrue(input.contains("[before:L2-L3]\noldOne\noldTwo\n"));
        assertTrue(input.contains("[after:L2-L3]\nnewOne\nnewTwo"));
    }
    @Test void unchangedRowsFollowTheMasterSideAndMixedBlocksKeepSelectionOrder() {
        var context = DiffSelectionContext.unified("a.cpp", document(TEXT), 0, TEXT.indexOf("last") + 4, Side.RIGHT, MAPPING);
        assertEquals(List.of("after:L1", "before:L2-L3", "after:L2-L4"),
                context.diffSelections().stream().map(EditorFileContext.DiffSelection::label).toList());
        var left = DiffSelectionContext.unified("a.cpp", document(TEXT), 0, 4, Side.LEFT, MAPPING);
        assertEquals("before:L1", left.selectionLabel());
    }
    @Test void pureDeletionAndTwoPaneSelectionsKeepTheirOriginalVersion() {
        var deleted = DiffSelectionContext.unified("gone.cpp", document("removed\n"), 0, 7, Side.RIGHT,
                (side, line) -> side == Side.LEFT ? line : -1);
        assertEquals("before:L1", deleted.selectionLabel());
        var twoPane = DiffSelectionContext.side("a.cpp", document("keep\nchanged\nlast"), 7, 13, Side.LEFT);
        assertEquals("before:L2", twoPane.selectionLabel()); assertEquals("anged\n", twoPane.selectedText());
    }
    @Test void missingMappingsAndEmptySelectionsNeverInventSourceLineNumbers() {
        var missing = DiffSelectionContext.unified("a.cpp", document(TEXT), 0, 7, Side.RIGHT, (side, line) -> -1);
        assertFalse(missing.hasSelection()); assertEquals("a.cpp", missing.displayLabel());
        assertFalse(DiffSelectionContext.side("a.cpp", document(TEXT), 3, 3, Side.RIGHT).hasSelection());
    }
    @Test void switchingOrClosingTheEditorRejectsAPreviousDiffSelection() {
        var container = new JPanel(); var editorComponent = new JPanel(); container.add(editorComponent);
        var disposed = new AtomicBoolean();
        var editor = (Editor) Proxy.newProxyInstance(Editor.class.getClassLoader(), new Class<?>[]{Editor.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isDisposed" -> disposed.get();
            case "getComponent" -> editorComponent;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        assertTrue(EditorContextCapture.belongsTo(editor, container));
        assertFalse(EditorContextCapture.belongsTo(editor, new JPanel()));
        disposed.set(true); assertFalse(EditorContextCapture.belongsTo(editor, container));
    }
    @Test void deletedAndRenamedVcsPathsComeFromRevisionMetadataWithoutDiskAccess() {
        var path = (FilePath) Proxy.newProxyInstance(FilePath.class.getClassLoader(), new Class<?>[]{FilePath.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getPath" -> "D:/Project/Source/deleted.cpp";
            case "isNonLocal" -> false;
            default -> throw new AssertionError("Must not resolve files or load content: " + method.getName());
        });
        class Content extends UserDataHolderBase implements DiffContent {
            @Override public com.intellij.openapi.fileTypes.FileType getContentType() { return null; }
        }
        var content = new Content(); content.putUserData(DiffVcsDataKeys.REVISION_INFO, Pair.create(path, null));
        assertEquals("D:/Project/Source/deleted.cpp", EditorContextCapture.contentPath(content));
    }
    private static Document document(String text) {
        var starts = new ArrayList<Integer>(); starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return (Document) Proxy.newProxyInstance(Document.class.getClassLoader(), new Class<?>[]{Document.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getCharsSequence" -> text;
            case "getTextLength" -> text.length();
            case "getLineCount" -> starts.size();
            case "getLineStartOffset" -> starts.get((int) args[0]);
            case "getLineNumber" -> {
                int line = 0; while (line + 1 < starts.size() && starts.get(line + 1) <= (int) args[0]) line++;
                yield line;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
