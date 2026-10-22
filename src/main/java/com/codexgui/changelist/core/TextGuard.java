package com.codexgui.changelist.core;

public final class TextGuard {
    private TextGuard() {}

    /** UTF-16 BOM is text; arbitrary NUL/control bytes are binary even when mislabeled by Perforce. */
    public static boolean isProbablyText(byte[] bytes) {
        int start = 0;
        int stride = 1;
        if (bytes.length >= 2) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            if ((a == 255 && b == 254) || (a == 254 && b == 255)) {
                if (bytes.length % 2 != 0) return false;
                start = 2; stride = 2;
            }
        }
        int controls = 0, sampled = 0;
        for (int i = start; i < Math.min(bytes.length, 8192); i += stride) {
            int value = bytes[i] & 255;
            if (stride == 2) {
                boolean little = (bytes[0] & 255) == 255;
                value = little ? value | ((bytes[i + 1] & 255) << 8) : (value << 8) | (bytes[i + 1] & 255);
            }
            if (value == 0) return false;
            if (value < 32 && value != '\r' && value != '\n' && value != '\t' && value != '\f') controls++;
            sampled++;
        }
        return controls <= Math.max(1, sampled / 100);
    }
}
