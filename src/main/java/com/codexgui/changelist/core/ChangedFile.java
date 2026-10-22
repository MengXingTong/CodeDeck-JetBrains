package com.codexgui.changelist.core;

import java.nio.file.Path;

public record ChangedFile(String id, Path path, Kind kind, boolean binary) {
    public enum Kind {
        ADDED("新增"), MODIFIED("修改"), MODIFIED_WITHOUT_CHECKOUT("修改"),
        DELETED("删除"), MOVED("移动/重命名"), UNVERSIONED("新增");
        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }
}
