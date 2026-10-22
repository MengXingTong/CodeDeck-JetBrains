package com.codexgui.changelist.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CodexLauncherTest {
    @TempDir Path root;

    @Test void windowsPathKeyFindsNpmShimAndNodeInDifferentDirectories() throws Exception {
        Path shim = file("npm/codex.cmd"), script = file("npm/node_modules/@openai/codex/bin/codex.js");
        Path node = file("Program Files/nodejs/node.exe");
        assertEquals(List.of(node.toString(), script.toString()), resolve("",
                Map.of("Path", shim.getParent() + ";\"" + node.getParent() + "\"")));
    }
    @Test void explicitPowershellShimUsesTheSameNpmEntryPoint() throws Exception {
        Path shim = file("npm/codex.ps1"), script = file("npm/node_modules/@openai/codex/bin/codex.js");
        Path node = file("tools/node.exe");
        assertEquals(List.of(node.toString(), script.toString()), resolve(shim.toString(), Map.of("pAtH", node.getParent().toString())));
    }
    @Test void appDataFallbackAlsoIgnoresEnvironmentKeyCase() throws Exception {
        file("roaming/npm/codex.cmd"); Path script = file("roaming/npm/node_modules/@openai/codex/bin/codex.js");
        Path node = file("tools/node.exe");
        assertEquals(List.of(node.toString(), script.toString()), resolve("", Map.of("appdata", root.resolve("roaming").toString(),
                "Path", node.getParent().toString())));
    }
    @Test void nodeBesideTheNpmShimDoesNotNeedPath() throws Exception {
        Path shim = file("npm/codex.cmd"), script = file("npm/node_modules/@openai/codex/bin/codex.js");
        Path node = file("npm/node.exe");
        assertEquals(List.of(node.toString(), script.toString()), resolve(shim.toString(), Map.of()));
    }
    @Test void standardNodeInstallationWorksWhenIdeHasAnOldPath() throws Exception {
        Path shim = file("npm/codex.cmd"), script = file("npm/node_modules/@openai/codex/bin/codex.js");
        Path node = file("Program Files/nodejs/node.exe");
        assertEquals(List.of(node.toString(), script.toString()), resolve(shim.toString(),
                Map.of("programfiles", root.resolve("Program Files").toString())));
    }
    @Test void brokenNpmEntryDoesNotHideALaterWorkingExecutable() throws Exception {
        Path broken = file("old npm/codex.cmd"), nativeCli = file("desktop/codex.exe");
        assertEquals(List.of(nativeCli.toString()), resolve("", Map.of("Path", broken.getParent() + ";" + nativeCli.getParent())));
    }
    @Test void quotedExplicitExecutableWithSpacesDoesNotNeedNode() throws Exception {
        Path nativeCli = file("native tools/codex.exe");
        assertEquals(List.of(nativeCli.toString()), resolve("\"" + nativeCli + "\"", Map.of()));
    }
    @Test void missingNodeIsReportedSeparatelyFromMissingCodexPackage() throws Exception {
        Path shim = file("npm/codex.cmd"); file("npm/node_modules/@openai/codex/bin/codex.js");
        var missingNode = assertThrows(IOException.class, () -> resolve(shim.toString(), Map.of()));
        assertTrue(missingNode.getMessage().contains("找不到 node.exe"));
        Path broken = file("broken/codex.cmd");
        var missingPackage = assertThrows(IOException.class, () -> resolve(broken.toString(), Map.of()));
        assertTrue(missingPackage.getMessage().contains("包文件不存在"));
    }
    @Test void anExplicitInvalidPathDoesNotSilentlyLaunchAnotherCli() throws Exception {
        Path nativeCli = file("desktop/codex.exe");
        assertThrows(IOException.class, () -> resolve(root.resolve("missing.exe").toString(), Map.of("Path", nativeCli.getParent().toString())));
    }
    private List<String> resolve(String configured, Map<String, String> environment) throws IOException {
        return CodexLauncher.resolve(configured, environment, root.resolve("home"), true);
    }
    private Path file(String relative) throws IOException {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, "fixture"); return path;
    }
}
