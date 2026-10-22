package com.codexgui.changelist.ui;

import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.impl.DesktopLayout;
import com.intellij.openapi.wm.impl.UnifiedToolWindowWeights;
import com.intellij.openapi.wm.impl.WindowInfoImpl;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SidebarPlacementTest {
    private WindowInfoImpl window(String id, ToolWindowAnchor anchor, boolean split, int order, String pane) {
        var info = new WindowInfoImpl();
        info.setId(id); info.setAnchor(anchor); info.setSplit(split); info.setOrder(order); info.setToolWindowPaneId(pane);
        return info;
    }

    @Test void movesOldRightButtonAfterLeftUpperButtonsWithoutMovingOtherWindows() {
        var original = new DesktopLayout(Map.of(
                "Project", window("Project", ToolWindowAnchor.LEFT, false, 0, "default"),
                "Structure", window("Structure", ToolWindowAnchor.LEFT, false, 5, "default"),
                "Build", window("Build", ToolWindowAnchor.LEFT, true, 100, "default"),
                "Other pane", window("Other pane", ToolWindowAnchor.LEFT, false, 30, "other"),
                "VCS Changelist", window("VCS Changelist", ToolWindowAnchor.RIGHT, false, 1, "default")),
                new UnifiedToolWindowWeights());
        var updated = SidebarPlacement.leftTopLast(original, "VCS Changelist");
        var button = updated.getInfo("VCS Changelist");
        assertEquals(ToolWindowAnchor.LEFT, button.getAnchor());
        assertFalse(button.isSplit());
        assertEquals(6, button.getOrder());
        assertEquals(ToolWindowAnchor.RIGHT, original.getInfo("VCS Changelist").getAnchor());
        assertEquals(5, updated.getInfo("Structure").getOrder());
        assertEquals(100, updated.getInfo("Build").getOrder());
        assertTrue(updated.getInfo("Build").isSplit());
        assertEquals(30, updated.getInfo("Other pane").getOrder());
    }
}
