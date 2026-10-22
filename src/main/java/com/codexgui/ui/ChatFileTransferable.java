package com.codexgui.ui;

import com.codexgui.i18n.UiMessages;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Chat-only paths: ordinary editor/file drop handlers must not open files or insert path text. */
public record ChatFileTransferable(List<Path> paths) implements Transferable {
    public static final DataFlavor FLAVOR = new DataFlavor(
        "application/x-codedeck-file-references;class=java.lang.String", UiMessages.message("CodeDeck 文件引用"));

    public ChatFileTransferable { paths = List.copyOf(paths); }

    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{FLAVOR}; }
    @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return FLAVOR.equals(flavor); }
    @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
        return paths.stream().map(path -> path.toUri().toASCIIString()).collect(Collectors.joining("\n"));
    }

    public static List<Path> paths(Transferable transferable) {
        if (transferable == null || !transferable.isDataFlavorSupported(FLAVOR)) return List.of();
        try {
            return ((String) transferable.getTransferData(FLAVOR)).lines().map(URI::create).map(Path::of).toList();
        } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
            return List.of();
        }
    }
}
