package com.codexgui.changelist.ai;

import com.intellij.openapi.editor.ComponentInlayAlignment;
import com.intellij.openapi.editor.ComponentInlayKt;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.util.ThrowableRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.SwingUtilities;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native editor test; needs the IntelliJ test framework on the test runtime classpath. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_EDITOR_LAYOUT", matches = "1")
class AiAnnotationLayoutIntegrationTest {
    @Test void annotationReservesItsFullHeightBeforeTheNextSourceLine() throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, (ThrowableRunnable<Exception>) () -> {
            var factory = EditorFactory.getInstance();
            var document = factory.createDocument("first(" + "argument, ".repeat(200) + ");\nsecond();\nthird();\n");
            var editor = (EditorEx) factory.createEditor(document);
            try {
                editor.getComponent().setSize(1200, 700);
                layout(editor.getComponent());
                var annotation = new AiAnnotationComponent(editor, new AiResponse.Annotation("after", 1, 1,
                    "新增绕过死亡奖励流程的移除入口", "DespawnNpc 仅在服务端处理有效角色，移除组内记录并销毁角色。".repeat(20)), value -> {});
                var inlay = ComponentInlayKt.addComponentInlay(editor, document.getLineEndOffset(0),
                    new InlayProperties().relatesToPrecedingText(true).showAbove(false), annotation,
                    ComponentInlayAlignment.FIT_VIEWPORT_X_SPAN);
                assertNotNull(inlay);
                for (var width : new int[]{1200, 420, 900}) {
                    editor.getComponent().setSize(width, 700);
                    for (var pass = 0; pass < 4; pass++) layout(editor.getComponent());
                    var bounds = SwingUtilities.convertRectangle(annotation.getParent(), annotation.getBounds(), editor.getContentComponent());
                    int nextLineY = editor.logicalPositionToXY(new LogicalPosition(1, 0)).y;
                    assertTrue(inlay.getHeightInPixels() > 0, "No editor space reserved");
                    assertTrue(bounds.height >= annotation.getPreferredSize().height, "Wrapped text is clipped at width " + width);
                    assertTrue(bounds.y + bounds.height <= nextLineY, "Annotation overlaps source at width " + width
                        + ": annotation=" + bounds + ", next line y=" + nextLineY + ", inlay height=" + inlay.getHeightInPixels());
                }
                int originalHeight = inlay.getHeightInPixels();
                editor.setFontSize(24); annotation.refreshFont();
                for (var pass = 0; pass < 4; pass++) layout(editor.getComponent());
                assertTrue(inlay.getHeightInPixels() > originalHeight, "Zoom must increase the reserved source space");
                var bounds = SwingUtilities.convertRectangle(annotation.getParent(), annotation.getBounds(), editor.getContentComponent());
                assertTrue(bounds.y + bounds.height <= editor.logicalPositionToXY(new LogicalPosition(1, 0)).y);
                editor.getScrollingModel().scrollHorizontally(120);
                for (var pass = 0; pass < 4; pass++) layout(editor.getComponent());
                bounds = SwingUtilities.convertRectangle(annotation.getParent(), annotation.getBounds(), editor.getContentComponent());
                assertEquals(editor.getScrollingModel().getVisibleArea().x + editor.getContentComponent().getInsets().left, bounds.x);
                editor.getCaretModel().moveToOffset(document.getTextLength());
                editor.getFoldingModel().runBatchFoldingOperation(() -> {
                    var fold = editor.getFoldingModel().addFoldRegion(0, document.getLineEndOffset(2), "...");
                    assertNotNull(fold); fold.setExpanded(false);
                });
                for (var pass = 0; pass < 4; pass++) layout(editor.getComponent());
                assertFalse(annotation.isVisible(), "Folded annotations must not float over other source lines");
                var owned = new ArrayList<Inlay<?>>(List.of(inlay));
                AiDiffPanel.disposeInlays(owned);
                assertNull(annotation.getParent(), "Clearing the Diff removes the selectable component");
                assertTrue(owned.isEmpty());
            } finally { factory.releaseEditor(editor); }
        });
    }

    @Test void repeatedDiffRefreshCleansUpAlreadyInvalidatedAnnotationComponents() throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, (ThrowableRunnable<Exception>) () -> {
                var factory = EditorFactory.getInstance();
                var document = factory.createDocument("first();\nsecond();\nthird();\n");
                var editor = factory.createEditor(document);
                try {
                    editor.getComponent().setSize(900, 700); layout(editor.getComponent());
                    int sourceY = editor.logicalPositionToXY(new LogicalPosition(1, 0)).y;
                    for (var generation = 0; generation < 5; generation++) {
                        var annotation = new AiAnnotationComponent(editor, new AiResponse.Annotation("after", 1, 1,
                            "说明", "多行变更说明。".repeat(40)), value -> {});
                        var inlay = ComponentInlayKt.addComponentInlay(editor, document.getLineEndOffset(0),
                            new InlayProperties().showAbove(false), annotation, ComponentInlayAlignment.FIT_VIEWPORT_X_SPAN);
                        assertNotNull(inlay);
                        for (var pass = 0; pass < 4; pass++) layout(editor.getComponent());
                        inlay.dispose(); // Simulate native invalidation before the queued Diff refresh.
                        assertFalse(inlay.isValid());
                        assertNotNull(annotation.getParent(), "Native invalidation alone leaves the Swing host behind");
                        AiDiffPanel.disposeInlays(new ArrayList<Inlay<?>>(List.of(inlay)));
                        assertNull(annotation.getParent(), "Refresh must remove the orphaned component even when the inlay is invalid");
                        assertEquals(sourceY, editor.logicalPositionToXY(new LogicalPosition(1, 0)).y);
                    }
                    assertEquals("first();\nsecond();\nthird();\n", document.getText());
                } finally { factory.releaseEditor(editor); }
            });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
