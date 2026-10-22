package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** A detachable view of the project's current or most recent explicit batch. */
public final class AiBatchDialog extends DialogWrapper {
    private final Project project;
    private final AiSessionStore store;
    private final AiBatchState state;
    private final Consumer<AiBatchState.Source> open;
    private final AiOptionsPanel options;
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> list = new JList<>(rows);
    private final JLabel status = new JLabel();
    private final Timer timer = new Timer(1000, e -> refresh());
    private final Action start = new AbstractAction(UiMessages.message("开始生成")) {
        @Override public void actionPerformed(ActionEvent event) {
            store.startBatch(project, state, options.options()); refresh();
        }
    };
    private final Action stop = new AbstractAction(UiMessages.message("停止队列")) {
        @Override public void actionPerformed(ActionEvent event) { store.cancel(project, state.task()); refresh(); }
    };
    public AiBatchDialog(Project project, List<AiBatchState.Source> sources, Consumer<AiBatchState.Source> open) {
        super(project, false); this.project = project; this.open = open;
        store = project.getService(AiSessionStore.class); state = store.batchFor(sources);
        options = new AiOptionsPanel(com.codexgui.settings.CodexSettingsState.AnnotationScope.BATCH);
        if (state.running()) options.restore(state.options());
        setTitle(UiMessages.message("AI 注释 · ") + state.sources().size() + UiMessages.message(" 个文件")); setModal(false); setCancelButtonText(UiMessages.message("关闭"));
        state.sources().forEach(ignored -> rows.addElement("")); init();
        list.setSelectedIndex(state.selectedIndex());
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) state.select(list.getSelectedIndex()); });
        project.getMessageBus().connect(getDisposable()).subscribe(AiSessionStore.CHANGED, key -> refresh());
        com.intellij.openapi.util.Disposer.register(getDisposable(), () -> timer.stop());
        refresh(); timer.start();
    }
    private String display(AiBatchState.Source source) { return Path.of(project.getBasePath()).relativize(source.path()).toString(); }
    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(4, 6)); panel.setPreferredSize(new Dimension(900, 420));
        panel.add(options, BorderLayout.NORTH); panel.add(new JBScrollPane(list), BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout()); footer.add(status, BorderLayout.CENTER);
        var show = new JButton(UiMessages.message("查看选中文件 Diff")); show.addActionListener(e -> {
            int index = list.getSelectedIndex(); if (index >= 0) open.accept(state.sources().get(index));
        }); footer.add(show, BorderLayout.EAST); panel.add(footer, BorderLayout.SOUTH); return panel;
    }
    @Override protected Action @NotNull [] createActions() { return new Action[]{start, stop, getCancelAction()}; }
    private void refresh() {
        if (isDisposed() || project.isDisposed()) return;
        for (int i = 0; i < rows.size(); i++) {
            String value = state.row(i) + " · " + display(state.sources().get(i));
            if (!rows.get(i).equals(value)) rows.set(i, value);
        }
        var active = store.activeBatchTask();
        start.setEnabled(active == null && !state.sources().isEmpty());
        stop.setEnabled(state.running() && state.task() == active && !state.task().canceled());
        start.putValue(Action.NAME, state.task() == null ? UiMessages.message("开始生成（") + rows.size() + UiMessages.message(" 个文件）") : UiMessages.message("重新开始（复用缓存）"));
        options.setControlsEnabled(active == null);
        status.setText(state.task() != null ? state.task().displayStatus() : UiMessages.message("点击开始后才会请求 AI；缓存命中或已有任务的文件直接跳过"));
        status.setToolTipText(status.getText());
    }
}
