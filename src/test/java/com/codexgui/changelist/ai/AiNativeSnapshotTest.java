package com.codexgui.changelist.ai;

import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.impl.DiffViewerWrapper;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.UserDataHolderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiNativeSnapshotTest {
    @TempDir Path root;
    @Test void capturesNativeBaselineAndUnsavedDocumentRatherThanDisk() throws Exception {
        Path path = root.resolve("file.cs"); Files.writeString(path, "stale disk");
        var before = new Content("workspace have revision"); var after = new Content("unsaved editor text");
        var request = new SimpleDiffRequest("native", before, after, "have", "local");
        var snapshot = AiNativeSnapshot.read(root, path, request);
        assertEquals("workspace have revision", snapshot.before()); assertEquals("unsaved editor text", snapshot.after());
        assertEquals("stale disk", Files.readString(path)); assertTrue(AiNativeSnapshot.matches(snapshot, request));
        after.text.set("edited again"); assertFalse(AiNativeSnapshot.matches(snapshot, request));
    }
    @Test void refusesOversizedContentsBeforeCopyingThemFromTheEditor() {
        var before = new Content("old"); var after = new Content("x".repeat(120_001));
        var request = new SimpleDiffRequest("large", before, after, "before", "after");
        assertThrows(IllegalArgumentException.class, () -> AiNativeSnapshot.read(root, root.resolve("large.cs"), request));
        assertEquals(0, before.reads.get()); assertEquals(0, after.reads.get());
    }
    @Test void acceptsNativeEmptySideForAddedAndDeletedFilesAndRejectsProjectEscape() {
        var request = new SimpleDiffRequest("added", new EmptyContent(), new Content("new"), "", "local");
        assertEquals("", AiNativeSnapshot.read(root, root.resolve("new.cs"), request).before());
        var deleted = new SimpleDiffRequest("deleted", new Content("removed"), new EmptyContent(), "have", "");
        assertEquals("", AiNativeSnapshot.read(root, root.resolve("old.cs"), deleted).after());
        assertThrows(IllegalArgumentException.class, () -> AiNativeSnapshot.read(root, root.resolve("../other.cs"), request));
    }
    @Test void installingTheHeaderKeepsTheNativeViewerAndDoesNotReadContentsOrRunCli() {
        var before = new Content("old"); var after = new Content("new");
        var request = new SimpleDiffRequest("native", before, after, "before", "after");
        AiDiffViewer.install(root.resolve("file.cs"), request);
        assertNull(request.getUserData(DiffViewerWrapper.KEY), "Keep the native viewer identity and editor input routing");
        assertEquals(1, request.getUserData(DiffUserDataKeys.NOTIFICATION_PROVIDERS).size());
        assertEquals(0, before.reads.get()); assertEquals(0, after.reads.get());
    }
    private static final class Content extends UserDataHolderBase implements DocumentContent {
        private final AtomicReference<String> text;
        private final AtomicInteger reads = new AtomicInteger();
        private final Document document;
        private Content(String value) {
            text = new AtomicReference<>(value);
            document = (Document) Proxy.newProxyInstance(Document.class.getClassLoader(), new Class<?>[]{Document.class}, (p, method, args) -> switch (method.getName()) {
                case "getText" -> { reads.incrementAndGet(); yield text.get(); }
                case "getTextLength" -> text.get().length();
                case "toString" -> "Fixture document";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
        @Override public Document getDocument() { return document; }
        @Override public FileType getContentType() { return null; }
    }
}
