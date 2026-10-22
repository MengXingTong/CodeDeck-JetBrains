package com.codexgui.ui;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionId;
import com.codexgui.service.ProjectFileSearch;
import com.intellij.ide.dnd.DnDDropHandler;
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDManager;
import com.intellij.ide.dnd.DnDManagerImpl;
import com.intellij.ide.dnd.DnDNativeTarget;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.serviceContainer.ComponentManagerImpl;
import com.intellij.util.ThrowableRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the IDE's native Transferable routing, not just the source's exported flavors. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_IDE_DND", matches = "1")
class WorkspaceDropIntegrationTest {
    @TempDir Path root;

    @Test void changeListFileTransferReachesComposerAsReferencesAndImageAttachments() throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
            .invoke(null, (ThrowableRunnable<Exception>) () -> {
                for (var name : List.of("文件 with space.cpp", "Deleted.cpp", "preview.png")) {
                    var path = root.resolve(name);
                    boolean deleted = name.equals("Deleted.cpp");
                    if (!deleted) Files.writeString(path, "original");
                    var payload = changeListTransfer(path, deleted);
                    var session = new ConversationSession(new SessionId("drop-test"));
                    var listener = new DropListener();
                    var input = new WorkspaceInputCoordinator(null, null, null, root, () -> session, listener);
                    var target = new JLayeredPane();
                    target.setSize(1000, 800);
                    var parent = Disposer.newDisposable();
                    var manager = new DnDManagerImpl();
                    try {
                        // The fixture normally substitutes a no-op headless manager; exercise the production one.
                        ((ComponentManagerImpl) ApplicationManager.getApplication())
                            .replaceServiceInstance(DnDManager.class, manager, parent);
                        input.updateComposerDropRegion(.1, .6, .9, .95);
                        input.installDropTarget(target, parent);
                        // This is the same manager branch used for Swing TransferHandler / OS file drags.
                        var update = manager.getClass().getDeclaredMethod("updateCurrentEvent", Component.class,
                            Point.class, int.class, DataFlavor[].class, Transferable.class);
                        update.setAccessible(true);
                        var outside = (DnDEvent) update.invoke(manager, target, new Point(500, 200),
                            DnDConstants.ACTION_COPY, payload.getTransferDataFlavors(), payload);
                        assertNotNull(outside, "Standard file transfers must reach the registered target");
                        assertFalse(outside.isDropPossible(), "The transcript must not consume file drops");
                        var inside = (DnDEvent) update.invoke(manager, target, new Point(500, 650),
                            DnDConstants.ACTION_COPY, payload.getTransferDataFlavors(), payload);
                        assertNotNull(inside);
                        assertTrue(inside.isDropPossible(), "A ChangeList file must be accepted in the composer");
                        var handler = target.getClientProperty("DnD Target");
                        assertInstanceOf(DnDNativeTarget.class, handler);
                        assertTrue(((DnDDropHandler.WithResult) handler).tryDrop(inside));
                        assertEquals(List.of("position", "inactive", name.endsWith(".png") ? "image" : "reference"),
                            listener.events.subList(listener.events.size() - 3, listener.events.size()));
                        assertEquals(.5, listener.x);
                        assertEquals(.8125, listener.y);
                        if (name.endsWith(".png")) {
                            assertEquals(List.of(path), session.attachments().stream().map(item -> item.path()).toList());
                            assertTrue(session.fileReferences().isEmpty());
                        } else {
                            assertEquals(List.of(path), session.fileReferences().stream().map(item -> item.path()).toList());
                            assertTrue(session.attachments().isEmpty());
                        }
                        // Repeated drops cannot duplicate references or attachments, or alter source files.
                        assertTrue(((DnDDropHandler.WithResult) handler).tryDrop(inside));
                        assertEquals(1, session.fileReferences().size() + session.attachments().size());
                        assertEquals(!deleted, Files.exists(path));
                        if (!deleted) assertEquals("original", Files.readString(path));
                    } finally {
                        manager.getDropTargetListener().dragExit(new DropTargetEvent(target.getDropTarget().getDropTargetContext()));
                        Disposer.dispose(parent);
                        input.dispose();
                    }
                    assertNull(target.getClientProperty("DnD Target"));
                }
            });
    }

    private Transferable changeListTransfer(Path path, boolean deleted) throws Exception {
        var file = new ChangedFile("file", path, deleted ? ChangedFile.Kind.DELETED : ChangedFile.Kind.MODIFIED, false);
        var node = new DefaultMutableTreeNode(new ChangeTree.Node("file/id", path.getFileName().toString(), file, path, List.of()));
        var tree = new JTree(node);
        tree.setSelectionPath(new TreePath(node));
        var type = Class.forName("com.codexgui.changelist.ui.ChangeListFileTransferHandler");
        var constructor = type.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        var method = type.getDeclaredMethod("createTransferable", JComponent.class);
        method.setAccessible(true);
        return (Transferable) method.invoke(constructor.newInstance(root), tree);
    }

    private static final class DropListener implements WorkspaceInputCoordinator.Listener {
        final List<String> events = new ArrayList<>();
        double x, y;
        @Override public void attachmentsChanged(ConversationSession session) { events.add("image"); }
        @Override public void fileReferencesChanged(ConversationSession session) { events.add("reference"); }
        @Override public void nativeDragChanged(boolean active) { events.add(active ? "active" : "inactive"); }
        @Override public void nativeDrop(double x, double y) { events.add("position"); this.x = x; this.y = y; }
        @Override public void projectFilesLoaded(long requestId, List<ProjectFileSearch.Candidate> files) {}
    }
}
