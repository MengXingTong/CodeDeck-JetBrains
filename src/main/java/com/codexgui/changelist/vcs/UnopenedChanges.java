package com.codexgui.changelist.vcs;

import com.intellij.util.messages.Topic;
import com.codexgui.changelist.core.ChangedFile;

import java.nio.file.Path;
import java.util.List;

/** Optional native-Perforce bridge; generic VCS support works without the Perforce plugin. */
public interface UnopenedChanges {
    interface Listener {
        void changed();
        default void pathsChanged(List<Path> paths) { changed(); }
    }
    Topic<Listener> CHANGED = Topic.create("VCS sidebar unopened scan changed", Listener.class);
    record Entry(Path path, ChangedFile.Kind kind, boolean binary, NativeChanges.DiffTarget target) {}
    List<Entry> snapshot();
    String status();
    void refresh(boolean force);
    void refreshPaths(List<Path> paths);
    void operationCompleted(FileVcsOperations.Result result);
}
