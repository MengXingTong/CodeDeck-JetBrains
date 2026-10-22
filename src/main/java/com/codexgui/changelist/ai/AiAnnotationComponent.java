package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.View;
import java.awt.*;
import java.util.function.Consumer;

/** An independent, read-only text document: selecting an explanation never selects or edits source code. */
final class AiAnnotationComponent extends JTextArea {
    private final Editor editor;

    AiAnnotationComponent(Editor editor, AiResponse.Annotation annotation) {
        this(editor, annotation, CopyPasteManager::copyTextToClipboard);
    }
    AiAnnotationComponent(Editor editor, AiResponse.Annotation annotation, Consumer<String> copy) {
        super(annotation.title() + "\n" + annotation.explanation().replace("\r\n", "\n").replace('\r', '\n'));
        this.editor = editor;
        setEditable(false); setFocusable(true); setLineWrap(true); setWrapStyleWord(true);
        refreshFont();
        setBackground(new JBColor(0xEDF5FF, 0x263648)); setForeground(JBColor.foreground());
        setMargin(new Insets(0, 0, 0, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, new JBColor(0x4E86BF, 0x72A5D8)),
                JBUI.Borders.empty(6)));
        setCaretPosition(0);
        getAccessibleContext().setAccessibleName(UiMessages.message("变更说明"));
        var menu = new JPopupMenu();
        var selected = new JMenuItem(UiMessages.message("复制"));
        selected.addActionListener(e -> { if (getSelectedText() != null) copy.accept(getSelectedText()); });
        var all = new JMenuItem(UiMessages.message("复制全部")); all.addActionListener(e -> copy.accept(getText()));
        var selectAll = new JMenuItem(UiMessages.message("全选"));
        selectAll.addActionListener(e -> { requestFocusInWindow(); selectAll(); });
        menu.add(selected); menu.add(all); menu.addSeparator(); menu.add(selectAll);
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) { selected.setEnabled(getSelectionStart() != getSelectionEnd()); }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        setComponentPopupMenu(menu);
    }
    @Override public Dimension getPreferredSize() {
        // The component inlay host assigns the viewport width. Recalculate height after each assignment,
        // including the first layout of a reopened Diff, instead of retaining a hidden editor's width.
        int width = getWidth();
        if (width <= 0 && editor != null) width = editor.getScrollingModel().getVisibleArea().width;
        if (width <= 0) width = JBUI.scale(180);
        var insets = getInsets(); var root = getUI().getRootView(this);
        root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
        int height = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
        return new Dimension(width, Math.max(1, height));
    }
    void refreshFont() {
        var font = new Font(Font.DIALOG, Font.PLAIN, Math.max(11, editor.getColorsScheme().getEditorFontSize() - 1));
        if (!font.equals(getFont())) setFont(font);
    }
    @Override public Dimension getMinimumSize() { return new Dimension(1, 1); }
    @Override public void setBounds(int x, int y, int width, int height) {
        boolean changed = width != getWidth();
        super.setBounds(x, y, width, height);
        if (changed) revalidate();
    }
}
