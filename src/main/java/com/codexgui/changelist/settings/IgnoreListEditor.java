package com.codexgui.changelist.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.codexgui.changelist.core.IgnoreRules;
import com.codexgui.changelist.core.IgnoreTemplates;
import com.codexgui.changelist.core.ProjectScope;

import javax.swing.*;
import java.awt.*;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Shared by the settings page and the sidebar's directly accessible ignore-list dialog. */
public final class IgnoreListEditor extends JPanel {
    private final Project project;
    private final Map<String, JBCheckBox> templates = new LinkedHashMap<>();
    private final JBTextArea patterns = textArea(true);
    private final DefaultListModel<String> ignored = new DefaultListModel<>(), restored = new DefaultListModel<>();
    private final JBList<String> ignoredList = new JBList<>(ignored), restoredList = new JBList<>(restored);

    public IgnoreListEditor(Project project) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.project = project;
        add(new JBLabel("<html>忽略列表只控制本侧栏的展示，适用于所有变更文件。<br>路径相对于当前项目；目录末尾加 /。恢复跟踪可为模板或父目录添加例外。</html>"), BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        tabs.addTab("GitHub 默认模板", templatePanel());
        JPanel custom = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        custom.add(new JBLabel("Gitignore 语法：每行一条，支持 # 注释、*、**、/ 和 ! 例外；自定义规则排在模板之后。"), BorderLayout.NORTH);
        custom.add(new JBScrollPane(patterns), BorderLayout.CENTER);
        tabs.addTab("自定义规则", custom);
        tabs.addTab("永久忽略路径", pathsPanel(false));
        tabs.addTab("恢复跟踪", pathsPanel(true));
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel templatePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        for (String name : IgnoreTemplates.NAMES) {
            var box = new JBCheckBox(name);
            templates.put(name, box); choices.add(box);
        }
        panel.add(choices, BorderLayout.NORTH);
        JPanel preview = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        JComboBox<String> selected = new JComboBox<>(IgnoreTemplates.NAMES.toArray(String[]::new));
        JBTextArea content = textArea(false);
        content.setText(IgnoreTemplates.content(IgnoreTemplates.NAMES.getFirst()));
        content.setCaretPosition(0);
        selected.addActionListener(event -> {
            content.setText(IgnoreTemplates.content((String) selected.getSelectedItem()));
            content.setCaretPosition(0);
        });
        JPanel heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        heading.add(new JBLabel("预览模板： ")); heading.add(selected);
        preview.add(heading, BorderLayout.NORTH); preview.add(new JBScrollPane(content), BorderLayout.CENTER);
        panel.add(preview, BorderLayout.CENTER);
        panel.add(new JBLabel("来源：github.com/github/gitignore · 内置离线快照 · 所有模板默认不启用"), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel pathsPanel(boolean restore) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(new JBLabel(restore
                ? "这些路径优先于模板与通配规则。更具体的手动忽略路径仍可覆盖父目录的恢复。"
                : "右键忽略的路径永久保存在当前项目；选择路径可恢复跟踪，并添加必要的规则例外。"), BorderLayout.NORTH);
        panel.add(new JBScrollPane(restore ? restoredList : ignoredList), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        JButton add = new JButton(restore ? "恢复指定文件 / 目录…" : "添加忽略路径…");
        add.addActionListener(event -> addPath(restore)); buttons.add(add);
        JButton remove = new JButton(restore ? "移除恢复例外" : "恢复选中路径的跟踪");
        var list = restore ? restoredList : ignoredList;
        remove.setEnabled(false);
        list.addListSelectionListener(event -> remove.setEnabled(!list.isSelectionEmpty()));
        remove.addActionListener(event -> {
            for (String path : list.getSelectedValuesList()) {
                if (!restore) addEquivalent(restored, path);
                removeEquivalent(restore ? restored : ignored, path);
            }
        });
        buttons.add(remove); panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void addPath(boolean restore) {
        String input = Messages.showInputDialog(project,
                "输入项目相对路径（也可粘贴项目内绝对路径）。目录末尾加 /，例如 Content/Generated/。\n路径按字面匹配，不使用通配符。",
                restore ? "恢复跟踪" : "永久忽略路径", null);
        if (input == null) return;
        try {
            String entry = parsePath(ProjectScope.forBasePath(project.getBasePath()), input);
            var model = restore ? restored : ignored;
            var opposite = restore ? ignored : restored;
            removeEquivalent(opposite, entry);
            addEquivalent(model, entry);
            (restore ? restoredList : ignoredList).setSelectedValue(entry, true);
        } catch (IllegalArgumentException error) { Messages.showErrorDialog(project, error.getMessage(), "路径无效"); }
    }

    private static void addEquivalent(DefaultListModel<String> model, String value) {
        if (Collections.list(model.elements()).stream().noneMatch(value::equalsIgnoreCase)) model.addElement(value);
    }

    private static void removeEquivalent(DefaultListModel<String> model, String value) {
        for (int i = model.size() - 1; i >= 0; i--) if (value.equalsIgnoreCase(model.get(i))) model.remove(i);
    }

    static String parsePath(ProjectScope scope, String input) {
        try {
            String value = input.trim().replace('\\', '/');
            if (scope.root() == null || value.isEmpty()) throw new IllegalArgumentException();
            Path path = Path.of(value);
            if (!path.isAbsolute()) path = scope.root().resolve(path);
            return IgnoreRules.literalPath(scope, path, value.endsWith("/"));
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("请输入有效的项目内文件或目录路径");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("只能添加当前项目内的文件或目录，不能忽略项目根目录或项目外路径");
        }
    }

    public void reset(SidebarSettings.Data data) {
        templates.forEach((name, box) -> box.setSelected(data.ignoreTemplates.contains(name)));
        patterns.setText(data.ignorePatterns); patterns.setCaretPosition(0);
        ignored.clear(); ignored.addAll(data.ignoredPaths);
        restored.clear(); restored.addAll(data.restoredPaths);
    }

    public void writeTo(SidebarSettings.Data data) {
        data.ignoreTemplates = new ArrayList<>(templates.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList());
        data.ignorePatterns = patterns.getText();
        data.ignoredPaths = new ArrayList<>(Collections.list(ignored.elements()));
        data.restoredPaths = new ArrayList<>(Collections.list(restored.elements()));
    }

    public boolean isModified(SidebarSettings.Data data) {
        var edited = data.copy(); writeTo(edited);
        return !new HashSet<>(data.ignoreTemplates).equals(new HashSet<>(edited.ignoreTemplates))
                || !data.ignorePatterns.equals(edited.ignorePatterns)
                || !data.ignoredPaths.equals(edited.ignoredPaths) || !data.restoredPaths.equals(edited.restoredPaths);
    }

    private static JBTextArea textArea(boolean editable) {
        var area = new JBTextArea(12, 60);
        area.setEditable(editable);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setBorder(JBUI.Borders.empty(6));
        return area;
    }
}
