package com.codexgui.changelist.core;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/** Gitignore rules plus literal per-project path overrides. No filesystem or VFS access. */
public final class IgnoreRules {
    private final ProjectScope scope;
    private final List<FastIgnoreRule> rules;
    private final List<String> excluded, restored;
    private final Map<String, Boolean> directories = new HashMap<>();

    public IgnoreRules(ProjectScope scope, Collection<String> templates, String custom,
                       Collection<String> excluded, Collection<String> restored) {
        this.scope = scope;
        List<FastIgnoreRule> parsed = new ArrayList<>();
        for (String template : templates) addRules(parsed, IgnoreTemplates.content(template));
        addRules(parsed, custom == null ? "" : custom);
        rules = List.copyOf(parsed);
        this.excluded = normalizePaths(excluded);
        this.restored = normalizePaths(restored);
    }

    public static IgnoreRules none(ProjectScope scope) {
        return new IgnoreRules(scope, List.of(), "", List.of(), List.of());
    }

    private static void addRules(List<FastIgnoreRule> rules, String text) {
        text.lines().map(FastIgnoreRule::new).filter(rule -> !rule.isEmpty()).forEach(rules::add);
    }

    public boolean isIgnored(Path path) { return isIgnored(path, false); }

    public boolean isIgnored(Path path, boolean directory) {
        if (!scope.contains(path)) return false;
        String relative = scope.root().relativize(path.normalize()).toString().replace('\\', '/');
        // Literal overrides use the most specific path. An explicit ignore wins a tie.
        int exclude = specificity(excluded, relative, directory);
        int restore = specificity(restored, relative, directory);
        if (restore > exclude) return false;
        if (exclude >= 0) return true;
        int slash = relative.lastIndexOf('/');
        if (slash >= 0 && directoryIgnored(relative.substring(0, slash))) return true;
        return matches(relative, directory);
    }

    private boolean directoryIgnored(String path) {
        Boolean cached = directories.get(path);
        if (cached != null) return cached;
        int slash = path.lastIndexOf('/');
        boolean ignored = (slash >= 0 && directoryIgnored(path.substring(0, slash))) || matches(path, true);
        directories.put(path, ignored);
        return ignored;
    }

    private boolean matches(String path, boolean directory) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            FastIgnoreRule rule = rules.get(i);
            if (rule.isMatch(path, directory, true)) return rule.getResult();
        }
        return false;
    }

    private static int specificity(List<String> entries, String path, boolean directory) {
        int result = -1;
        for (String entry : entries) {
            boolean dir = entry.endsWith("/");
            String plain = dir ? entry.substring(0, entry.length() - 1) : entry;
            boolean exact = plain.equalsIgnoreCase(path);
            if ((exact && (!dir || directory)) || (dir && path.regionMatches(true, 0, entry, 0, entry.length()))) {
                result = Math.max(result, plain.length());
            }
        }
        return result;
    }

    public static List<String> normalizePaths(Collection<String> entries) {
        if (entries == null) return List.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            String raw = entry.replace('\\', '/');
            if (raw.startsWith("/")) continue; // Also reject drive-relative rooted paths on Windows.
            boolean directory = raw.endsWith("/");
            try {
                Path path = Path.of(raw).normalize();
                String normalized = path.toString().replace('\\', '/');
                if (path.isAbsolute() || normalized.isEmpty() || normalized.equals(".")
                        || normalized.equals("..") || normalized.startsWith("../") || normalized.contains(":")) continue;
                String literal = normalized + (directory ? "/" : "");
                result.putIfAbsent(literal.toLowerCase(Locale.ROOT), literal);
            } catch (InvalidPathException ignored) { }
        }
        return List.copyOf(result.values());
    }

    public static String literalPath(ProjectScope scope, Path path, boolean directory) {
        if (!scope.contains(path)) throw new IllegalArgumentException("只能忽略当前项目内的文件或目录");
        return scope.root().relativize(path.normalize()).toString().replace('\\', '/') + (directory ? "/" : "");
    }
}
