package com.codexgui.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.codexgui.settings.CodexSettingsState;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.ThrowableRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real VFS and cached editor Documents; disk-only rollback cannot detect this regression. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_IDE_ROLLBACK", matches = "1")
class ConversationChangeTrackerIdeTest {
    @TempDir Path root;

    @Test void singleAndAllFileRevertsRestoreCrlfBytesAndRefreshOpenDocuments() throws Exception {
        onEdt(() -> {
            var tracker = new ConversationChangeTracker(project());
            var manager = FileDocumentManager.getInstance();
            var originals = new LinkedHashMap<Path, byte[]>();
            var changedContents = new LinkedHashMap<Path, byte[]>();
            var editors = new ArrayList<Editor>();
            try {
                for (var name : List.of("NpcCreateComponent.h", "NpcGroupBase.cpp", "NpcGroupBase.h", "NpcCreateComponent.cpp")) {
                    var path = root.resolve(name);
                    // Includes the exact four real before/after files when explicitly supplied for diagnosis.
                    var beforeDirectory = System.getenv("CODEDECK_ROLLBACK_BEFORE");
                    var afterDirectory = System.getenv("CODEDECK_ROLLBACK_AFTER");
                    var original = beforeDirectory == null
                        ? ("// 中文 baseline\r\n\r\n" + name + "\r\n").getBytes(StandardCharsets.UTF_8)
                        : Files.readAllBytes(Path.of(beforeDirectory).resolve(name));
                    var changed = afterDirectory == null ? "changed\r\n".getBytes(StandardCharsets.UTF_8)
                        : Files.readAllBytes(Path.of(afterDirectory).resolve(name));
                    Files.write(path, original);
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                    assertNotNull(file);
                    var document = manager.getDocument(file);
                    assertNotNull(document);
                    editors.add(EditorFactory.getInstance().createEditor(document));
                    assertTrue(tracker.trackBeforeWrite("session", name));
                    WriteAction.run(() -> file.setBinaryContent(changed));
                    manager.reloadFromDisk(document);
                    originals.put(path, original);
                    changedContents.put(path, changed);
                }
                assertEquals(4, tracker.listSummaries("session").size());
                for (var entry : originals.entrySet()) {
                    tracker.revert("session", entry.getKey());
                    assertRestored(entry.getKey(), entry.getValue());
                }
                assertTrue(tracker.listSummaries("session").isEmpty());
                for (var entry : changedContents.entrySet()) {
                    assertTrue(tracker.trackBeforeWrite("session", entry.getKey().toString()));
                    var file = LocalFileSystem.getInstance().findFileByNioFile(entry.getKey());
                    WriteAction.run(() -> file.setBinaryContent(entry.getValue()));
                    manager.reloadFromDisk(manager.getCachedDocument(file));
                }
                assertEquals(4, tracker.listSummaries("session").size());
                tracker.revertAll("session");
                for (var entry : originals.entrySet()) assertRestored(entry.getKey(), entry.getValue());
                assertTrue(tracker.listSummaries("session").isEmpty());
            } finally {
                editors.forEach(EditorFactory.getInstance()::releaseEditor);
                tracker.dispose();
            }
        });
    }

    @Test void restoresBomAndOriginalSeparatorsAfterUnsavedEditorChanges() throws Exception {
        onEdt(() -> {
            var tracker = new ConversationChangeTracker(project());
            try {
                for (var original : List.of("\ufeff中文\r\nsecond\r\n", "first\r\nsecond\nthird\r", "first\nsecond\n")) {
                    var path = root.resolve("编码 空格-" + originalsIndex++ + ".txt");
                    var bytes = original.getBytes(StandardCharsets.UTF_8);
                    Files.write(path, bytes);
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                    assertNotNull(file);
                    var manager = FileDocumentManager.getInstance();
                    var document = manager.getDocument(file);
                    assertNotNull(document);
                    tracker.trackBeforeWrite("session", path.toString());
                    WriteCommandAction.runWriteCommandAction(null, () -> document.setText("unsaved user changes\n"));
                    assertTrue(manager.isDocumentUnsaved(document));
                    tracker.revert("session", path);
                    assertRestored(path, bytes);
                    manager.saveDocument(document);
                    assertArrayEquals(bytes, Files.readAllBytes(path), "A later IDE save must not undo the restoration");
                }
            } finally {
                tracker.dispose();
            }
        });
    }

    private int originalsIndex;

    @Test void liveFormatGuardPreservesDiskStyleAndSkipsUnsavedEditorText() throws Exception {
        onEdt(() -> {
            var tracker = new ConversationChangeTracker(project());
            try {
                var path = root.resolve("guard.txt");
                Files.writeString(path, "\ufeffbefore\r\n");
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                assertNotNull(file);
                var manager = FileDocumentManager.getInstance();
                var document = manager.getDocument(file);
                assertNotNull(document);
                tracker.patchStarted("session", "first", List.of(path.toString()));
                Files.writeString(path, "changed\n");
                tracker.patchCompleted("session", "first");
                assertEquals("\ufeffchanged\r\n", Files.readString(path));
                file.refresh(false, false);
                manager.reloadFromDisk(document);
                tracker.patchStarted("session", "second", List.of(path.toString()));
                WriteCommandAction.runWriteCommandAction(null, () -> document.setText("manual unsaved edit\n"));
                Files.writeString(path, "external change\n");
                tracker.patchCompleted("session", "second");
                assertEquals("manual unsaved edit\n", document.getText());
                assertTrue(manager.isDocumentUnsaved(document));
                assertEquals("external change\n", Files.readString(path));
                // Finish the disposable fixture without leaving an unsaved document in the test application.
                manager.reloadFromDisk(document);
            } finally { tracker.dispose(); }
        });
    }

    private void assertRestored(Path path, byte[] original) throws Exception {
        assertArrayEquals(original, Files.readAllBytes(path), path.toString());
        var file = LocalFileSystem.getInstance().findFileByNioFile(path);
        assertNotNull(file);
        var manager = FileDocumentManager.getInstance();
        var document = manager.getCachedDocument(file);
        assertNotNull(document);
        var text = new String(original, StandardCharsets.UTF_8).replaceFirst("^\ufeff", "")
            .replace("\r\n", "\n").replace('\r', '\n');
        assertEquals(text, document.getText(), "The open editor must show the restored file");
        assertFalse(manager.isDocumentUnsaved(document));
    }

    private Project project() {
        // Only supply the workspace identity; all document, filesystem and write APIs remain real IDE services.
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBasePath" -> root.toString();
                case "getMessageBus" -> ApplicationManager.getApplication().getMessageBus();
                case "isDisposed", "isDefault" -> false;
                case "toString" -> "Rollback fixture project";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    private void onEdt(ThrowableRunnable<Exception> action) throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        var parent = Disposer.newDisposable();
        Class.forName("com.intellij.testFramework.ServiceContainerUtil")
            .getMethod("registerOrReplaceServiceInstance", ComponentManager.class, Class.class, Object.class,
                com.intellij.openapi.Disposable.class)
            .invoke(null, ApplicationManager.getApplication(), CodexSettingsState.class, new CodexSettingsState(), parent);
        try {
            Class.forName("com.intellij.testFramework.EdtTestUtil").getMethod("runInEdtAndWait", ThrowableRunnable.class)
                .invoke(null, action);
        } finally { Disposer.dispose(parent); }
    }
}
