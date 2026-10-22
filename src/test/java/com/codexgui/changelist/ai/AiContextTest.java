package com.codexgui.changelist.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiContextTest {
    private static AiDiffSnapshot change() { return new AiDiffSnapshot("Source/中文.cs", "old\nkeep\n", "new\nkeep\n"); }
    private static String annotation(String side, String first, String last) {
        return """
            {"summary":"变更摘要","annotations":[{"side":"%s","startLine":%s,"endLine":%s,
            "title":"功能块","explanation":"说明行为变化"}]}
            """.formatted(side, first, last);
    }
    @Test void snapshotsNormalizeLineEndingsAndBindBothRevisionsAndPath() {
        var lf = change();
        assertEquals(lf.fingerprint(), new AiDiffSnapshot(lf.file(), "old\r\nkeep\r\n", "new\r\nkeep\r\n").fingerprint());
        assertNotEquals(lf.fingerprint(), new AiDiffSnapshot("Other.cs", lf.before(), lf.after()).fingerprint());
        assertNotEquals(lf.fingerprint(), new AiDiffSnapshot(lf.file(), "older", lf.after()).fingerprint());
        assertNotEquals(lf.fingerprint(), new AiDiffSnapshot(lf.file(), lf.before(), "newer").fingerprint());
        assertEquals(List.of(new AiDiffSnapshot.ChangedRange(0, 1, 0, 1)), lf.changes());
    }
    @Test void addedDeletedAndEmptyFilesHaveUnambiguousSides() {
        var added = new AiDiffSnapshot("a.cs", "", "a\nb\n");
        assertEquals(List.of(new AiDiffSnapshot.ChangedRange(0, 0, 0, 2)), added.changes());
        assertEquals(0, added.lineCount("before"));
        assertEquals(1, AiResponse.parse(annotation("after", "1", "2"), added).annotations().size());
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("before", "1", "1"), added));
        var deleted = new AiDiffSnapshot("a.cs", "a\nb\n", "");
        assertEquals(1, AiResponse.parse(annotation("before", "1", "2"), deleted).annotations().size());
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("after", "1", "1"), deleted));
        assertTrue(new AiDiffSnapshot("empty", "", "").changes().isEmpty());
    }
    @Test void refusesHugeContextInsteadOfSilentlyOmittingChanges() {
        assertThrows(IllegalArgumentException.class, () -> new AiDiffSnapshot("huge", "a".repeat(120_001), ""));
    }
    @Test void rejectsPartialCoverageAndAllowsSemanticBlocksToCoverAnAdditionTogether() {
        var added = new AiDiffSnapshot("new.cs", "", "first\nsecond\n");
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("after", "1", "1"), added));
        String first = annotation("after", "1", "1"); String second = annotation("after", "2", "2");
        var root = JsonParser.parseString(first).getAsJsonObject();
        root.getAsJsonArray("annotations").add(JsonParser.parseString(second).getAsJsonObject().getAsJsonArray("annotations").get(0));
        assertEquals(2, AiResponse.parse(root.toString(), added).annotations().size());
        var separate = new AiDiffSnapshot("edit.cs", "old\nunchanged\nold\n", "new\nunchanged\nnew\n");
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("after", "1", "1"), separate));
    }
    @Test void validatesAnnotationAnchorsAgainstActualChangedLines() {
        assertEquals("功能块", AiResponse.parse(annotation("after", "1", "1"), change()).annotations().getFirst().title());
        for (String bad : List.of(annotation("after", "2", "2"), annotation("other", "1", "1"),
                annotation("after", "0", "1"), annotation("after", "1", "99"), annotation("after", "1.5", "2"),
                annotation("after", "2", "1"), "not json"))
            assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(bad, change()), bad);
    }
    @Test void commentOnlyChangesCanAnchorAfterTheUnchangedDeclarationWithoutLosingChangeCoverage() {
        String declaration = "\nUFUNCTION(BlueprintCallable, BlueprintNativeEvent)\nbool TryOccupy(AActor* Occupier);\n";
        var snapshot = new AiDiffSnapshot("Interaction.h", "// 旧说明" + declaration, "// 新说明" + declaration);
        var response = AiResponse.parse(annotation("after", "1", "3"), snapshot);
        assertEquals(3, response.annotations().getFirst().endLine());
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("after", "3", "3"), snapshot),
                "An anchor may include unchanged context but must still explain the actual change");
    }
    @Test void doesNotAcceptAnEmptyExplanationOrMissingAnnotations() {
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse(annotation("after", "1", "1").replace("说明行为变化", ""), change()));
        assertThrows(IllegalArgumentException.class, () -> AiResponse.parse("{\"summary\":\"\",\"annotations\":[]}", change()));
    }
    @Test void annotationPromptContainsTheFullNativeDiffAndNoConversation() {
        var prompt = JsonParser.parseString(AiAnnotationPrompt.create(change())).getAsJsonObject();
        assertEquals("annotate", prompt.get("task").getAsString());
        assertEquals("1: old\n2: keep\n3: \n", prompt.getAsJsonObject("diff").get("before").getAsString());
        assertTrue(prompt.getAsJsonObject("diff").get("after").getAsString().contains("new"));
        assertFalse(prompt.has("selection")); assertFalse(prompt.has("history")); assertFalse(prompt.has("question"));
        assertTrue(prompt.getAsJsonObject("diff").has("changedRanges"));
    }
    @Test void cacheSeparatesModelsEffortsFastAndDiffVersions() {
        var standard = new AiOptions("", "model-a", "low", false);
        for (var other : List.of(new AiOptions("", "model-b", "low", false), new AiOptions("", "model-a", "high", false),
                new AiOptions("", "model-a", "low", true))) assertNotEquals(standard.cacheKey(change()), other.cacheKey(change()));
        assertNotEquals(standard.cacheKey(change()), standard.cacheKey(new AiDiffSnapshot("Source/中文.cs", "old", "other")));
    }
}
