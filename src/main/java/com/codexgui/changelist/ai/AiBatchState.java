package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.codexgui.changelist.vcs.NativeChanges;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Queue contents and progress survive all of the queue's dialogs. EDT only. */
public final class AiBatchState {
    public record Source(Path path, NativeChanges.DiffTarget target) {}
    private List<Source> sources;
    private final List<String> rows = new ArrayList<>();
    private AiTaskState task;
    private AiOptions options;
    private int selectedIndex;

    AiBatchState(List<Source> sources) {
        this.sources = List.copyOf(sources);
        sources.forEach(ignored -> rows.add(UiMessages.message("待处理")));
    }
    public List<Source> sources() { return sources; }
    String row(int index) { return rows.get(index); }
    void row(int index, String state) { rows.set(index, state); }
    AiTaskState task() { return task; }
    AiOptions options() { return options; }
    boolean running() { return task != null && task.running(); }
    int selectedIndex() { return selectedIndex; }
    void select(int index) { selectedIndex = index; }
    void start(AiTaskState task, AiOptions options) {
        this.task = task; this.options = options;
        for (int i = 0; i < rows.size(); i++) rows.set(i, UiMessages.message("待处理"));
    }
    void refreshSources(List<Source> sources) {
        if (!running() && sameScope(sources)) this.sources = List.copyOf(sources);
    }
    void finishRows() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).equals(UiMessages.message("处理中"))) rows.set(i, task.outcome() == AiTaskState.Outcome.FAILED ? UiMessages.message("失败") : UiMessages.message("已取消"));
            else if (rows.get(i).equals(UiMessages.message("待处理"))) rows.set(i, UiMessages.message("未处理"));
        }
    }
    boolean sameScope(List<Source> candidates) {
        return sources.stream().map(s -> s.path().normalize()).toList()
                .equals(candidates.stream().map(s -> s.path().normalize()).toList());
    }
}
