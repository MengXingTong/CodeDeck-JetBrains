package com.codexgui.changelist.vcs;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PerforceLiveBatchTest {
    @Test void aFileOperationQueriesOnlyItsTargetEvenWithManyPendingDiskEvents() {
        var selected = Path.of("Source/selected.cpp");
        var disk = IntStream.range(0, 1000).mapToObj(i -> Path.of("Content/asset" + i + ".uasset")).toList();
        var editor = List.of(Path.of("Source/editing.cpp"));
        assertEquals(List.of(selected), PerforceLiveChanges.nextBatch(List.of(selected), editor, disk));
        assertEquals(1000, disk.size(), "Unrelated events must remain queued separately");
    }

    @Test void movedFilesVerifyOnlyTheirTwoEndpoints() {
        var before = Path.of("Source/before.cpp");
        var after = Path.of("Source/after.cpp");
        assertEquals(List.of(before, after), PerforceLiveChanges.nextBatch(List.of(before, after),
                List.of(Path.of("Source/another.cpp")), List.of(Path.of("Content/large.uasset"))));
    }

    @Test void ordinaryEventsStillBatchAndPrioritizeEditorChangesAfterTheOperation() {
        var edited = Path.of("Source/editing.cpp");
        var disk = IntStream.range(0, 100).mapToObj(i -> Path.of("Content/asset" + i + ".uasset")).toList();
        var batch = PerforceLiveChanges.nextBatch(List.of(), List.of(edited, edited), disk);
        assertEquals(64, batch.size());
        assertEquals(edited, batch.getFirst());
        assertEquals(64, batch.stream().distinct().count());
    }
}
