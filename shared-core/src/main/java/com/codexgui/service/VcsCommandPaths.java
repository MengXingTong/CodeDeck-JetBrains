package com.codexgui.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/** Bounded metadata lookup with the JGit runtime already bundled by the plugin. */
final class VcsCommandPaths {
    private static final Pattern RESTORE = Pattern.compile("(?is)(?:^|[\\s\"';|&/\\\\])(?:git|p4)(?:\\.exe)?[\"']?\\s+[^;|&\\r\\n]*?\\b(?:revert|restore|reset|checkout|sync)\\b");
    private static final Pattern GIT = Pattern.compile("(?i)(?:^|[\\s\"'])git(?:\\.exe)?\\s+(.+)");
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s;|&]+)");

    static boolean restores(String command) { return command != null && RESTORE.matcher(command).find(); }

    static List<Path> resolve(String command, Path cwd, WorkspacePathPolicy policy) {
        var match = GIT.matcher(command == null ? "" : command);
        if (!match.find()) return List.of();
        var tokens = new ArrayList<String>();
        var matcher = TOKEN.matcher(match.group(1));
        while (matcher.find() && tokens.size() < 256)
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        try {
            int index = 0;
            while (index < tokens.size() && tokens.get(index).equals("-C")) {
                if (++index >= tokens.size()) return List.of();
                cwd = cwd.resolve(tokens.get(index++)).normalize();
            }
            cwd = cwd.toAbsolutePath().normalize();
            if ((!cwd.equals(policy.root()) && policy.normalize(cwd) == null) || index >= tokens.size()) return List.of();
            var operation = tokens.get(index++);
            if (!List.of("revert", "restore", "reset", "checkout").contains(operation)) return List.of();
            var builder = new FileRepositoryBuilder().findGitDir(cwd.toFile());
            if (builder.getGitDir() == null) return List.of();
            try (var repository = builder.build(); var walk = new RevWalk(repository)) {
                var root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
                if (!root.equals(policy.root()) && policy.normalize(root) == null) return List.of();
                var names = new LinkedHashSet<String>();
                if (operation.equals("revert")) {
                    String revision = null;
                    while (index < tokens.size()) {
                        var token = tokens.get(index++);
                        if (token.equals("-m") || token.equals("--mainline")) { index++; continue; }
                        if (token.startsWith("--mainline=") || List.of("--no-edit", "--no-commit", "-n", "--edit", "-e").contains(token)) continue;
                        if (token.startsWith("-") || !token.matches("[\\p{L}\\p{N}_./~^@{}+-]+") || token.contains("..") || revision != null) return List.of();
                        revision = token;
                    }
                    if (revision == null) return List.of();
                    var id = repository.resolve(revision + "^{commit}");
                    if (id == null) return List.of();
                    var commit = walk.parseCommit(id);
                    if (commit.getParentCount() == 0) {
                        if (!treeNames(repository, commit.getTree(), names)) return List.of();
                    } else {
                        if (commit.getParentCount() > 16) return List.of();
                        for (var parent : commit.getParents()) {
                            try (var entries = new TreeWalk(repository)) {
                                entries.addTree(walk.parseCommit(parent).getTree()); entries.addTree(commit.getTree());
                                entries.setRecursive(true); entries.setFilter(TreeFilter.ANY_DIFF);
                                while (entries.next()) {
                                    names.add(entries.getPathString());
                                    if (names.size() > 128) return List.of();
                                }
                            }
                        }
                    }
                } else {
                    var cache = repository.readDirCache();
                    if (cache.getEntryCount() > 128) return List.of();
                    for (int entry = 0; entry < cache.getEntryCount(); entry++) names.add(cache.getEntry(entry).getPathString());
                    var head = repository.resolve("HEAD^{commit}");
                    if (head != null && !treeNames(repository, walk.parseCommit(head).getTree(), names)) return List.of();
                }
                var paths = new ArrayList<Path>();
                for (var name : names) {
                    var path = policy.normalize(root.resolve(name));
                    if (path != null) paths.add(path);
                }
                java.util.logging.Logger.getLogger(VcsCommandPaths.class.getName()).fine("Git capture paths: " + paths);
                return List.copyOf(paths);
            }
        } catch (Exception | LinkageError ignored) {
            java.util.logging.Logger.getLogger(VcsCommandPaths.class.getName()).fine("Git capture metadata unavailable: " + ignored);
            return List.of();
        }
    }

    private static boolean treeNames(Repository repository, org.eclipse.jgit.lib.ObjectId tree, LinkedHashSet<String> names) throws java.io.IOException {
        try (var entries = new TreeWalk(repository)) {
            entries.addTree(tree); entries.setRecursive(true);
            while (entries.next()) {
                names.add(entries.getPathString());
                if (names.size() > 128) return false;
            }
            return true;
        }
    }
}
