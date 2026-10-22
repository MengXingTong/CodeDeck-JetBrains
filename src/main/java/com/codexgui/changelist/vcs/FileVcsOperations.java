package com.codexgui.changelist.vcs;

import com.intellij.openapi.vcs.VcsException;
import com.codexgui.changelist.core.ChangedFile;

import java.nio.file.Path;
import java.util.List;

/** Optional Perforce operations, loaded only when the IDE's Perforce plugin is available. */
public interface FileVcsOperations {
    enum Operation {
        REVERT("撤销修改", "恢复工作区基准版本，丢弃本地修改"),
        CHECKOUT("迁出", "打开 Perforce 文件以供编辑，保留本地修改"),
        CANCEL_CHECKOUT("取消迁出", "解除 Perforce 迁出状态，保留本地修改");
        private final String label;
        private final String description;
        Operation(String label, String description) { this.label = label; this.description = description; }
        public String label() { return label; }
        public String description() { return description; }
    }

    record Update(Path path, ChangedFile.Kind kind, boolean binary, NativeChanges.DiffTarget target,
                  String groupId, String groupLabel) {}
    record Result(List<Update> updates) {}

    Result perform(Operation operation, List<Path> paths, Runnable beforeMutation) throws VcsException;

    /** Menu construction uses existing metadata only, without reading content or contacting the server. */
    static boolean available(Operation operation, ChangedFile file, NativeChanges.DiffTarget target,
                             boolean perforce, boolean nativeRollback) {
        if (file == null || target == null || file.kind() == ChangedFile.Kind.UNVERSIONED) return false;
        boolean opened = target.opened();
        return switch (operation) {
            case REVERT -> perforce || (opened && nativeRollback);
            case CHECKOUT -> perforce && !opened && (file.kind() == ChangedFile.Kind.MODIFIED
                    || file.kind() == ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT);
            case CANCEL_CHECKOUT -> perforce && opened;
        };
    }
}
