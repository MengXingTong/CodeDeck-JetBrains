package com.codexgui.changelist.ui;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.ui.ChatFileTransferable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeListFileTransferHandlerTest {
    @TempDir Path root;

    @Test void exportsExistingAndDeletedFilesAsChatOnlyReferences() throws Exception {
        for (var deleted : List.of(false, true)) {
            var path = root.resolve(deleted ? "Deleted.cpp" : "文件 with space.cpp");
            if (!deleted) Files.writeString(path, "text");
            var file = new ChangedFile("id", path, deleted ? ChangedFile.Kind.DELETED : ChangedFile.Kind.MODIFIED, false);
            var tree = selected(new ChangeTree.Node("file/id", path.getFileName().toString(), file, path, List.of()));
            var handler = new ChangeListFileTransferHandler(root);
            assertEquals(TransferHandler.COPY, handler.getSourceActions(tree));
            var transfer = handler.createTransferable(tree);
            assertNotNull(transfer);
            assertEquals(List.of(path), ChatFileTransferable.paths(transfer));
            assertArrayEquals(new DataFlavor[]{ChatFileTransferable.FLAVOR}, transfer.getTransferDataFlavors());
            assertFalse(transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
            assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor));
            assertFalse(transfer.isDataFlavorSupported(DataFlavor.getTextPlainUnicodeFlavor()));
            assertEquals(!deleted, Files.exists(path));
        }
    }

    @Test void groupsAndOutOfProjectPathsCannotBeExported() {
        var outside = root.getParent().resolve("outside.cpp");
        var file = new ChangedFile("outside", outside, ChangedFile.Kind.MODIFIED, false);
        var handler = new ChangeListFileTransferHandler(root);
        for (var node : List.of(new ChangeTree.Node("group/id", "Changes", null, root, List.of()),
            new ChangeTree.Node("group/id/dir/outside", "outside", null, root.resolve("../outside"), List.of()),
            new ChangeTree.Node("file/outside", "outside.cpp", file, outside, List.of()))) {
            var tree = selected(node);
            assertEquals(TransferHandler.NONE, handler.getSourceActions(tree));
            assertNull(handler.createTransferable(tree));
        }
    }

    @Test void exportsDirectoryItselfAndDeduplicatesAcrossChangeGroups() throws Exception {
        var directory = Files.createDirectory(root.resolve("源码 with space"));
        Files.writeString(directory.resolve("File.cpp"), "unchanged");
        var treeRoot = new DefaultMutableTreeNode("root");
        var first = new DefaultMutableTreeNode(new ChangeTree.Node("group/a/dir/src", "src", null, directory, List.of()));
        var second = new DefaultMutableTreeNode(new ChangeTree.Node("group/b/dir/src", "src", null, directory, List.of()));
        treeRoot.add(first); treeRoot.add(second);
        var tree = new JTree(treeRoot);
        tree.setSelectionPaths(new TreePath[]{new TreePath(first.getPath()), new TreePath(second.getPath())});
        var handler = new ChangeListFileTransferHandler(root);
        assertEquals(TransferHandler.COPY, handler.getSourceActions(tree));
        assertEquals(List.of(directory), ChatFileTransferable.paths(handler.createTransferable(tree)));
        assertEquals("unchanged", Files.readString(directory.resolve("File.cpp")));
    }

    private JTree selected(ChangeTree.Node entry) {
        var node = new DefaultMutableTreeNode(entry); var tree = new JTree(node);
        tree.setSelectionPath(new TreePath(node)); return tree;
    }
}
