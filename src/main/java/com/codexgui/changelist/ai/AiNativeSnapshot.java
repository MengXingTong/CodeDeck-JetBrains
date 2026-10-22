package com.codexgui.changelist.ai;

import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import java.nio.file.Path;

/** Call on EDT or under a short read action. */
public final class AiNativeSnapshot {
    private AiNativeSnapshot() {}
    public static AiDiffSnapshot read(Path root, Path file, DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contents) || contents.getContents().size() != 2)
            throw new IllegalArgumentException("AI 只支持双版本文本 Diff");
        if (!file.normalize().startsWith(root.normalize())) throw new IllegalArgumentException("文件不在当前项目内");
        if ((long) length(contents, 0) + length(contents, 1) > AiDiffSnapshot.MAX_SOURCE_CHARS)
            throw new IllegalArgumentException("文件前后内容合计超过 120,000 字符，已停止 AI 请求；请缩小文件后手动重试");
        return new AiDiffSnapshot(root.relativize(file).toString(), content(contents, 0), content(contents, 1));
    }
    private static int length(ContentDiffRequest request, int side) {
        var content = request.getContents().get(side);
        return content instanceof DocumentContent document ? document.getDocument().getTextLength() : 0;
    }
    public static String content(ContentDiffRequest request, int side) {
        var value = request.getContents().get(side);
        if (value instanceof EmptyContent) return "";
        if (value instanceof DocumentContent document) return document.getDocument().getText();
        throw new IllegalArgumentException("AI 只支持文本 Diff");
    }
    public static boolean matches(AiDiffSnapshot snapshot, ContentDiffRequest request) {
        return snapshot.before().length() == length(request, 0) && snapshot.after().length() == length(request, 1)
                && snapshot.before().equals(content(request, 0)) && snapshot.after().equals(content(request, 1));
    }
}
