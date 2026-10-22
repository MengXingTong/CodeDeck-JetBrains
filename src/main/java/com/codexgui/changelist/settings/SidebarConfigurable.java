package com.codexgui.changelist.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;

public final class SidebarConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private JBCheckBox autoRefresh, unversioned, scanUnopened;
    private JSpinner interval;
    private IgnoreListEditor ignores;
    public SidebarConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "ChangeList"; }
    @Override public @Nullable JComponent createComponent() {
        autoRefresh = new JBCheckBox("定时请求 VCS 服务器（默认关闭，可能产生大量流量）");
        unversioned = new JBCheckBox("显示本地新文件");
        scanUnopened = new JBCheckBox("自动扫描 Perforce 本地变更（默认关闭，访问服务器）");
        ignores = new IgnoreListEditor(project);
        interval = new JSpinner(new SpinnerNumberModel(60, 5, 300, 1));
        panel = FormBuilder.createFormBuilder()
                .addComponent(new JLabel("<html>复用 IDE 的版本控制连接。Perforce / Git 等配置在 Version Control 中管理。<br>仅展示、请求刷新当前项目目录内的变更。侧栏始终监听 IDE 变更事件。</html>"))
                .addComponent(autoRefresh).addLabeledComponent("定时刷新间隔（秒）:", interval)
                .addComponent(unversioned).addComponent(scanUnopened)
                .addComponent(new JLabel("<html>默认只监听 IDE 已有的变更事件，不额外启动 VCS 检查。<br>启用自动扫描后，编辑、保存和磁盘变化会查询 have / reconcile，可能下载基准内容；<br>即使关闭定时刷新，这些事件检测仍会访问服务器。全量扫描在启动和手动刷新时执行，<br>定时刷新开启时也会重扫（上次扫描完成后至少间隔 60 秒）。大型工作区请谨慎开启。</html>"))
                .addSeparator()
                .addComponent(new JLabel("<html>AI 注释统一使用 CodeDeck 的 GPT 供应商、模型、思考程度、Fast 和 Codex CLI 设置。<br>在 CodeDeck 对话区配置后，Diff 与批量注释自动使用相同设置；点击生成才会请求模型。</html>"))
                .addComponentFillVertically(ignores, 8).getPanel();
        reset(); return panel;
    }
    @Override public boolean isModified() {
        if (panel == null) return false;
        var data = project.getService(SidebarSettings.class).snapshot();
        return data.autoRefresh != autoRefresh.isSelected() || data.showUnversioned != unversioned.isSelected()
                || data.refreshSeconds != (int) interval.getValue() || data.scanUnopened != scanUnopened.isSelected()
                || ignores.isModified(data);
    }
    @Override public void apply() {
        var data = project.getService(SidebarSettings.class).snapshot(); data.autoRefresh = autoRefresh.isSelected();
        data.showUnversioned = unversioned.isSelected(); data.refreshSeconds = (int) interval.getValue();
        data.scanUnopened = scanUnopened.isSelected(); ignores.writeTo(data);
        project.getService(SidebarSettings.class).update(project, data);
    }
    @Override public void reset() {
        if (panel == null) return;
        var data = project.getService(SidebarSettings.class).snapshot();
        autoRefresh.setSelected(data.autoRefresh); unversioned.setSelected(data.showUnversioned);
        scanUnopened.setSelected(data.scanUnopened); ignores.reset(data);
        interval.setValue(Math.max(5, Math.min(300, data.refreshSeconds)));
    }
    @Override public void disposeUIResources() { panel = null; ignores = null; }
}
