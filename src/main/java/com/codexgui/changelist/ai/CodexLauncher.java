package com.codexgui.changelist.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Resolve executables without running a shell or reading credentials. */
public final class CodexLauncher {
    private CodexLauncher() {}

    public static List<String> resolve(String configured) throws IOException {
        return resolve(configured, System.getenv(), Path.of(System.getProperty("user.home")),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    static List<String> resolve(String configured, Map<String, String> environment, Path home, boolean windows) throws IOException {
        var directories = searchDirectories(environment, home, windows);
        String selected = unquote(configured == null ? "" : configured.strip());
        if (!selected.isBlank()) {
            try { return launcher(Path.of(selected), directories, environment, windows); }
            catch (InvalidPathException e) { throw new IOException("Codex CLI 路径格式无效：" + selected, e); }
        }
        var candidates = executables("codex", directories, windows);
        var errors = new ArrayList<String>();
        for (Path candidate : candidates) {
            try { return launcher(candidate, directories, environment, windows); }
            catch (IOException e) { errors.add(e.getMessage()); }
        }
        if (!errors.isEmpty()) throw new IOException("未找到可启动的 Codex CLI：\n" + String.join("\n", errors.stream().limit(3).toList()));
        throw new IOException("找不到 Codex CLI；请安装官方 CLI 并执行 codex login，或在侧栏设置中指定路径");
    }

    private static List<String> launcher(Path candidate, List<Path> directories, Map<String, String> environment,
                                          boolean windows) throws IOException {
        Path executable = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) throw new IOException("Codex CLI 路径不存在：" + executable);
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".cmd") && !name.endsWith(".bat") && !name.endsWith(".ps1"))
            return List.of(executable.toString());
        Path parent = executable.getParent();
        Path script = parent.resolve("node_modules/@openai/codex/bin/codex.js");
        if (!Files.isRegularFile(script))
            throw new IOException("Codex npm 启动脚本对应的包文件不存在：" + script + "；请重新安装 CLI 或指定 codex.exe");

        // Use the official npm entry point directly; no script/model/prompt is passed through a shell.
        Path node = parent.resolve(windows ? "node.exe" : "node");
        if (!Files.isRegularFile(node)) {
            var nodes = executables("node", directories, windows).stream()
                    .filter(path -> !windows || path.getFileName().toString().equalsIgnoreCase("node.exe")).toList();
            node = nodes.isEmpty() ? null : nodes.getFirst();
        }
        if (node == null && windows) {
            for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "NVM_SYMLINK")) {
                String directory = env(environment, key);
                if (directory.isBlank()) continue;
                try {
                    Path installed = Path.of(directory).resolve(key.equals("NVM_SYMLINK") ? "node.exe" : "nodejs/node.exe");
                    if (Files.isRegularFile(installed)) { node = installed; break; }
                } catch (InvalidPathException ignored) {}
            }
        }
        if (node != null) return List.of(node.toAbsolutePath().normalize().toString(), script.toString());
        throw new IOException("已找到官方 npm Codex 启动器，但找不到 " + (windows ? "node.exe" : "node")
                + "：" + executable + "；请将 Node.js 加入 Rider 可见的 PATH，或在侧栏设置中指定 codex.exe");
    }

    private static List<Path> searchDirectories(Map<String, String> environment, Path home, boolean windows) {
        var directories = new LinkedHashSet<Path>();
        // Windows exposes 'Path' in getenv(), whose Map lookup is case-sensitive even though getenv("PATH") is not.
        for (String directory : env(environment, "PATH").split(windows ? ";" : java.util.regex.Pattern.quote(File.pathSeparator)))
            addDirectory(directories, directory);
        if (windows) {
            String appData = env(environment, "APPDATA");
            if (!appData.isBlank()) {
                try { addDirectory(directories, Path.of(appData).resolve("npm").toString()); }
                catch (InvalidPathException ignored) {}
            }
        }
        directories.add(home.resolve(".local/bin"));
        return List.copyOf(directories);
    }

    private static void addDirectory(Set<Path> directories, String directory) {
        if (directory.isBlank()) return;
        try { directories.add(Path.of(unquote(directory.strip())).toAbsolutePath().normalize()); }
        catch (InvalidPathException ignored) {}
    }

    private static List<Path> executables(String name, List<Path> directories, boolean windows) {
        var result = new ArrayList<Path>();
        for (Path directory : directories) {
            for (String suffix : windows ? List.of(".exe", ".cmd", ".ps1") : List.of("")) {
                Path candidate = directory.resolve(name + suffix);
                if (Files.isRegularFile(candidate)) result.add(candidate);
            }
        }
        return result;
    }

    private static String env(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value != null) return value;
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    private static String unquote(String path) {
        if (path.length() >= 2 && ((path.startsWith("\"") && path.endsWith("\"")) || (path.startsWith("'") && path.endsWith("'"))))
            return path.substring(1, path.length() - 1);
        return path;
    }
}
