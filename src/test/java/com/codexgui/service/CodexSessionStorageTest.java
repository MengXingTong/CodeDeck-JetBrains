package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodexSessionStorageTest {
    @TempDir Path shared;

    @Test void importsSettingsAndCredentialsOnceWithoutImportingDesktopSessionsOrDatabases() throws Exception {
        Files.writeString(shared.resolve("config.toml"), "model = 'existing'\n");
        Files.writeString(shared.resolve("auth.json"), "fixture-credential");
        Files.writeString(shared.resolve("state_5.sqlite"), "desktop-index");
        Files.createDirectories(shared.resolve("sessions"));
        Files.writeString(shared.resolve("sessions/desktop.jsonl"), "desktop conversation");
        var isolated = CodexSessionStorage.prepare(shared);
        assertEquals(shared.resolve("codedeck/jetbrains"), isolated);
        assertEquals("fixture-credential", Files.readString(isolated.resolve("auth.json")));
        assertFalse(Files.exists(isolated.resolve("sessions")));
        assertFalse(Files.exists(isolated.resolve("state_5.sqlite")));
        Files.writeString(isolated.resolve("config.toml"), "model = 'plugin'\n");
        Files.writeString(shared.resolve("auth.json"), "refreshed-desktop-credential");
        CodexSessionStorage.prepare(shared);
        assertEquals("model = 'plugin'\n", Files.readString(isolated.resolve("config.toml")));
        assertEquals("fixture-credential", Files.readString(isolated.resolve("auth.json")));
        assertEquals("model = 'existing'\n", Files.readString(shared.resolve("config.toml")));
    }

    @Test void reusesSkillAssetsButNeverLinksSessionsOrLocks() throws Exception {
        Files.createDirectories(shared.resolve("skills/personal"));
        Files.writeString(shared.resolve("skills/personal/SKILL.md"), "original skill");
        Files.createDirectories(shared.resolve("thread-writer-locks"));
        var isolated = CodexSessionStorage.prepare(shared);
        assertEquals("original skill", Files.readString(isolated.resolve("skills/personal/SKILL.md")));
        Files.writeString(shared.resolve("skills/personal/SKILL.md"), "updated skill");
        assertEquals("updated skill", Files.readString(isolated.resolve("skills/personal/SKILL.md")));
        assertFalse(Files.exists(isolated.resolve("thread-writer-locks")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEDECK_TEST_CODEX", matches = ".+")
    void registersBundledBridgeWithoutReplacingAnExistingDisabledRegistration() throws Exception {
        var executable = System.getenv("CODEDECK_TEST_CODEX");
        Files.writeString(shared.resolve("config.toml"), "model = 'existing'\n");
        var bridge = CodexSessionStorage.installHistoryBridge(executable, shared);
        assertTrue(Files.size(bridge) > 100_000);
        var config = Files.readString(shared.resolve("config.toml"));
        assertTrue(config.contains("[mcp_servers.codedeck_history]"));
        assertTrue(config.contains("history-bridge.jar"));
        assertEquals("model = 'existing'\n", Files.readString(CodexSessionStorage.home(shared).resolve("config.toml")));
        Files.writeString(shared.resolve("config.toml"), config.replace("[mcp_servers.codedeck_history]", "[mcp_servers.codedeck_history]\nenabled = false"));
        var disabled = Files.readAllBytes(shared.resolve("config.toml"));
        CodexSessionStorage.installHistoryBridge(executable, shared);
        assertArrayEquals(disabled, Files.readAllBytes(shared.resolve("config.toml")));
    }
}
