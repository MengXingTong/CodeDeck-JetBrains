package com.codexgui.service;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Literal shell arguments from raw tool calls; never evaluates generated JavaScript. */
public record ShellCommandInput(String command, String cwd) {
    private static final String STRING = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'";
    private static final Pattern CALL = Pattern.compile("(?:tools\\.)?(?:exec_command|shell_command)\\s*\\(\\s*\\{((?:" + STRING + "|[^{}])*?)}", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("(?:\"(cmd|command|workdir|cwd)\"|'(cmd|command|workdir|cwd)'|\\b(cmd|command|workdir|cwd))\\s*:\\s*(" + STRING + ")", Pattern.DOTALL);

    public static List<ShellCommandInput> parse(String name, String input) {
        if (input == null || input.length() > 2 * 1024 * 1024) return List.of();
        var result = new ArrayList<ShellCommandInput>();
        if (name != null && (name.endsWith("exec_command") || name.endsWith("shell_command"))) {
            try {
                var object = JsonParser.parseString(input).getAsJsonObject();
                var cmd = object.has("cmd") ? object.get("cmd") : object.get("command");
                var cwd = object.has("workdir") ? object.get("workdir") : object.get("cwd");
                if (cmd != null) result.add(new ShellCommandInput(cmd.getAsString(), cwd == null ? "" : cwd.getAsString()));
            } catch (RuntimeException ignored) { }
        } else {
            var calls = CALL.matcher(input);
            while (calls.find() && result.size() < 16) {
                String command = null, cwd = "";
                var fields = FIELD.matcher(calls.group(1));
                while (fields.find()) {
                    var key = fields.group(1) != null ? fields.group(1) : fields.group(2) != null ? fields.group(2) : fields.group(3);
                    try {
                        // Gson also accepts single quoted literals and decodes their escapes.
                        var value = JsonParser.parseString(fields.group(4)).getAsString();
                        if (key.equals("cmd") || key.equals("command")) command = value; else cwd = value;
                    } catch (RuntimeException ignored) { }
                }
                if (command != null) result.add(new ShellCommandInput(command, cwd));
            }
        }
        return List.copyOf(result);
    }
}
