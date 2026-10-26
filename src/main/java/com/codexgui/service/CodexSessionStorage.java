package com.codexgui.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Separate native session state; never redirect the desktop's own CODEX_HOME. */
public final class CodexSessionStorage {
    private CodexSessionStorage() {}

    public static Path sharedHome() {
        var configured = System.getenv("CODEX_HOME");
        return (configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(configured)).toAbsolutePath().normalize();
    }

    public static Path home() { return home(sharedHome()); }
    static Path home(Path shared) { return shared.resolve("codedeck").resolve("jetbrains"); }

    public static Path prepare() throws IOException { return prepare(sharedHome()); }

    static synchronized Path prepare(Path shared) throws IOException {
        var target = home(shared);
        Files.createDirectories(target);
        try (var channel = FileChannel.open(target.resolve(".bootstrap.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!Files.exists(target.resolve(".initial-import-complete"))) {
                // Import once. Later edits, logins and token refreshes in either client stay independent.
                for (var name : List.of("config.toml", "auth.json", ".credentials.json", "models_cache.json",
                    "AGENTS.md", "AGENTS.override.md", "instructions.md", "requirements.toml")) {
                    var source = shared.resolve(name);
                    if (Files.isRegularFile(source) && !Files.exists(target.resolve(name))) Files.copy(source, target.resolve(name));
                }
                // Tool assets are reusable; sessions, databases, queues and writer locks are never linked.
                for (var name : List.of("skills", "plugins", "rules", "prompts", "hooks")) {
                    var source = shared.resolve(name);
                    if (Files.isDirectory(source) && !Files.exists(target.resolve(name))) linkDirectory(source, target.resolve(name));
                }
                Files.writeString(target.resolve(".initial-import-complete"), "CodeDeck isolated Codex home\n");
            }
        }
        return target;
    }

    private static void linkDirectory(Path source, Path target) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            Files.createSymbolicLink(target, source.toAbsolutePath());
            return;
        }
        // Directory junctions do not require Windows developer mode or administrator privileges.
        var command = "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path "
            + psString(target.toString()) + " -Value " + psString(source.toString()) + " | Out-Null";
        run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command), null, false);
    }

    private static String psString(String value) { return "'" + value.replace("'", "''") + "'"; }

    public static void saveTitle(String id, String title) throws IOException {
        if (!id.matches("[a-fA-F0-9-]{36}")) return;
        var directory = home().resolve("chat-titles"); Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, "title-", ".tmp");
        try {
            Files.writeString(temporary, title);
            Files.move(temporary, directory.resolve(id + ".txt"), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Path installHistoryBridge(String executable) throws IOException {
        return installHistoryBridge(executable, sharedHome());
    }

    static synchronized Path installHistoryBridge(String executable, Path shared) throws IOException {
        var target = prepare(shared);
        var bridge = target.resolve("history-bridge.jar");
        try (var input = CodexSessionStorage.class.getResourceAsStream("/history-bridge.jar")) {
            if (input == null) throw new IOException("CodeDeck history bridge is missing from the plugin package");
            var temporary = Files.createTempFile(target, "history-bridge-", ".tmp");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!Files.exists(bridge) || Files.mismatch(temporary, bridge) != -1) {
                    Files.move(temporary, bridge, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
        }
        // Keep an existing registration, including an explicit user disablement.
        if (run(ExecutableCommand.build(executable, List.of("mcp", "get", "codedeck_history", "--json")), shared, true) != 0) {
            var java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            run(ExecutableCommand.build(executable, List.of("mcp", "add", "codedeck_history", "--",
                java.toString(), "-jar", bridge.toString(), target.toString())), shared, false);
        }
        return bridge;
    }

    private static int run(List<String> command, Path codexHome, boolean allowFailure) throws IOException {
        var builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (codexHome != null) builder.environment().put("CODEX_HOME", codexHome.toString());
        var process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("CodeDeck history bridge setup timed out");
            }
            if (process.exitValue() != 0 && !allowFailure) throw new IOException("CodeDeck history bridge setup failed (exit " + process.exitValue() + ")");
            return process.exitValue();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("CodeDeck history bridge setup interrupted", error);
        }
    }
}
