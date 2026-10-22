package com.codexgui.service;

import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Reads literal patch arguments without evaluating model-generated JavaScript. */
public final class PatchInputPaths {
    private static final Pattern ARGUMENT = Pattern.compile("(?:tools\\.)?apply_patch\\s*\\(\\s*(\"(?:\\\\.|[^\"\\\\])*\")", Pattern.DOTALL);
    private static final Pattern PATH = Pattern.compile("(?m)^\\*\\*\\* (?:Add File|Update File|Delete File|Move to): (.+)$");
    private PatchInputPaths() { }

    public static List<String> parse(String name, String input) {
        if (input == null || input.length() > 2 * 1024 * 1024) return List.of();
        var paths = new LinkedHashSet<String>();
        if (name != null && name.endsWith("apply_patch")) collect(input, paths);
        else {
            var arguments = ARGUMENT.matcher(input);
            while (arguments.find() && paths.size() < 128) {
                try { collect(JsonParser.parseString(arguments.group(1)).getAsString(), paths); }
                catch (RuntimeException ignored) { }
            }
        }
        return List.copyOf(paths);
    }

    private static void collect(String patch, LinkedHashSet<String> paths) {
        var matcher = PATH.matcher(patch);
        while (matcher.find() && paths.size() < 128) paths.add(matcher.group(1).stripTrailing());
    }
}
