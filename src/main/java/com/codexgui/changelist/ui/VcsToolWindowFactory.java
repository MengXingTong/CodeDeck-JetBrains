package com.codexgui.changelist.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.ui.content.ContentFactory;
import com.codexgui.changelist.settings.SidebarSettings;
import org.jetbrains.annotations.NotNull;

public final class VcsToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void init(@NotNull ToolWindow toolWindow) {
        var project = toolWindow.getProject();
        var manager = ToolWindowManagerEx.getInstanceEx(project);
        manager.invokeLater(() -> {
            if (project.isDisposed()) return;
            var settings = project.getService(SidebarSettings.class);
            var data = settings.snapshot();
            // Migrate the old saved right-hand placement once; later user moves remain respected.
            if (data.placementVersion >= 1) return;
            manager.setLayout(SidebarPlacement.leftTopLast(manager.getLayout(), toolWindow.getId()));
            data.placementVersion = 1;
            settings.update(project, data);
        });
    }
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        VcsToolWindowPanel panel = new VcsToolWindowPanel(project, toolWindow);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
