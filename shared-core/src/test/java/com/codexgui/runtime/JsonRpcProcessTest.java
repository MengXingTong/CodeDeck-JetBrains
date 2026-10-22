package com.codexgui.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcProcessTest {
    @Test void multiplexesEventsStringApprovalIdsErrorsAndTimeouts() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        try (var rpc = new JsonRpcProcess(new ProcessBuilder(executable.toString(), "-cp",
            System.getProperty("java.class.path"), Fixture.class.getName()), new JsonRpcProcess.Listener() {
            public void notification(String method, JsonObject params) { received.add(method); }
            public void serverRequest(JsonElement id, String method, JsonObject params) { received.add(id.getAsString()); }
            public void disconnected(String detail) { received.add("closed"); }
        }, log -> {})) {
            assertEquals("yes", rpc.request("echo", new JsonObject()).get(5, TimeUnit.SECONDS).get("answer").getAsString());
            assertEquals("notification", received.poll(5, TimeUnit.SECONDS));
            assertEquals("approval-string", received.poll(5, TimeUnit.SECONDS));
            var response = new JsonObject(); response.addProperty("decision", "accept");
            rpc.respond(new com.google.gson.JsonPrimitive("approval-string"), response);
            assertEquals("approved", received.poll(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> rpc.request("error", new JsonObject()).get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> rpc.request("ignore", new JsonObject(), Duration.ofMillis(50)).get(5, TimeUnit.SECONDS));
            var pending = rpc.request("ignore", new JsonObject());
            rpc.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertFalse(rpc.isAlive());
        }
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            var input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                var message = JsonParser.parseString(line).getAsJsonObject();
                if (!message.has("method")) { System.out.println("{\"method\":\"approved\"}"); continue; }
                switch (message.get("method").getAsString()) {
                    case "echo" -> {
                        System.out.println("{\"method\":\"notification\"}");
                        System.out.println("{\"id\":\"approval-string\",\"method\":\"approval\"}");
                        System.out.println("{\"id\":" + message.get("id") + ",\"result\":{\"answer\":\"yes\"}}");
                    }
                    case "error" -> System.out.println("{\"id\":" + message.get("id") + ",\"error\":{\"message\":\"fixture failure\"}}");
                    default -> { }
                }
                System.out.flush();
            }
        }
    }
}
