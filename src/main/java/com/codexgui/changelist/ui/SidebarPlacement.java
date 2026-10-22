package com.codexgui.changelist.ui;

import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.impl.DesktopLayout;
import java.util.Objects;

final class SidebarPlacement {
    private SidebarPlacement() {}

    static DesktopLayout leftTopLast(DesktopLayout original, String id) {
        DesktopLayout copy = original.copy();
        var target = copy.getInfo(id);
        if (target == null) return copy;
        int last = copy.getInfos().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(id))
                .map(java.util.Map.Entry::getValue)
                .filter(info -> info.getAnchor() == ToolWindowAnchor.LEFT && !info.isSplit()
                        && Objects.equals(info.getToolWindowPaneId(), target.getToolWindowPaneId()))
                .mapToInt(info -> info.getOrder()).max().orElse(-1);
        target.setAnchor(ToolWindowAnchor.LEFT);
        target.setSplit(false);
        target.setOrder(last + 1);
        target.setShowStripeButton(true);
        return copy;
    }
}
