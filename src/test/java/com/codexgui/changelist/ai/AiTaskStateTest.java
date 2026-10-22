package com.codexgui.changelist.ai;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AiTaskStateTest {
    private final AiOptions options = new AiOptions("", "model", "high", false);
    private List<AiBatchState.Source> sources(String... names) {
        return java.util.Arrays.stream(names).map(name -> new AiBatchState.Source(Path.of(name), null)).toList();
    }
    @Test void reopeningAQueueRestoresOriginalScopeRowsSelectionOptionsAndTask() {
        var store = new AiSessionStore(); var batch = store.batchFor(sources("first", "second"));
        var task = new AiTaskState("正在生成"); assertTrue(store.tryBeginBatch(task)); batch.start(task, options);
        batch.row(0, "完成"); batch.row(1, "处理中"); batch.select(1);
        var reopened = store.batchFor(sources("different selection"));
        assertSame(batch, reopened); assertSame(task, reopened.task()); assertTrue(task.running());
        assertEquals("完成", reopened.row(0)); assertEquals("处理中", reopened.row(1));
        assertEquals(1, reopened.selectedIndex()); assertEquals(options, reopened.options());
        assertEquals(sources("first", "second"), reopened.sources());
    }
    @Test void terminalQueueIsRetainedAndAnotherScopeCanCreateANewQueue() {
        var store = new AiSessionStore(); var batch = store.batchFor(sources("a"));
        var task = new AiTaskState("运行"); store.tryBeginBatch(task); batch.start(task, options);
        task.finish(AiTaskState.Outcome.FAILED, "失败原因"); batch.finishRows(); store.finished(task);
        assertSame(batch, store.batchFor(sources("a"))); assertSame(batch, store.batchFor(List.of()));
        assertEquals("失败原因", batch.task().message()); assertEquals("未处理", batch.row(0));
        assertNotSame(batch, store.batchFor(sources("b")));
    }
    @Test void reopeningATerminalQueueRetainsProgressButRefreshesItsVcsTargets() {
        var store = new AiSessionStore();
        var oldTarget = new com.codexgui.changelist.vcs.NativeChanges.DiffTarget(null, null).withOpened(false);
        var newTarget = oldTarget.withOpened(true);
        var batch = store.batchFor(List.of(new AiBatchState.Source(Path.of("a"), oldTarget)));
        var task = new AiTaskState("生成"); store.tryBeginBatch(task); batch.start(task, options); batch.row(0, "完成");
        task.finish(AiTaskState.Outcome.SUCCEEDED, "完成"); store.finished(task);
        assertSame(batch, store.batchFor(List.of(new AiBatchState.Source(Path.of("a"), newTarget))));
        assertSame(newTarget, batch.sources().getFirst().target()); assertEquals("完成", batch.row(0));
    }
    @Test void explicitStopMarksTheCurrentFileCanceledAndLeavesFinishedRowsIntact() {
        var batch = new AiBatchState(sources("done", "current", "pending"));
        var task = new AiTaskState("运行"); batch.start(task, options);
        batch.row(0, "完成"); batch.row(1, "处理中"); task.cancel();
        task.progress("不能覆盖取消状态"); assertTrue(task.message().contains("正在停止"));
        task.finish(AiTaskState.Outcome.SUCCEEDED, "不能覆盖取消结果"); batch.finishRows();
        assertEquals(AiTaskState.Outcome.CANCELED, task.outcome());
        assertEquals("完成", batch.row(0)); assertEquals("已取消", batch.row(1)); assertEquals("未处理", batch.row(2));
    }
    @Test void explicitStopNotifiesTheWorkerOnceAndUpdatesTheDisplayedStatus() {
        var task = new AiTaskState("正在准备 Diff"); var cancellations = new AtomicInteger();
        task.onCancel(cancellations::incrementAndGet);
        assertTrue(task.displayStatus().contains("关闭窗口后继续运行"));
        task.cancel(); task.cancel();
        assertEquals(1, cancellations.get(), "Stopping must notify the worker's cancellation handler");
        assertTrue(task.displayStatus().contains("正在停止"));
        assertFalse(task.displayStatus().contains("继续运行"));
        task.finish(AiTaskState.Outcome.SUCCEEDED, "迟到的成功结果");
        assertEquals(AiTaskState.Outcome.CANCELED, task.outcome());
        assertEquals("已取消；不会自动继续或重试", task.displayStatus());
    }
    @Test void stoppingBeforeTheWorkerStartsNotifiesItsCancellationHandlerWhenItArrives() {
        var task = new AiTaskState("等待后台任务"); var cancellations = new AtomicInteger();
        task.cancel(); task.onCancel(cancellations::incrementAndGet);
        assertEquals(1, cancellations.get());
        assertTrue(task.canceled());
    }
    @Test void olderCompletionCannotReleaseANewerFileTaskAndProjectDisposalCancelsAllTasks() {
        var store = new AiSessionStore(); var first = new AiTaskState("first"); var second = new AiTaskState("second");
        var other = new AiTaskState("other"); var batch = new AiTaskState("batch"); Path file = Path.of("file.cs");
        assertTrue(store.tryBegin(file, first, options)); assertFalse(store.tryBegin(file, second, options)); store.finished(first);
        assertTrue(store.tryBegin(file, second, options));
        assertTrue(store.tryBegin(Path.of("other.cs"), other, options)); assertTrue(store.tryBeginBatch(batch));
        store.finished(first); assertSame(second, store.activeFile(file).task());
        store.dispose(); assertTrue(second.canceled()); assertTrue(other.canceled()); assertTrue(batch.canceled());
        assertFalse(store.tryBegin(file, new AiTaskState("disposed project"), options));
        assertFalse(store.tryBeginBatch(new AiTaskState("disposed batch")));
    }
    @Test void activeFileContextSurvivesCacheEvictionAndCanBeReadByAReopenedView() {
        var store = new AiSessionStore(); var task = new AiTaskState("生成 file"); store.tryBegin(Path.of("file.cs"), task, options);
        var original = store.session("file:v1"); original.task = task;
        for (int i = 0; i < 100; i++) store.session("other:" + i);
        assertSame(original, store.session("file:v1")); assertSame(task, store.session("file:v1").task);
        task.finish(AiTaskState.Outcome.SUCCEEDED, "完成 · tokens"); store.finished(task);
        assertEquals("完成 · tokens", store.session("file:v1").task.displayStatus());
    }
    @Test void differentDiffFilesRunWithIndependentOptionsAndStoppingOneDoesNotAffectTheOther() {
        var store = new AiSessionStore(); Path firstFile = Path.of("first.cs"), secondFile = Path.of("second.cs");
        var first = new AiTaskState("first"); var second = new AiTaskState("second");
        var otherOptions = new AiOptions("", "other-model", "low", true);
        assertTrue(store.tryBegin(firstFile, first, options));
        assertNull(store.activeFile(secondFile), "Another Diff must remain available for a new request");
        assertTrue(store.tryBegin(secondFile, second, otherOptions));
        assertEquals(options, store.activeFile(firstFile).options());
        assertEquals(otherOptions, store.activeFile(secondFile).options());
        assertFalse(store.tryBegin(Path.of("folder", "..", "first.cs"), new AiTaskState("duplicate"), otherOptions));
        assertTrue(store.cancel(first)); assertTrue(first.canceled()); assertFalse(second.canceled());
        assertFalse(store.tryBegin(firstFile, new AiTaskState("too early"), options), "Wait for the stopped process to exit");
        store.finished(first);
        assertNull(store.activeFile(firstFile)); assertSame(second, store.activeFile(secondFile).task());
        assertFalse(store.cancel(first), "A stale Stop action must not cancel another task");
        assertFalse(second.canceled());
    }
    @Test void batchOnlyClaimsItsCurrentFileAndStoppingTheQueueLeavesOtherDiffsRunning() {
        var store = new AiSessionStore(); var fileTask = new AiTaskState("single"); var batchTask = new AiTaskState("batch");
        Path firstFile = Path.of("first.cs"), secondFile = Path.of("second.cs");
        assertTrue(store.tryBegin(firstFile, fileTask, options)); assertTrue(store.tryBeginBatch(batchTask));
        assertFalse(store.tryBeginBatch(new AiTaskState("another batch")));
        assertFalse(store.tryBegin(firstFile, batchTask, options), "The queue must skip a file already generating elsewhere");
        assertTrue(store.tryBegin(secondFile, batchTask, options));
        store.releaseFile(firstFile, batchTask); assertSame(fileTask, store.activeFile(firstFile).task());
        store.releaseFile(secondFile, batchTask); assertNull(store.activeFile(secondFile));
        assertSame(batchTask, store.activeBatchTask(), "Releasing one file must not release the queue");
        assertTrue(store.cancel(batchTask)); assertFalse(fileTask.canceled()); store.finished(batchTask);
        assertNull(store.activeBatchTask()); assertSame(fileTask, store.activeFile(firstFile).task());
    }
    @Test void moreThanTheCacheLimitOfActiveDiffsCanBeReopenedWithoutLosingTheirTasks() {
        var store = new AiSessionStore();
        var original = new java.util.ArrayList<AiSessionStore.Session>();
        for (int i = 0; i < 80; i++) {
            var task = new AiTaskState("file " + i); assertTrue(store.tryBegin(Path.of(i + ".cs"), task, options));
            var session = store.session("file:" + i); session.task = task; original.add(session);
        }
        for (int i = 0; i < 80; i++) assertSame(original.get(i), store.session("file:" + i));
    }
    @Test void anotherDiffChangingSharedOptionsDoesNotHideACompletedExplanationOrReuseItForNewRequests() {
        var store = new AiSessionStore(); var snapshot = new AiDiffSnapshot("file.cs", "old", "new");
        var original = store.session(options.cacheKey(snapshot)); original.annotations = new AiResponse("first result", List.of());
        var changedOptions = new AiOptions("", "new-model", "low", true);
        assertSame(original, store.displayedSession(snapshot, changedOptions));
        assertNull(store.session(changedOptions.cacheKey(snapshot)).annotations, "A new request still requires an exact cache match");
        assertNull(store.displayedSession(new AiDiffSnapshot("file.cs", "old", "edited"), changedOptions).annotations);
        assertNull(store.displayedSession(new AiDiffSnapshot("other.cs", "old", "new"), changedOptions).annotations);
    }
}
