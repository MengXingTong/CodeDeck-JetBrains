package com.codexgui.changelist.core;

import java.nio.file.Path;
import java.util.*;

/** Stable node ids retain expansion and selection across native VCS refreshes. */
public final class ChangeTree {
    private ChangeTree() {}
    public record Group(String id, String label, List<ChangedFile> files) {}
    public record Node(String id, String label, ChangedFile file, Path path, List<Node> children) {}

    public static Node build(List<Group> groups, Path base) {
        return build(groups, base, () -> {});
    }
    public static Node build(List<Group> groups, Path base, Runnable checkCanceled) {
        ProjectScope scope = ProjectScope.of(base);
        List<Node> nodes = new ArrayList<>();
        for (Group group : groups) {
            checkCanceled.run();
            if (group.files().isEmpty()) continue;
            Builder root = new Builder("group/" + group.id(), group.label(), null, scope.root());
            int count = 0;
            for (ChangedFile file : group.files()) {
                checkCanceled.run();
                if (!scope.contains(file.path())) continue;
                Path local = file.path().normalize();
                String display = scope.root().relativize(local).toString().replace('\\', '/');
                String[] parts = display.split("/");
                Builder parent = root;
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].isBlank()) continue;
                    String name = parts[i];
                    Builder currentParent = parent;
                    parent = parent.children.computeIfAbsent("dir/" + name,
                            key -> new Builder(currentParent.id + "/" + key, name, null, currentParent.path.resolve(name)));
                }
                String name = parts[parts.length - 1];
                parent.children.put("file/" + file.id(), new Builder(root.id + "/file/" + file.id(), name, file, local));
                count++;
            }
            if (count == 0) continue;
            Node node = root.freeze(checkCanceled);
            nodes.add(new Node(node.id(), node.label() + " (" + count + ")", null, node.path(), node.children()));
        }
        return new Node("root", "VCS Changelist", null, scope.root(), List.copyOf(nodes));
    }

    private static final class Builder {
        final String id, label;
        final ChangedFile file;
        final Path path;
        final Map<String, Builder> children = new HashMap<>();
        Builder(String id, String label, ChangedFile file, Path path) {
            this.id = id; this.label = label; this.file = file; this.path = path;
        }
        Node freeze(Runnable checkCanceled) {
            checkCanceled.run();
            var sorted = children.values().stream().sorted(Comparator
                    .comparing((Builder builder) -> builder.file != null)
                    .thenComparing(builder -> builder.label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(builder -> builder.label).thenComparing(builder -> builder.id))
                    .map(child -> child.freeze(checkCanceled)).toList();
            return new Node(id, label, file, path, sorted);
        }
    }
}
