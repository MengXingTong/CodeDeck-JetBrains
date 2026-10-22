package com.codexgui.changelist.ai;

/** Keep a changed leading comment, its attributes/macros and the following declaration together. */
final class AiAnnotationAnchor {
    private final String[] source, code;

    AiAnnotationAnchor(String text) {
        source = text.split("\n", -1); code = new String[source.length];
        boolean blockComment = false; char quote = 0;
        for (int line = 0; line < source.length; line++) {
            var result = new StringBuilder(); String value = source[line];
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i), next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
                if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
                if (quote != 0) {
                    if (c == '\\') i++;
                    else if (c == quote) quote = 0;
                    continue;
                }
                if (c == '/' && next == '/') break;
                if (c == '/' && next == '*') { blockComment = true; i++; continue; }
                if (c == '"' || c == '\'') { quote = c; result.append('x'); continue; }
                result.append(c);
            }
            code[line] = result.toString().strip();
            quote = 0; // Do not let an unmatched quote or a C++ digit separator consume subsequent declarations.
        }
    }
    int endLine(AiResponse.Annotation annotation) {
        int end = annotation.endLine();
        boolean comment = false;
        for (int line = annotation.startLine() - 1; line < end; line++) {
            if (!code[line].isEmpty()) return end;
            String value = source[line].strip();
            comment |= value.startsWith("//") || value.startsWith("/*") || value.startsWith("*");
        }
        if (!comment) return end;
        int parentheses = 0, brackets = 0;
        for (int line = end; line < Math.min(source.length, end + 32); line++) {
            // A blank line separates standalone commentary from the next declaration.
            if (source[line].isBlank() || code[line].startsWith("#")) return end;
            for (char c : code[line].toCharArray()) {
                if (c == '{' || c == '}') return end; // Do not guess the extent of a function/class body.
                if (c == '(') parentheses++;
                else if (c == ')') parentheses--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
                else if (c == ';' && parentheses == 0 && brackets == 0) return line + 1;
                if (parentheses < 0 || brackets < 0) return end;
            }
        }
        return end;
    }
}
