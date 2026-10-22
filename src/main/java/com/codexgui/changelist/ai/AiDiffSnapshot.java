package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Immutable native Diff contents, including unsaved documents. Never reads the working tree. */
public record AiDiffSnapshot(String file, String before, String after, String fingerprint) {
    public static final int MAX_SOURCE_CHARS = 120_000;
    public record ChangedRange(int beforeStart, int beforeEnd, int afterStart, int afterEnd) {
        boolean intersects(String side, int start, int end) {
            int first = side.equals("before") ? beforeStart : afterStart;
            int last = side.equals("before") ? beforeEnd : afterEnd;
            return first < last && start <= last && end > first;
        }
    }
    public AiDiffSnapshot(String file, String before, String after) {
        this(file, normalize(before), normalize(after), hash(file, normalize(before), normalize(after)));
    }
    public AiDiffSnapshot {
        if ((long) before.length() + after.length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException(UiMessages.message("文件前后内容合计超过 120,000 字符，已停止 AI 请求；请缩小文件后手动重试"));
    }
    private static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
    private static String hash(String... values) {
        if ((long) values[1].length() + values[2].length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException(UiMessages.message("文件前后内容合计超过 120,000 字符，已停止 AI 请求"));
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String value : values) { hash.update(value.getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public List<ChangedRange> changes() {
        var edits = new HistogramDiff().diff(RawTextComparator.DEFAULT,
                new RawText(before.getBytes(StandardCharsets.UTF_8)), new RawText(after.getBytes(StandardCharsets.UTF_8)));
        return edits.stream().map(e -> new ChangedRange(e.getBeginA(), e.getEndA(), e.getBeginB(), e.getEndB())).toList();
    }
    public int lineCount(String side) {
        String text = side.equals("before") ? before : after;
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }
    public JsonObject context() {
        var object = new JsonObject();
        object.addProperty("file", file); object.addProperty("version", fingerprint);
        object.addProperty("lineNumbering", UiMessages.message("before/after 从 1 开始；changedRanges 为从 0 开始、末端不含的范围"));
        object.addProperty("before", numbered(before)); object.addProperty("after", numbered(after));
        var ranges = new JsonArray();
        for (var change : changes()) {
            var range = new JsonObject();
            range.addProperty("beforeStart", change.beforeStart); range.addProperty("beforeEnd", change.beforeEnd);
            range.addProperty("afterStart", change.afterStart); range.addProperty("afterEnd", change.afterEnd);
            ranges.add(range);
        }
        object.add("changedRanges", ranges); return object;
    }
    private static String numbered(String text) {
        if (text.isEmpty()) return "";
        var result = new StringBuilder(); int line = 1;
        for (String value : text.split("\n", -1)) result.append(line++).append(": ").append(value).append('\n');
        return result.toString();
    }
}
