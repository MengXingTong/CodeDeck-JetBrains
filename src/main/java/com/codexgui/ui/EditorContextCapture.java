package com.codexgui.ui;

import com.codexgui.model.EditorFileContext;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.DiffVcsDataKeys;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.ide.DataManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.lang.ref.WeakReference;
import java.nio.file.Path;

/** Keep the actual selected/focused editor when focus moves into the chat tool window. EDT only. */
final class EditorContextCapture {
    private final Project project;
    private final Path root;
    private WeakReference<Editor> lastEditor = new WeakReference<>(null);

    EditorContextCapture(Project project, Path root) { this.project = project; this.root = root.toAbsolutePath().normalize(); }
    void remember(Editor editor) {
        var selected = FileEditorManager.getInstance(project).getSelectedEditor();
        if (editor.getProject() == project && selected != null && belongsTo(editor, selected.getComponent()))
            lastEditor = new WeakReference<>(editor);
    }
    EditorFileContext current() {
        var manager = FileEditorManager.getInstance(project);
        var selected = manager.getSelectedEditor();
        if (selected == null) return null;
        Editor editor = lastEditor.get();
        if (!belongsTo(editor, selected.getComponent())) editor = manager.getSelectedTextEditor();
        if (!belongsTo(editor, selected.getComponent())) editor = null;
        if (editor != null) {
            var data = DataManager.getInstance().getDataContext(editor.getContentComponent());
            var viewer = DiffDataKeys.DIFF_VIEWER.getData(data);
            if (viewer instanceof DiffViewerBase base) {
                Side side;
                if (viewer instanceof UnifiedDiffViewer unified && unified.getEditor() == editor) side = unified.getMasterSide();
                else if (viewer instanceof TwosideTextDiffViewer two && two.getEditors().contains(editor))
                    side = two.getEditor1() == editor ? Side.LEFT : Side.RIGHT;
                else if (viewer instanceof OnesideTextDiffViewer one && one.getEditor() == editor) side = one.getSide();
                else return null;
                var contents = base.getRequest().getContents();
                if (contents.size() != 2) return null;
                String path = contentPath(side.select(contents));
                if (path == null) path = contentPath(side.other().select(contents));
                if (path == null) {
                    var source = base.getRequest().getUserData(com.codexgui.service.DiffSourceContext.SOURCE_PATH);
                    if (source != null) path = source.toString();
                }
                if (path == null) return null;
                String display = displayPath(path);
                if (viewer instanceof UnifiedDiffViewer unified) {
                    if (unified.isStateIsOutOfDate()) return new EditorFileContext(display, 0, 0, "");
                    var selection = editor.getSelectionModel();
                    var captured = DiffSelectionContext.unified(display, editor.getDocument(), selection.getSelectionStart(),
                            selection.getSelectionEnd(), side, unified::transferLineFromOnesideStrict);
                    if (!captured.diffSelections().isEmpty()
                            && captured.diffSelections().stream().map(EditorFileContext.DiffSelection::side).distinct().count() == 1) {
                        var original = captured.diffSelections().getFirst().side().equals("before") ? Side.LEFT : Side.RIGHT;
                        var originalPath = contentPath(original.select(contents));
                        if (originalPath != null) captured = new EditorFileContext(displayPath(originalPath), captured.startLine(),
                                captured.endLine(), captured.selectedText(), captured.diffSelections());
                    }
                    return captured;
                }
                var selection = editor.getSelectionModel();
                return DiffSelectionContext.side(display, editor.getDocument(), selection.getSelectionStart(), selection.getSelectionEnd(), side);
            }
        }
        var file = selected.getFile();
        if (file == null || !file.isInLocalFileSystem()) return null;
        String display = displayPath(file.getPath());
        if (editor == null || FileDocumentManager.getInstance().getFile(editor.getDocument()) != file)
            return new EditorFileContext(display, 0, 0, "");
        var selection = editor.getSelectionModel();
        String text = selection.getSelectedText();
        if (text == null || text.isEmpty()) return new EditorFileContext(display, 0, 0, "");
        return new EditorFileContext(display, editor.getDocument().getLineNumber(selection.getSelectionStart()) + 1,
                editor.getDocument().getLineNumber(selection.getSelectionEnd() - 1) + 1, text);
    }
    static boolean belongsTo(Editor editor, JComponent container) {
        return editor != null && !editor.isDisposed() && SwingUtilities.isDescendingFrom(editor.getComponent(), container);
    }
    private String displayPath(String path) {
        try {
            var file = Path.of(path).toAbsolutePath().normalize();
            return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
        } catch (RuntimeException ignored) { return path; }
    }
    static String contentPath(DiffContent content) {
        // Native revision metadata identifies deleted/renamed paths without resolving or reading a file.
        var revision = content.getUserData(DiffVcsDataKeys.REVISION_INFO);
        if (revision != null && !revision.first.isNonLocal()) return revision.first.getPath();
        if (content instanceof DocumentContent document) {
            var file = document.getHighlightFile();
            if (file == null) file = FileDocumentManager.getInstance().getFile(document.getDocument());
            String path = localPath(file); if (path != null) return path;
        }
        if (content instanceof FileContent file) {
            String path = localPath(file.getFile()); if (path != null) return path;
        }
        return content.getNavigatable() instanceof OpenFileDescriptor descriptor ? localPath(descriptor.getFile()) : null;
    }
    private static String localPath(VirtualFile file) { return file != null && file.isInLocalFileSystem() ? file.getPath() : null; }
}
