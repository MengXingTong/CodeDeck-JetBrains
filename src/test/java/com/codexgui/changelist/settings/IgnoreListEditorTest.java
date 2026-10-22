package com.codexgui.changelist.settings;

import com.codexgui.changelist.core.ProjectScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;

class IgnoreListEditorTest {
    @TempDir Path root;

    @Test void actualEditorPreservesSettingsAndRestoresSelectedIgnoredPath() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var data = new SidebarSettings.Data();
            data.ignoreTemplates.add("UnrealEngine");
            data.ignorePatterns = "*.cache\n";
            data.ignoredPaths.add("Source/Generated/");
            var editor = new IgnoreListEditor(null);
            editor.reset(data);
            assertFalse(editor.isModified(data));
            var controls = descendants(editor);
            var unreal = controls.stream().filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                    .filter(box -> box.getText().equals("UnrealEngine")).findFirst().orElseThrow();
            assertTrue(unreal.isSelected());
            var ignored = controls.stream().filter(JList.class::isInstance).map(JList.class::cast)
                    .filter(list -> list.getModel().getSize() == 1).findFirst().orElseThrow();
            ignored.setSelectedIndex(0);
            var restore = controls.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> button.getText().equals("恢复选中路径的跟踪")).findFirst().orElseThrow();
            assertTrue(restore.isEnabled()); restore.doClick();
            assertTrue(editor.isModified(data));
            var edited = data.copy(); editor.writeTo(edited);
            assertEquals(List.of("UnrealEngine"), edited.ignoreTemplates);
            assertEquals("*.cache\n", edited.ignorePatterns);
            assertTrue(edited.ignoredPaths.isEmpty());
            assertEquals(List.of("Source/Generated/"), edited.restoredPaths);
            assertEquals(List.of("Source/Generated/"), data.ignoredPaths, "Cancel must leave original settings untouched");
        });
    }

    private static List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    @Test void relativeAndAbsolutePathsAreStoredAsLiteralProjectRelativeEntries() {
        var scope = ProjectScope.of(root);
        assertEquals("Source/Generated/", IgnoreListEditor.parsePath(scope, "Source/Generated/"));
        assertEquals("Source/[special] #1.cpp", IgnoreListEditor.parsePath(scope, root.resolve("Source/[special] #1.cpp").toString()));
        assertEquals("Saved/", IgnoreListEditor.parsePath(scope, root.resolve("Saved") + "/"));
    }

    @Test void emptyProjectRootAndOutsidePathsAreRejected() {
        var scope = ProjectScope.of(root);
        for (String path : new String[]{"", ".", "../Other/", root.toString(), root.resolve("../Other").toString()}) {
            assertThrows(IllegalArgumentException.class, () -> IgnoreListEditor.parsePath(scope, path), path);
        }
    }
}
