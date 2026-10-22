package com.codexgui.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShellCommandInputTest {
    @Test void readsNativeAndCodeModeCallsWithSeparateWorkingDirectories() {
        var json = new Gson();
        var cmd = "p4 revert Source\\UI\\Example.cpp";
        assertEquals(List.of(new ShellCommandInput(cmd, "D:\\project")), ShellCommandInput.parse("exec_command",
            json.toJson(Map.of("cmd", cmd, "workdir", "D:\\project"))));
        var source = "await Promise.all([tools.exec_command({cmd:" + json.toJson(cmd)
            + ",workdir:\"D:\\\\project\"}), tools.exec_command({cmd:'git revert HEAD', workdir:'repo'})]);";
        assertEquals(List.of(new ShellCommandInput(cmd, "D:\\project"), new ShellCommandInput("git revert HEAD", "repo")),
            ShellCommandInput.parse("exec", source));
    }

    @Test void skipsComputedCommandsAndMalformedArgumentsWithoutEvaluatingCode() {
        assertTrue(ShellCommandInput.parse("exec", "await tools.exec_command({cmd:computed(),workdir:'repo'})").isEmpty());
        assertTrue(ShellCommandInput.parse("exec_command", "malformed").isEmpty());
        assertTrue(ShellCommandInput.parse("exec", "x".repeat(2 * 1024 * 1024 + 1)).isEmpty());
    }
}
