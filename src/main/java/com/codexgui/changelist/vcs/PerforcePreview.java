package com.codexgui.changelist.vcs;

import com.codexgui.changelist.core.ProjectScope;
import org.jetbrains.idea.perforce.perforce.ExecResult;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/** Tagged reconcile PREVIEW metadata, never an instruction to open or change workspace files. */
final class PerforcePreview {
    record Entry(Path path, String depotPath, long revision, boolean deleted, boolean binary) {}
    private PerforcePreview() {}

    static String encodePath(Path path) {
        return path.toString().replace('\\', '/').replace("%", "%25")
                .replace("@", "%40").replace("#", "%23").replace("*", "%2A");
    }

    static String[] haveCommand(Path root) {
        return new String[]{"-ztag", "have", encodePath(root) + "/..."};
    }

    static String[] command(Path argumentFile) {
        return new String[]{"-x", argumentFile.toString(), "-ztag", "reconcile", "-n", "-e", "-d"};
    }

    static String[] haveFilesCommand(Path argumentFile) {
        return new String[]{"-x", argumentFile.toString(), "-ztag", "have"};
    }

    static List<Entry> haveEntries(String output, ProjectScope scope, Runnable checkCanceled) {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> record = new HashMap<>();
        var lines = output.lines().iterator();
        while (lines.hasNext()) {
            checkCanceled.run();
            String line = lines.next();
            if (line.startsWith("... depotFile ") && record.containsKey("depotFile")) {
                addHave(entries, record, scope); record.clear();
            }
            if (!line.startsWith("... ")) continue;
            int space = line.indexOf(' ', 4);
            if (space > 4) record.put(line.substring(4, space), line.substring(space + 1));
        }
        addHave(entries, record, scope);
        return List.copyOf(entries);
    }

    private static void addHave(List<Entry> entries, Map<String, String> record, ProjectScope scope) {
        String local = record.get("path"), depot = record.get("depotFile");
        if (local == null || depot == null) return;
        try {
            Path path = Path.of(local).normalize();
            long revision = Long.parseLong(record.getOrDefault("haveRev", "0"));
            if (scope.contains(path) && revision > 0) entries.add(new Entry(path, depot, revision, false, false));
        } catch (InvalidPathException | NumberFormatException ignored) { }
    }

    static String haveErrorsOnly(String stderr) {
        // Targeted 'have' legitimately sees newly created, not-yet-versioned files.
        return errorsOnly(stderr).lines().filter(line -> !line.trim().endsWith(" - file(s) not on client."))
                .reduce((first, next) -> first + "\n" + next).orElse("");
    }

    static List<Path> syncedFiles(String output, ProjectScope scope, Runnable checkCanceled) {
        Set<Path> files = new HashSet<>();
        var lines = output.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            checkCanceled.run();
            if (!line.startsWith("... path ")) continue;
            try {
                Path file = Path.of(line.substring(9)).normalize();
                if (scope.contains(file)) files.add(file);
            } catch (InvalidPathException ignored) { }
        }
        record PlannedFile(Path path, int priority) {}
        return files.stream().map(file -> new PlannedFile(file, priority(scope, file)))
                .sorted(Comparator.comparingInt(PlannedFile::priority).thenComparing(file -> file.path().toString()))
                .map(PlannedFile::path).toList();
    }

    private static int priority(ProjectScope scope, Path file) {
        String relative = scope.root().relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (relative.startsWith("source/")) return 0;
        if (relative.startsWith("config/")) return 1;
        if (relative.contains("/source/")) return 2;
        return relative.endsWith(".uasset") || relative.endsWith(".umap") || relative.endsWith(".ubulk")
                || relative.endsWith(".pak") ? 4 : 3;
    }

    static String errorsOnly(String stderr) {
        // Each -x sub-batch with no differences emits this normal message on stderr, even on exit 0.
        return stderr.lines().filter(line -> !line.trim().equalsIgnoreCase("No file(s) to reconcile."))
                .reduce((first, next) -> first + "\n" + next).orElse("");
    }

    static ExecResult normalizedResult(ExecResult output) {
        return normalizedResult(output, false);
    }

    static ExecResult normalizedResult(ExecResult output, boolean haveFiles) {
        // Native ExecResult.getStderr() prefers its cached StreamGobbler over setStderr().
        // Materialize a detached result so the native validator sees exactly the filtered text.
        // The caller still owns and cleans up the original stream-backed result.
        var normalized = new ExecResult();
        normalized.setExitCode(output.getExitCode());
        normalized.setCharset(output.getCharset());
        normalized.setException(output.getException());
        normalized.setStdout(output.getStdout());
        normalized.setStderr(haveFiles ? haveErrorsOnly(output.getStderr()) : errorsOnly(output.getStderr()));
        return normalized;
    }

    static List<Entry> parse(String output, ProjectScope scope, Runnable checkCanceled) {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> record = new HashMap<>();
        var lines = output.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            checkCanceled.run();
            if (line.startsWith("... depotFile ") && record.containsKey("depotFile")) {
                add(entries, record, scope);
                record.clear();
            }
            if (!line.startsWith("... ")) continue;
            int space = line.indexOf(' ', 4);
            if (space > 4) record.put(line.substring(4, space), line.substring(space + 1));
        }
        add(entries, record, scope);
        return List.copyOf(entries);
    }

    private static void add(List<Entry> entries, Map<String, String> record, ProjectScope scope) {
        String action = record.get("action");
        if (!"edit".equals(action) && !"delete".equals(action)) return;
        String local = record.get("clientFile"), depot = record.get("depotFile");
        if (local == null || depot == null) return;
        try {
            Path path = Path.of(local).normalize();
            long revision = Long.parseLong(record.getOrDefault("workRev", "0"));
            if (!scope.contains(path) || revision <= 0) return;
            String type = record.getOrDefault("type", "");
            entries.add(new Entry(path, depot, revision, "delete".equals(action),
                    type.startsWith("binary") || type.startsWith("ubinary") || type.startsWith("apple")
                            || type.startsWith("resource")));
        } catch (InvalidPathException | NumberFormatException ignored) { }
    }
}
