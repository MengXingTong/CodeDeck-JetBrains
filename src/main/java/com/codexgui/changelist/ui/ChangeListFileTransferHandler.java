package com.codexgui.changelist.ui;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.ui.ChatFileTransferable;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.datatransfer.Transferable;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Export file and directory references exclusively to the chat composer. */
final class ChangeListFileTransferHandler extends TransferHandler {
    private final ProjectScope scope;

    ChangeListFileTransferHandler(Path root) { scope = ProjectScope.of(root); }

    @Override public int getSourceActions(JComponent component) {
        return files(component).isEmpty() ? NONE : COPY;
    }

    @Override protected Transferable createTransferable(JComponent component) {
        var files = files(component);
        if (files.isEmpty()) return null;
        return new ChatFileTransferable(files);
    }

    private List<Path> files(JComponent component) {
        if (!(component instanceof JTree tree) || tree.getSelectionPaths() == null) return List.of();
        var result = new LinkedHashSet<Path>();
        for (var selected : tree.getSelectionPaths()) {
            if (!(selected.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof ChangeTree.Node entry)) continue;
            // 分组节点也有工作区路径，只有真实文件和目录节点可以引用。
            if (entry.file() == null && !entry.id().contains("/dir/")) continue;
            var path = entry.path();
            if (scope.contains(path)) result.add(path.normalize());
        }
        return List.copyOf(result);
    }
}
