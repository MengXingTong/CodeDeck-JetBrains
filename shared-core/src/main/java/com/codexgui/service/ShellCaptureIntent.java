package com.codexgui.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative shell hints, never evaluates code. Unknown commands keep bounded fallback capture. */
final class ShellCaptureIntent {
    private static final Pattern FIRST = Pattern.compile("^\\s*(?:&\\s+)?(\"[^\"]*\"|'[^']*'|[^\\s]+)");
    private static final Pattern WRAPPER_ARGUMENT = Pattern.compile("(?is)(?:^|\\s)(?:-command|-c|/c)\\s+(.+)$");
    private static final Set<String> READERS = Set.of("rg", "grep", "egrep", "fgrep", "findstr", "cat", "head", "tail",
        "ls", "dir", "pwd", "wc", "stat", "type", "get-content", "gc", "get-childitem", "gci", "get-item", "get-location",
        "test-path", "resolve-path", "select-string", "select-object", "sort-object", "format-list", "format-table",
        "out-string", "measure-object", "get-filehash");
    private static final Set<String> WRITERS = Set.of("set-content", "add-content", "out-file", "new-item", "remove-item",
        "rename-item", "move-item", "copy-item", "clear-content", "sc", "ac", "ni", "ri", "rm", "del", "erase",
        "ren", "rename", "move", "mv", "copy", "cp", "mkdir", "rmdir", "touch", "truncate", "tee");
    private static final Pattern WRITE_API = Pattern.compile("(?i)\\b(?:WriteAllText|WriteAllBytes|WriteAllLines|AppendAllText|AppendAllLines|"
        + "writeFileSync|appendFileSync|writeFile|appendFile|write_text|write_bytes|unlink|unlinkSync|renameSync)\\s*\\(");
    private static final Pattern WRITE_OPEN = Pattern.compile("(?i)\\bopen\\s*\\([^\\r\\n]*?['\"][wax](?:[bt+]{0,2})['\"]");
    private static final Pattern READ_VCS = Pattern.compile("(?is)^(?:git(?:\\s+-C\\s+(?:\"[^\"]*\"|'[^']*'|\\S+))?\\s+"
        + "(?:status|diff|log|show|ls-files|ls-tree|rev-parse)|p4\\s+(?:opened|diff|fstat|files|where))\\b.*");

    static List<String> captureSegments(String command) {
        if (command == null || command.isBlank()) return List.of();
        command = unwrap(command);
        var segments = split(command, false);
        return segments == null ? List.of(command) : segments.stream().filter(s -> !readOnly(s)).toList();
    }

    static List<String> explicitWriteSegments(String command) {
        var segments = split(command, true);
        return (segments == null ? List.of(command) : segments).stream().filter(ShellCaptureIntent::explicitWrite).toList();
    }

    private static List<String> split(String command, boolean pipeline) {
        var segments = new ArrayList<String>();
        char quote = 0;
        int depth = 0, start = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if ((c == '\\' || c == '`') && i + 1 < command.length() && command.charAt(i + 1) == quote) i++;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (depth == 0 && (pipeline ? c == '|'
                : c == ';' || c == '&' || c == '\n' || c == '\r'
                    || c == '|' && (i + 1 < command.length() && command.charAt(i + 1) == '|'
                        || i > 0 && command.charAt(i - 1) == '|'))) {
                // '& command' is PowerShell's invocation operator, not a statement separator.
                boolean descriptor = i > 0 && command.charAt(i - 1) == '>';
                boolean invocation = command.substring(start, i).isBlank()
                    && (i + 1 == command.length() || command.charAt(i + 1) != '&');
                if (c == '&' && (descriptor || invocation)) continue;
                segments.add(command.substring(start, i)); start = i + 1;
                if (segments.size() >= 128) return null;
            }
        }
        if (quote != 0 || depth != 0) return null;
        segments.add(command.substring(start));
        return segments.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean explicitWrite(String command) {
        return WRITERS.contains(executable(command)) || WRITE_API.matcher(command).find()
            || WRITE_OPEN.matcher(command).find() || redirects(command) || VcsCommandPaths.restores(command)
            || command.matches("(?is)^\\s*sed\\s+(?:.*\\s)?-i(?:\\s|[^a-z]).*");
    }

    private static boolean readOnly(String command) {
        var segments = split(command, true);
        return segments != null && segments.stream().allMatch(ShellCaptureIntent::readOnlyPart);
    }

    private static boolean readOnlyPart(String command) {
        // Substitutions and helper executables can write even inside an otherwise read-only command.
        if (redirects(command) || command.contains("$(") || command.contains("`")
            || command.matches("(?is).*(?:--pre(?:=|\\s)|--exec(?:=|\\s)|-exec\\b|--output(?:=|\\s)|--ext-diff\\b|--textconv\\b).*")) return false;
        return READERS.contains(executable(command)) || READ_VCS.matcher(command).matches();
    }

    private static boolean redirects(String command) {
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if ((c == '\\' || c == '`') && i + 1 < command.length() && command.charAt(i + 1) == quote) i++;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return true;
        }
        return false;
    }

    private static String executable(String command) {
        var first = FIRST.matcher(command);
        if (!first.find()) return "";
        String name = unquote(first.group(1)).replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static String unwrap(String command) {
        for (int i = 0; i < 3; i++) {
            if (!Set.of("powershell", "pwsh", "cmd", "bash", "sh").contains(executable(command))) break;
            var argument = WRAPPER_ARGUMENT.matcher(command);
            if (!argument.find()) break;
            command = unquote(argument.group(1).trim());
        }
        return command;
    }

    private static String unquote(String value) {
        return value.length() > 1 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
            && value.charAt(value.length() - 1) == value.charAt(0) ? value.substring(1, value.length() - 1) : value;
    }
}
