package com.codexgui.runtime;

import com.codexgui.i18n.Messages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Host independent, concurrent JSON-line RPC transport for the CLI app-server. */
public final class JsonRpcProcess implements AutoCloseable {
    public interface Listener {
        void notification(String method, JsonObject params);
        void serverRequest(JsonElement id, String method, JsonObject params);
        void disconnected(String detail);
    }

    private final Process process;
    private final BufferedWriter output;
    private final Listener listener;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService timeouts =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("rpc-timeouts").factory());
    private volatile boolean closed;

    public JsonRpcProcess(ProcessBuilder builder, Listener listener, Consumer<String> log) throws IOException {
        this.listener = listener;
        process = builder.start();
        output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofPlatform().daemon().name("rpc-stdout").start(this::read);
        Thread.ofPlatform().daemon().name("rpc-stderr").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) log.accept(line);
            } catch (IOException ignored) { }
        });
        process.onExit().thenRun(() -> fail(Messages.message("CLI app-server 已退出")));
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        return request(method, params, Duration.ofSeconds(25));
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params, Duration timeout) {
        if (closed) return CompletableFuture.failedFuture(new IOException(Messages.message("CLI 连接已关闭")));
        var id = sequence.incrementAndGet();
        var future = new CompletableFuture<JsonObject>();
        pending.put(id, future);
        var timer = timeouts.schedule(() -> {
            if (pending.remove(id, future)) future.completeExceptionally(new IOException(Messages.message("CLI 请求超时：") + method));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> { pending.remove(id, future); timer.cancel(false); });
        var message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        try { write(message); } catch (IOException error) { future.completeExceptionally(error); }
        return future;
    }

    public void notify(String method, JsonObject params) throws IOException {
        var message = new JsonObject();
        message.addProperty("method", method);
        if (params != null) message.add("params", params);
        write(message);
    }

    public void respond(JsonElement id, JsonObject result) throws IOException {
        var message = new JsonObject();
        message.add("id", id);
        message.add("result", result);
        write(message);
    }

    public boolean isAlive() { return !closed && process.isAlive(); }

    private synchronized void write(JsonObject message) throws IOException {
        if (closed) throw new IOException(Messages.message("CLI 连接已关闭"));
        output.write(message.toString());
        output.newLine();
        output.flush();
    }

    private void read() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var message = JsonParser.parseString(line).getAsJsonObject();
                if (message.has("method")) {
                    var method = message.get("method").getAsString();
                    var params = message.has("params") && message.get("params").isJsonObject()
                        ? message.getAsJsonObject("params") : new JsonObject();
                    if (message.has("id")) listener.serverRequest(message.get("id"), method, params);
                    else listener.notification(method, params);
                } else if (message.has("id")) {
                    var future = pending.remove(message.get("id").getAsLong());
                    if (future == null) continue;
                    if (message.has("error")) future.completeExceptionally(new IOException(message.get("error").toString()));
                    else future.complete(message.has("result") && message.get("result").isJsonObject()
                        ? message.getAsJsonObject("result") : new JsonObject());
                }
            }
            fail(Messages.message("CLI 连接已断开"));
        } catch (Exception error) { fail(Messages.message("CLI 协议错误：") + error.getMessage()); }
    }

    private synchronized void fail(String detail) {
        if (closed) return;
        closed = true;
        pending.values().forEach(future -> future.completeExceptionally(new IOException(detail)));
        pending.clear();
        timeouts.shutdownNow();
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) process.destroyForcibly();
        listener.disconnected(detail);
    }

    @Override public void close() {
        // Reap descendants as well as Windows npm/cmd launchers.
        process.descendants().forEach(child -> child.destroyForcibly());
        fail(Messages.message("CLI 连接已关闭"));
    }
}
