package com.codexgui.changelist.core;

import com.codexgui.i18n.UiMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Offline snapshots of github/gitignore, with their original license bundled alongside them. */
public final class IgnoreTemplates {
    public static final List<String> NAMES = List.of("UnrealEngine", "Unity", "C++", "VisualStudio");
    private IgnoreTemplates() {}

    public static String content(String name) {
        if (!NAMES.contains(name)) return "";
        try (var input = IgnoreTemplates.class.getResourceAsStream("/changelist/gitignore/" + name + ".gitignore")) {
            if (input == null) throw new IllegalStateException(UiMessages.message("缺少内置忽略模板：") + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException(UiMessages.message("无法读取忽略模板：") + name, error); }
    }
}
