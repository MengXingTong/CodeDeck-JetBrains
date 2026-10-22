package com.codexgui.changelist.vcs;

import com.codexgui.i18n.UiMessages;

import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Acknowledged server results bridge the delay before the IDE's shared VCS cache catches up. */
public final class FileOperationOverlay {
    private record Pending(FileVcsOperations.Update update, long nativeVersion) {}
    private volatile Map<Path, Pending> pending = Map.of();
    private long nativeVersion;
    private volatile long revision;

    public long revision() { return revision; }

    public synchronized void record(FileVcsOperations.Result result) {
        Map<Path, Pending> next = new LinkedHashMap<>(pending);
        for (var update : result.updates()) next.put(update.path().normalize(), new Pending(update, nativeVersion));
        pending = Map.copyOf(next);
        revision++;
    }

    /** Observe an update that the IDE performed independently; this never requests a VCS refresh. */
    public synchronized void nativeUpdateCompleted() {
        nativeVersion++;
        if (!pending.isEmpty()) revision++;
    }

    public synchronized void clear() { pending = Map.of(); revision++; }

    /** A targeted live check can correct local content state while the native opened cache is still stale. */
    public synchronized void localChanges(List<Path> paths, List<UnopenedChanges.Entry> entries) {
        Map<Path, UnopenedChanges.Entry> current = new HashMap<>();
        for (var entry : entries) current.put(entry.path().normalize(), entry);
        Map<Path, Pending> next = new LinkedHashMap<>(pending);
        for (Path path : paths) {
            var previous = next.get(path.normalize());
            if (previous == null || (previous.update().target() != null && previous.update().target().opened())) continue;
            var entry = current.get(path.normalize());
            var update = new FileVcsOperations.Update(path, entry == null ? null : entry.kind(),
                    entry != null && entry.binary(), entry == null ? null : entry.target(), "local", UiMessages.message("本地变更"));
            next.put(path.normalize(), new Pending(update, previous.nativeVersion()));
        }
        if (next.equals(pending)) return;
        pending = Map.copyOf(next);
        revision++;
    }

    private synchronized Map<Path, Pending> reconcile(NativeChanges.Snapshot snapshot) {
        if (pending.isEmpty()) return pending;
        Map<Path, NativeChanges.DiffTarget> observed = new HashMap<>();
        for (var group : snapshot.groups()) for (var file : group.files()) {
            observed.put(file.path().normalize(), snapshot.targets().get(file.id()));
        }
        Map<Path, Pending> next = new LinkedHashMap<>(pending);
        next.entrySet().removeIf(entry -> {
            var operation = entry.getValue();
            if (operation.nativeVersion() >= nativeVersion) return false;
            var actual = observed.get(entry.getKey());
            boolean opened = operation.update().target() != null && operation.update().target().opened();
            return opened == (actual != null && actual.opened());
        });
        // Releasing an override whose state now agrees with the native cache does not invalidate this snapshot.
        pending = Map.copyOf(next);
        return pending;
    }

    public NativeChanges.Snapshot apply(NativeChanges.Snapshot snapshot, boolean showUnversioned,
                                        Predicate<Path> visible) {
        var updates = reconcile(snapshot);
        if (updates.isEmpty()) return snapshot;
        var scope = ProjectScope.of(snapshot.projectRoot());
        Map<String, List<ChangedFile>> files = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, NativeChanges.DiffTarget> targets = new HashMap<>(snapshot.targets());
        for (var group : snapshot.groups()) {
            List<ChangedFile> kept = new ArrayList<>();
            for (var file : group.files()) {
                if (updates.containsKey(file.path().normalize())) targets.remove(file.id());
                else kept.add(file);
            }
            files.put(group.id(), kept); labels.put(group.id(), group.label());
        }
        for (var pending : updates.values()) {
            var update = pending.update();
            if (update.kind() == null || !scope.contains(update.path()) || !visible.test(update.path())
                    || (!showUnversioned && update.kind() == ChangedFile.Kind.UNVERSIONED)) continue;
            String id = "file-operation:" + update.path().normalize();
            files.computeIfAbsent(update.groupId(), key -> new ArrayList<>())
                    .add(new ChangedFile(id, update.path(), update.kind(), update.binary()));
            labels.putIfAbsent(update.groupId(), update.groupLabel()); targets.put(id, update.target());
        }
        List<ChangeTree.Group> groups = new ArrayList<>();
        for (var group : files.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            group.getValue().sort(Comparator.comparing(file -> file.path().toString()));
            groups.add(new ChangeTree.Group(group.getKey(), labels.get(group.getKey()), List.copyOf(group.getValue())));
        }
        // Keep native changelists before the local supplement, including a newly populated changelist.
        groups.sort(Comparator.comparing(group -> "local".equals(group.id())));
        return new NativeChanges.Snapshot(List.copyOf(groups), Map.copyOf(targets), snapshot.vcsNames(), snapshot.projectRoot());
    }
}
