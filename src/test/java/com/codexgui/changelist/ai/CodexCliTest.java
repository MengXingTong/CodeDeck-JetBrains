package com.codexgui.changelist.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CodexCliTest {
    @TempDir Path root;
    private final AiOptions options = new AiOptions("", "", "", false);
    @Test void commandUsesStdinReadOnlyAndExplicitFastOffWithoutReplacingModelDefaults() {
        var args = CodexCli.command(List.of("codex"), options, Path.of("schema with space.json"), Path.of("out.json"));
        assertEquals("-", args.getLast()); assertTrue(args.contains("read-only")); assertTrue(args.contains("--ephemeral"));
        assertTrue(args.contains("--skip-git-repo-check")); assertTrue(args.contains("service_tier=\"default\""));
        assertTrue(args.contains("features.fast_mode=false")); assertTrue(args.contains("features.shell_tool=false"));
        assertFalse(args.contains("--model")); assertFalse(args.stream().anyMatch(a -> a.startsWith("model_reasoning_effort=")));
        assertFalse(args.contains("--ignore-user-config"));
    }
    @Test void modelValuesAreSeparateArgumentsAndNeverShellInterpolated() {
        String model = "model $(not-executed) & quoted";
        var args = CodexCli.command(List.of("codex"), new AiOptions("", model, "high", true), Path.of("s"), Path.of("o"));
        assertEquals(model, args.get(args.indexOf("--model") + 1));
        assertTrue(args.contains("model_reasoning_effort=\"high\"")); assertTrue(args.contains("service_tier=\"fast\""));
        assertThrows(IllegalArgumentException.class, () -> CodexCli.command(List.of("codex"), new AiOptions("", "", "x\" injection", false), Path.of("s"), Path.of("o")));
    }
    @Test void fakeCliReceivesExactUnicodeMultilineStdinAndReturnsUsageWithoutUsingCodex() throws Exception {
        var launcher = fixture(); String prompt = "中文代码\n引号 \" \\ $(secret) & <data>\n";
        var result = CodexCli.run(launcher, options, root, prompt, () -> false, Duration.ofSeconds(10));
        assertEquals(prompt, result.json()); assertEquals(321, result.inputTokens());
        assertEquals(120, result.cachedTokens()); assertEquals(42, result.outputTokens());
    }
    @Test void cancellationKillsRunningProcessAndDoesNotRetry() throws Exception {
        var launcher = fixture(); long start = System.nanoTime();
        assertThrows(CancellationException.class, () -> CodexCli.run(launcher, options, root, "SLEEP",
                () -> System.nanoTime() - start > Duration.ofMillis(700).toNanos(), Duration.ofSeconds(20)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(8).toNanos());
        long pid = Long.parseLong(Files.readString(root.resolve("pid.txt")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void cancellationStopsTheCliLauncherAndItsChildProcess() throws Exception {
        var launcher = fixture(); var task = new AiTaskState("运行");
        assertThrows(CancellationException.class, () -> CodexCli.run(launcher, options, root, "SLEEP_WITH_CHILD", () -> {
            if (Files.exists(root.resolve("child-pid.txt"))) task.cancel();
            return task.canceled();
        }, Duration.ofSeconds(10)));
        for (String file : List.of("pid.txt", "child-pid.txt")) {
            long pid = Long.parseLong(Files.readString(root.resolve(file)));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20);
            });
        }
    }
    @Test void alreadyStoppedTaskDoesNotStartAnotherCliProcess() {
        var task = new AiTaskState("等待"); task.cancel();
        assertThrows(CancellationException.class, () -> CodexCli.run(List.of("must-not-start"), options,
                root, "下一文件", task::canceled, Duration.ofSeconds(10)));
        assertFalse(Files.exists(root.resolve("pid.txt")));
    }
    @Test void differentDiffsRunCliProcessesInParallelAndCanBeStoppedIndependently() throws Exception {
        var launcher = fixture(); var store = new AiSessionStore();
        Path firstDirectory = Files.createDirectory(root.resolve("first")), secondDirectory = Files.createDirectory(root.resolve("second"));
        var first = new AiTaskState("first"); var second = new AiTaskState("second");
        assertTrue(store.tryBegin(firstDirectory.resolve("file.cs"), first, options));
        assertTrue(store.tryBegin(secondDirectory.resolve("file.cs"), second, options));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> CodexCli.run(launcher, options, firstDirectory, "SLEEP", first::canceled, Duration.ofSeconds(15)));
            var secondResult = executor.submit(() -> CodexCli.run(launcher, options, secondDirectory, "SLEEP", second::canceled, Duration.ofSeconds(15)));
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!Files.exists(firstDirectory.resolve("pid.txt")) || !Files.exists(secondDirectory.resolve("pid.txt"))) Thread.sleep(20);
                });
                assertTrue(store.cancel(first));
                var stopped = assertThrows(ExecutionException.class, () -> firstResult.get(5, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, stopped.getCause()); store.finished(first);
                assertFalse(secondResult.isDone(), "Stopping the first Diff must leave the second CLI running");
                assertFalse(second.canceled()); assertSame(second, store.activeFile(secondDirectory.resolve("file.cs")).task());
                assertTrue(store.cancel(second));
                assertInstanceOf(CancellationException.class,
                        assertThrows(ExecutionException.class, () -> secondResult.get(5, TimeUnit.SECONDS)).getCause());
            } finally { first.cancel(); second.cancel(); }
        }
    }
    @Test void timeoutAndCliFailureAreReportedWithoutRetry() throws Exception {
        var launcher = fixture();
        var timeout = assertThrows(IOException.class, () -> CodexCli.run(launcher, options, root, "SLEEP", () -> false, Duration.ofMillis(500)));
        assertTrue(timeout.getMessage().contains("超时"));
        var failure = assertThrows(IOException.class, () -> CodexCli.run(launcher, options, root, "FAIL", () -> false, Duration.ofSeconds(10)));
        assertTrue(failure.getMessage().contains("fixture error"));
    }
    private List<String> fixture() throws Exception {
        Path directory = Files.createDirectories(root.resolve("fake cli with spaces"));
        Path target = directory.resolve("com/codexgui/changelist/ai/FakeCodex.class"); Files.createDirectories(target.getParent());
        try (var bytes = FakeCodex.class.getResourceAsStream("FakeCodex.class")) {
            assertNotNull(bytes); Files.copy(bytes, target);
        }
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", directory.toString(), "com.codexgui.changelist.ai.FakeCodex");
    }
}
