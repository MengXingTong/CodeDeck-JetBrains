package com.codexgui.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class FileLineEndingsTest {
    @Test void preservesBothUniformStylesAndCodeChanges() {
        assertEquals("new\r\nline\r\n", preserve("old\r\nline\r\n", "new\nline\n"));
        assertEquals("new\nline\n", preserve("old\nline\n", "new\r\nline\r\n"));
        assertEquals("new\r\nline\r\nlast", preserve("old\r\n", "new\nline\r\nlast"));
    }

    @Test void keepsBomButDoesNotInventTrailingNewlines() {
        assertEquals("\ufeff中文\r\nlast", preserve("\ufeffold\r\n", "中文\nlast"));
        assertEquals("中文\n", preserve("old\n", "\ufeff中文\r\n"));
        assertEquals("one line", preserve("old\r\n", "one line"));
    }

    @Test void leavesMixedUnknownAndBinaryFormatsAlone() {
        for (var original : new byte[][]{null, bytes("mixed\r\nline\n"), bytes("no newline"),
            bytes("old\r"), new byte[]{0, 10}, new byte[]{(byte) 0xff, 10}}) {
            var after = bytes("new\n");
            assertSame(after, FileLineEndings.preserve(original, after));
        }
        for (var after : new byte[][]{new byte[]{0, 10}, new byte[]{(byte) 0xff, 10}})
            assertSame(after, FileLineEndings.preserve(bytes("old\r\n"), after));
    }

    private static String preserve(String before, String after) {
        return new String(FileLineEndings.preserve(bytes(before), bytes(after)), StandardCharsets.UTF_8);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
