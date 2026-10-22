package com.codexgui.changelist.core;

import com.codexgui.i18n.UiMessages;

import java.nio.file.Path;

public record ChangedFile(String id, Path path, Kind kind, boolean binary) {
    public enum Kind {
        ADDED(UiMessages.message("新增")), MODIFIED(UiMessages.message("修改")), MODIFIED_WITHOUT_CHECKOUT(UiMessages.message("修改")),
        DELETED(UiMessages.message("删除")), MOVED(UiMessages.message("移动/重命名")), UNVERSIONED(UiMessages.message("新增"));
        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }
}
