package com.codexgui.changelist.ai;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AiAnnotationComponentTest {
    @Test void explanationsHaveNoAddedPrefixAndCanBeSelectedAndCopiedAsPlainText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var copied = new AtomicReference<String>(); var text = component(new AtomicInteger(800), copied::set);
            assertTrue(text.getText().startsWith("删除接口保留完整的 64 位物品 RID\n"));
            assertFalse(text.getText().startsWith("AI")); assertFalse(text.isEditable()); assertTrue(text.isFocusable());
            int start = text.getText().indexOf("RemoveItemByRid");
            text.select(start, start + "RemoveItemByRid".length());
            assertEquals("RemoveItemByRid", text.getSelectedText());
            var clipboard = new Clipboard("annotation test");
            text.getTransferHandler().exportToClipboard(text, clipboard, TransferHandler.COPY);
            try { assertEquals("RemoveItemByRid", clipboard.getData(DataFlavor.stringFlavor)); }
            catch (Exception e) { throw new AssertionError(e); }
            var menu = text.getComponentPopupMenu(); ((JMenuItem) menu.getComponent(0)).doClick();
            assertEquals("RemoveItemByRid", copied.get());
            ((JMenuItem) menu.getComponent(1)).doClick(); assertEquals(text.getText(), copied.get());
            ((JMenuItem) menu.getComponent(3)).doClick(); assertEquals(text.getText(), text.getSelectedText());
            String original = text.getText();
            text.getActionMap().get(DefaultEditorKit.defaultKeyTypedAction)
                    .actionPerformed(new ActionEvent(text, ActionEvent.ACTION_PERFORMED, "x"));
            assertEquals(original, text.getText(), "Typing into an explanation must not modify it or the source");
        });
    }
    @Test void reopeningAHiddenDiffAndResizingItsViewportReflowsTheSelectableText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var viewport = new AtomicInteger(); var text = component(viewport, value -> {});
            var hidden = text.getPreferredSize(); viewport.set(1400);
            var full = text.getPreferredSize();
            assertEquals(1400, full.width); assertTrue(full.height < hidden.height);
            text.setBounds(0, 0, 420, full.height);
            var narrow = text.getPreferredSize(); assertEquals(420, narrow.width); assertTrue(narrow.height > full.height);
            text.setBounds(0, 0, 1400, narrow.height);
            assertEquals(full, text.getPreferredSize());
            assertTrue(text.getLineWrap()); assertTrue(text.getWrapStyleWord());
        });
    }
    @Test void selectableExplanationBackgroundFillsTheAvailableRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var text = component(new AtomicInteger(1200), value -> {});
            var size = text.getPreferredSize(); text.setSize(size);
            var image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { text.paint(graphics); } finally { graphics.dispose(); }
            assertNotEquals(0, image.getRGB(size.width - 1, size.height - 1) >>> 24);
            text.setSize(1, size.height); assertEquals(1, text.getPreferredSize().width);
            assertEquals(1, text.getMinimumSize().width);
        });
    }

    private AiAnnotationComponent component(AtomicInteger viewport, Consumer<String> copy) {
        var component = new JPanel();
        var colors = (EditorColorsScheme) Proxy.newProxyInstance(EditorColorsScheme.class.getClassLoader(),
                new Class<?>[]{EditorColorsScheme.class}, (p, method, args) -> switch (method.getName()) {
                    case "getEditorFontSize" -> 14;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var scrolling = (ScrollingModel) Proxy.newProxyInstance(ScrollingModel.class.getClassLoader(),
                new Class<?>[]{ScrollingModel.class}, (p, method, args) -> switch (method.getName()) {
                    case "getVisibleArea" -> new Rectangle(0, 0, viewport.get(), 600);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var editor = (Editor) Proxy.newProxyInstance(Editor.class.getClassLoader(), new Class<?>[]{Editor.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getColorsScheme" -> colors;
                    case "getScrollingModel" -> scrolling;
                    case "getContentComponent", "getComponent" -> component;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new AiAnnotationComponent(editor, new AiResponse.Annotation("after", 1, 1, "删除接口保留完整的 64 位物品 RID",
                "RemoveItemByRid 的参数从 int32 改为 int64，与物品数据中的 ItemRid 类型一致。从接口层面避免传入 64 位 RID 时丢失高位，降低无法定位或误匹配物品的风险。".repeat(5)), copy);
    }
}
