package com.codexgui.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Preserves a known, uniform UTF-8 line ending style without touching code or guessing mixed files. */
public final class FileLineEndings {
    private FileLineEndings() {}

    public static byte[] preserve(byte[] before, byte[] after) {
        if (!text(before) || !text(after)) return after;
        boolean crlf = false, lf = false;
        for (int i = 0; i < before.length; i++) {
            if (before[i] == '\r') {
                if (i + 1 >= before.length || before[++i] != '\n') return after;
                crlf = true;
            } else if (before[i] == '\n') lf = true;
        }
        if (crlf == lf) return after; // No original style, or a genuinely mixed file.
        var normalized = new String(after, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        if (crlf) normalized = normalized.replace("\n", "\r\n");
        // Keep an existing UTF-8 BOM rather than letting a full-file rewrite add or remove it.
        boolean bom = before.length >= 3 && before[0] == (byte) 0xef && before[1] == (byte) 0xbb && before[2] == (byte) 0xbf;
        if (normalized.startsWith("\ufeff")) normalized = normalized.substring(1);
        var result = ((bom ? "\ufeff" : "") + normalized).getBytes(StandardCharsets.UTF_8);
        return Arrays.equals(result, after) ? after : result;
    }

    private static boolean text(byte[] bytes) {
        if (bytes == null) return false;
        for (byte value : bytes) if (value >= 0 && value < 32 && value != '\r' && value != '\n' && value != '\t' && value != '\f') return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException ignored) { return false; }
    }
}
