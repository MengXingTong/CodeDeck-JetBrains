package com.codexgui.changelist.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

public record AiResponse(String summary, List<Annotation> annotations) {
    public record Annotation(String side, int startLine, int endLine, String title, String explanation) {}
    public static final String SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["summary","annotations"],"properties":{
          "summary":{"type":"string"},
          "annotations":{"type":"array","items":{"type":"object","additionalProperties":false,
            "required":["side","startLine","endLine","title","explanation"],"properties":{
              "side":{"type":"string","enum":["before","after"]},"startLine":{"type":"integer"},
              "endLine":{"type":"integer"},"title":{"type":"string"},"explanation":{"type":"string"}}}}}}
        """;
    public static AiResponse parse(String json, AiDiffSnapshot snapshot) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String summary = string(root, "summary", 12_000);
            var values = root.getAsJsonArray("annotations");
            if (values == null || values.size() > 200) throw new IllegalArgumentException("注释数量无效");
            var result = new ArrayList<Annotation>();
            var changes = snapshot.changes();
            for (var value : values) {
                var block = value.getAsJsonObject(); String side = string(block, "side", 10);
                int start = integer(block, "startLine"), end = integer(block, "endLine");
                if ((!side.equals("before") && !side.equals("after")) || start < 1 || end < start
                        || end > snapshot.lineCount(side) || changes.stream().noneMatch(c -> c.intersects(side, start, end)))
                    throw new IllegalArgumentException("注释行范围与当前 Diff 不一致");
                String title = string(block, "title", 300), explanation = string(block, "explanation", 12_000);
                if (title.isBlank() || explanation.isBlank()) throw new IllegalArgumentException("注释内容为空");
                result.add(new Annotation(side, start, end, title, explanation));
            }
            if (result.isEmpty() && !changes.isEmpty()) throw new IllegalArgumentException("AI 没有返回变更注释");
            for (var change : changes) {
                boolean oldCovered = change.beforeStart() < change.beforeEnd()
                        && covered(result, "before", change.beforeStart(), change.beforeEnd());
                boolean newCovered = change.afterStart() < change.afterEnd()
                        && covered(result, "after", change.afterStart(), change.afterEnd());
                if (change.afterStart() < change.afterEnd() ? !newCovered : !oldCovered)
                    throw new IllegalArgumentException("AI 未完整覆盖所有变更块");
            }
            return new AiResponse(summary, List.copyOf(result));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("AI 结果无效：" + e.getMessage() + "；未自动重试", e);
        }
    }
    private static boolean covered(List<Annotation> annotations, String side, int start, int end) {
        int cursor = start + 1;
        for (var annotation : annotations.stream().filter(a -> a.side().equals(side))
                .sorted(java.util.Comparator.comparingInt(Annotation::startLine)).toList()) {
            if (annotation.endLine() < cursor) continue;
            if (annotation.startLine() > cursor) return false;
            cursor = Math.max(cursor, annotation.endLine() + 1);
            if (cursor > end) return true;
        }
        return false;
    }
    private static int integer(JsonObject object, String key) {
        return object.getAsJsonPrimitive(key).getAsBigDecimal().intValueExact();
    }
    private static String string(JsonObject object, String key, int maximum) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("缺少 " + key);
        String text = value.getAsString();
        if (text.length() > maximum) throw new IllegalArgumentException(key + " 过长");
        return text;
    }
}
