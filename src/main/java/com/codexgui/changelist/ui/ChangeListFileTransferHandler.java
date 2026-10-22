package com.codexgui.changelist.ui;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ProjectScope;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/** Export ordinary file-list data so both IDE DnD and Chromium can consume ChangeList rows. */
final class ChangeListFileTransferHandler extends TransferHandler {
    private final ProjectScope scope;

    ChangeListFileTransferHandler(Path root) { scope = ProjectScope.of(root); }

    @Override public int getSourceActions(JComponent component) {
        return files(component).isEmpty() ? NONE : COPY;
    }

    @Override protected Transferable createTransferable(JComponent component) {
        var files = files(component);
        if (files.isEmpty()) return null;
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor};
            }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
                return flavor.equals(DataFlavor.javaFileListFlavor) || flavor.equals(DataFlavor.stringFlavor);
            }
            @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (flavor.equals(DataFlavor.javaFileListFlavor)) return files;
                if (flavor.equals(DataFlavor.stringFlavor)) return files.stream().map(File::getAbsolutePath)
                    .collect(Collectors.joining("\n"));
                throw new UnsupportedFlavorException(flavor);
            }
        };
    }

    private List<File> files(JComponent component) {
        if (!(component instanceof JTree tree) || tree.getSelectionPaths() == null) return List.of();
        var result = new LinkedHashSet<File>();
        for (var selected : tree.getSelectionPaths()) {
            if (!(selected.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof ChangeTree.Node entry) || entry.file() == null) continue;
            var path = entry.file().path();
            if (scope.contains(path)) result.add(path.normalize().toFile());
        }
        return List.copyOf(result);
    }
}
