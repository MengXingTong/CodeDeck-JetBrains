package com.codexgui.changelist.vcs;

import java.nio.file.Path;
import java.util.*;

/** Recent edits win over older full scans, including explicit removal after an undo/revert. */
final class LiveChangesOverlay {
    record Update(Path path, UnopenedChanges.Entry entry, boolean unsaved) {}
    private record Stamped(Update update, long version) {}
    private volatile Map<Path, Stamped> updates = Map.of();
    private long version;

    long version() { return version; }

    void update(List<Update> changes) {
        Map<Path, Stamped> next = new HashMap<>(updates);
        long stamp = ++version;
        for (Update change : changes) next.put(change.path(), new Stamped(change, stamp));
        updates = Map.copyOf(next);
    }

    void acknowledge(Collection<Path> scanned, long startedVersion) {
        Map<Path, Stamped> next = new HashMap<>(updates);
        for (Path path : scanned) {
            var current = next.get(path);
            if (current != null && current.version() <= startedVersion && !current.update().unsaved()) next.remove(path);
        }
        updates = Map.copyOf(next);
    }

    void acknowledgeAll(long startedVersion) {
        Map<Path, Stamped> next = new HashMap<>(updates);
        next.values().removeIf(current -> current.version() <= startedVersion && !current.update().unsaved());
        updates = Map.copyOf(next);
    }

    List<UnopenedChanges.Entry> merge(List<UnopenedChanges.Entry> scanned) {
        Map<Path, UnopenedChanges.Entry> result = new LinkedHashMap<>();
        for (var entry : scanned) result.put(entry.path(), entry);
        for (var stamped : updates.values()) {
            var change = stamped.update();
            if (change.entry() == null) result.remove(change.path());
            else result.put(change.path(), change.entry());
        }
        return List.copyOf(result.values());
    }

    void clear() { updates = Map.of(); }
}
