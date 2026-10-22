package com.codexgui.ui;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CodexFeatureCoordinatorTest {
    @Test
    void mergesConfiguredAndRuntimeMcpServersByName() {
        var config = JsonParser.parseString("""
            {"config":{"mcp_servers":{"offline":{"command":"tool","enabled":false},"live":{"url":"http://old"}}}}
            """).getAsJsonObject();
        var statuses = JsonParser.parseString("""
            {"data":[{"name":"live","serverInfo":{"version":"1"},"tools":{"run":{}}},{"name":"managed"}]}
            """).getAsJsonObject();

        var merged = CodexFeatureCoordinator.mergeMcpServers(statuses, config);

        assertEquals(3, merged.size());
        assertEquals("unknown", merged.get(0).getAsJsonObject().get("connectionStatus").getAsString());
        assertFalse(merged.get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertEquals("connected", merged.get(1).getAsJsonObject().get("connectionStatus").getAsString());
        assertTrue(merged.get(2).getAsJsonObject().get("managed").getAsBoolean());
    }

    @Test
    void showsConfigurationBeforeStatusAndKeepsItAfterTimeoutThenRecoversOnRetry() {
        var statuses = new CompletableFuture<JsonObject>();
        var config = JsonParser.parseString("""
            {"config":{"mcp_servers":{"slow":{"command":"slow-server"},"disabled":{"command":"tool","enabled":false}}}}
            """).getAsJsonObject();
        var shown = new ArrayList<JsonArray>();
        var warnings = new ArrayList<String>();
        var result = CodexFeatureCoordinator.readMcpServers(statuses, CompletableFuture.completedFuture(config), shown::add, warnings::add);

        assertFalse(result.isDone());
        assertEquals(2, shown.getFirst().size());
        assertEquals("unknown", shown.getFirst().get(0).getAsJsonObject().get("connectionStatus").getAsString());
        statuses.completeExceptionally(new TimeoutException("Codex RPC 超时：mcpServerStatus/list"));

        var fallback = result.join();
        assertEquals("slow-server", fallback.get(0).getAsJsonObject().getAsJsonObject("config").get("command").getAsString());
        assertFalse(fallback.get(1).getAsJsonObject().get("enabled").getAsBoolean());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("已保留配置"));

        var live = JsonParser.parseString("""
            {"data":[{"name":"slow","serverInfo":{"name":"slow-server"},"tools":{"search":{}}}]}
            """).getAsJsonObject();
        var retried = CodexFeatureCoordinator.readMcpServers(CompletableFuture.completedFuture(live),
            CompletableFuture.completedFuture(config), shown::add, warnings::add).join();
        assertEquals("connected", retried.get(0).getAsJsonObject().get("connectionStatus").getAsString());
        assertTrue(retried.get(0).getAsJsonObject().getAsJsonObject("tools").has("search"));
        assertEquals(1, warnings.size());
    }

    @Test
    void lateConfigurationCannotOverwriteSuccessfulStatusWithUnknown() {
        var config = new CompletableFuture<JsonObject>();
        var shown = new ArrayList<JsonArray>();
        var statuses = JsonParser.parseString("""
            {"data":[{"name":"live","serverInfo":{"name":"live"},"tools":{"run":{}}}]}
            """).getAsJsonObject();
        var result = CodexFeatureCoordinator.readMcpServers(CompletableFuture.completedFuture(statuses), config,
            shown::add, message -> { throw new AssertionError(message); }).thenAccept(shown::add);
        config.complete(JsonParser.parseString("""
            {"config":{"mcp_servers":{"live":{"command":"tool"}}}}
            """).getAsJsonObject());
        result.join();
        assertEquals(2, shown.size());
        assertEquals("connected", shown.getLast().get(0).getAsJsonObject().get("connectionStatus").getAsString());
    }

    @Test
    void keepsRuntimeServersWhenConfigFailsAndReportsTotalFailure() {
        var warnings = new ArrayList<String>();
        var config = CompletableFuture.<JsonObject>failedFuture(new IOException("config unavailable"));
        var statuses = JsonParser.parseString("""
            {"data":[{"name":"live","serverInfo":{"name":"live"}}]}
            """).getAsJsonObject();
        var result = CodexFeatureCoordinator.readMcpServers(CompletableFuture.completedFuture(statuses), config,
            ignored -> {}, warnings::add).join();
        assertEquals("connected", result.get(0).getAsJsonObject().get("connectionStatus").getAsString());
        assertEquals(1, warnings.size());

        var failed = CodexFeatureCoordinator.readMcpServers(CompletableFuture.failedFuture(new TimeoutException("status timeout")),
            config, ignored -> {}, warnings::add);
        assertThrows(CompletionException.class, failed::join);
    }

    @Test
    void validatesMcpConfigurationNames() {
        assertTrue(CodexFeatureCoordinator.validMcpName("docs_server-2"));
        assertFalse(CodexFeatureCoordinator.validMcpName("docs.server"));
        assertFalse(CodexFeatureCoordinator.validMcpName(""));
    }
}
