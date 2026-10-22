package com.codexgui.changelist.ai;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Project-owned state: closing a view never changes a task's lifetime. */
final class AiTaskState {
    enum Outcome { RUNNING, SUCCEEDED, CANCELED, FAILED, STALE }
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();
    private final long started = System.nanoTime();
    private volatile Outcome outcome = Outcome.RUNNING;
    private volatile String message;

    AiTaskState(String message) { this.message = message; }
    boolean running() { return outcome == Outcome.RUNNING; }
    boolean canceled() { return canceled.get(); }
    Outcome outcome() { return outcome; }
    String message() { return message; }
    String displayStatus() {
        if (!running()) return message;
        long seconds = (System.nanoTime() - started) / 1_000_000_000;
        return message + " · 已用时 " + seconds / 60 + "分" + seconds % 60 + "秒"
                + (canceled() ? "" : " · 关闭窗口后继续运行");
    }
    void progress(String message) { if (running() && !canceled()) this.message = message; }
    void onCancel(Runnable action) {
        cancelAction.set(action);
        if (canceled()) runCancelAction();
    }
    void cancel() {
        if (running() && canceled.compareAndSet(false, true)) {
            message = "正在停止 AI 任务…";
            runCancelAction();
        }
    }
    private void runCancelAction() {
        var action = cancelAction.getAndSet(null);
        if (action != null) action.run();
    }
    void finish(Outcome result, String message) {
        if (!running()) return;
        if (result == Outcome.RUNNING) throw new IllegalArgumentException("任务必须以最终状态结束");
        outcome = canceled() ? Outcome.CANCELED : result;
        this.message = outcome == Outcome.CANCELED ? "已取消；不会自动继续或重试" : message;
        cancelAction.set(null);
    }
}
