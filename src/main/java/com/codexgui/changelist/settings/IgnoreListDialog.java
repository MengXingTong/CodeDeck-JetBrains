package com.codexgui.changelist.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class IgnoreListDialog extends DialogWrapper {
    private final Project project;
    private final IgnoreListEditor editor;

    public IgnoreListDialog(Project project) {
        super(project);
        this.project = project;
        editor = new IgnoreListEditor(project);
        editor.reset(project.getService(SidebarSettings.class).snapshot());
        editor.setPreferredSize(JBUI.size(780, 520));
        setTitle("ChangeList 忽略列表"); setOKButtonText("保存并刷新"); init();
    }

    @Override protected @Nullable JComponent createCenterPanel() { return editor; }
    @Override protected String getDimensionServiceKey() { return "VcsChangelistSidebar.IgnoreList"; }
    @Override protected void doOKAction() {
        var settings = project.getService(SidebarSettings.class);
        var data = settings.snapshot(); editor.writeTo(data); settings.update(project, data);
        super.doOKAction();
    }
}
