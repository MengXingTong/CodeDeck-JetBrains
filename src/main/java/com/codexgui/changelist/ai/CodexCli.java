package com.codexgui.changelist.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.codexgui.service.CodexProviderConfiguration;
import com.codexgui.service.CodexExecutableResolver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One explicit action = one exec process. Prompts go to stdin, never to a shell command line. */
public final class CodexCli {
    public record Result(String json, long inputTokens, long cachedTokens, long outputTokens) {
        public String usageText() {
            return inputTokens < 0 ? "CLI 未提供 token 统计" : "输入 " + inputTokens + "（缓存 " + cachedTokens + "）· 输出 " + outputTokens + " tokens";
        }
    }
    private static final int MAX_OUTPUT = 2 * 1024 * 1024;
    private CodexCli() {}

    public static Result run(AiOptions options, Path directory, String prompt, BooleanSupplier canceled) throws IOException {
        return run(options, directory, prompt, canceled, Duration.ofMinutes(5));
    }
    static Result run(AiOptions options, Path directory, String prompt, BooleanSupplier canceled, Duration timeout) throws IOException {
        if (canceled.getAsBoolean()) throw new CancellationException();
        return run(resolveLauncher(options.executable()), options, directory, prompt, canceled, timeout);
    }
    static Result run(List<String> launcher, AiOptions options, Path directory, String prompt, BooleanSupplier canceled, Duration timeout) throws IOException {
        if (canceled.getAsBoolean()) throw new CancellationException();
        Path temporary = Files.createTempDirectory("vcs-diff-ai-");
        Path schema = temporary.resolve("schema.json"), answer = temporary.resolve("answer.json");
        Process process = null;
        try {
            Files.writeString(schema, AiResponse.SCHEMA, StandardCharsets.UTF_8);
            var builder = new ProcessBuilder(command(launcher, options, schema, answer)).directory(directory.toFile());
            CodexProviderConfiguration.applyEnvironment(builder, options.provider());
            process = builder.start(); Process child = process;
            var stdout = new OutputCapture(child.getInputStream()); var stderr = new OutputCapture(child.getErrorStream());
            Thread outThread = Thread.startVirtualThread(stdout), errThread = Thread.startVirtualThread(stderr);
            Thread writer = Thread.startVirtualThread(() -> {
                try (var input = child.getOutputStream()) { input.write(prompt.getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ignored) { /* Early CLI errors are reported through stderr and the exit code. */ }
            });
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (canceled.getAsBoolean()) throw new CancellationException();
                if (System.nanoTime() >= deadline) throw new IOException("Codex 请求超时，已停止；请手动重试");
                if (stdout.overflow || stderr.overflow) throw new IOException("CLI 输出过大，已停止；未自动重试");
            }
            writer.join(1000); outThread.join(2000); errThread.join(2000);
            if (canceled.getAsBoolean()) throw new CancellationException();
            if (stdout.overflow || stderr.overflow) throw new IOException("CLI 输出超过 2 MiB");
            if (child.exitValue() != 0) {
                String detail = stderr.text().strip();
                if (detail.isBlank()) detail = stdout.text().strip();
                if (detail.length() > 4000) detail = detail.substring(detail.length() - 4000);
                throw new IOException("Codex CLI 失败（" + child.exitValue() + "）：" + detail + "\n请检查 CLI 登录、模型和参数；未自动重试");
            }
            if (!Files.isRegularFile(answer) || Files.size(answer) > MAX_OUTPUT) throw new IOException("CLI 未返回有效结果文件");
            long input = -1, cached = 0, output = 0;
            for (String line : stdout.text().split("\n")) {
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    if (event.has("type") && event.get("type").getAsString().equals("turn.completed") && event.has("usage")) {
                        var usage = event.getAsJsonObject("usage");
                        input = number(usage, "input_tokens"); cached = number(usage, "cached_input_tokens"); output = number(usage, "output_tokens");
                    }
                    if (event.has("type") && event.get("type").getAsString().equals("turn.failed"))
                        throw new IOException("Codex 返回失败：" + event.get("error"));
                } catch (RuntimeException ignored) { /* Non-event diagnostic lines are not structured output. */ }
            }
            return new Result(Files.readString(answer, StandardCharsets.UTF_8), input, cached, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException();
        } finally {
            if (process != null && process.isAlive()) stop(process);
            try { Files.deleteIfExists(answer); Files.deleteIfExists(schema); Files.deleteIfExists(temporary); }
            catch (IOException ignored) { /* Do not replace a cancellation or a useful CLI error with a cleanup error. */ }
        }
    }
    private static long number(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsLong() : 0; }
    static List<String> command(List<String> launcher, AiOptions options, Path schema, Path answer) {
        var args = new ArrayList<>(launcher);
        args.addAll(CodexProviderConfiguration.arguments(options.provider()));
        args.addAll(List.of("exec", "--json", "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only",
                "--color", "never", "--output-schema", schema.toString(), "--output-last-message", answer.toString(),
                "-c", "approval_policy=\"never\"", "-c", "web_search=\"disabled\""));
        // Do not let normal Codex automation features turn an explanation into edits or extra agent runs.
        for (String feature : List.of("shell_tool", "unified_exec", "multi_agent", "hooks", "apps", "plugins", "browser_use", "computer_use", "image_generation"))
            args.addAll(List.of("-c", "features." + feature + "=false"));
        args.addAll(List.of("-c", "service_tier=\"" + (options.fast() ? "fast" : "default") + "\"",
                "-c", "features.fast_mode=" + options.fast()));
        if (!options.model().isEmpty()) args.addAll(List.of("--model", options.model()));
        if (!options.effort().isEmpty()) {
            if (!options.effort().matches("[a-z]+")) throw new IllegalArgumentException("思考程度无效");
            args.addAll(List.of("-c", "model_reasoning_effort=\"" + options.effort() + "\""));
        }
        args.add("-"); return List.copyOf(args);
    }
    private static void stop(Process process) {
        var children = process.descendants().toList();
        for (var child : children.reversed()) child.destroyForcibly();
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static List<String> resolveLauncher(String configured) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String executable = CodexExecutableResolver.resolve(configured == null || configured.isBlank() ? "codex" : configured, windows);
        return CodexLauncher.resolve(executable.equalsIgnoreCase("codex") ? "" : executable);
    }
    private static final class OutputCapture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean overflow;
        private OutputCapture(InputStream input) { this.input = input; }
        @Override public void run() {
            try (input) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (bytes) {
                        if (bytes.size() + count <= MAX_OUTPUT) bytes.write(buffer, 0, count); else overflow = true;
                    }
                }
            } catch (IOException ignored) {}
        }
        String text() { synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); } }
    }
}
