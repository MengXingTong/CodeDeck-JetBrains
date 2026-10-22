package com.codexgui.changelist.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiAnnotationAnchorTest {
    private AiResponse.Annotation annotation(int start, int end) {
        return new AiResponse.Annotation("after", start, end, "说明", "仅修改注释，未改变函数声明");
    }
    @Test void changedLeadingCommentStaysWithTheFollowingUnrealFunctionDeclaration() {
        var anchor = new AiAnnotationAnchor("""
            // 客户端检查同步状态；服务器重新校验任务资格并提交占用。
            UFUNCTION(BlueprintCallable, BlueprintNativeEvent)
            bool TryOccupy(AActor* Occupier);
            void Other();
            """);
        assertEquals(3, anchor.endLine(annotation(1, 1)));
        assertEquals(3, anchor.endLine(annotation(1, 3)), "Keep an already complete model-provided range");
    }
    @Test void commentsAndMultilineAttributesAreKeptWithTheirOwnDeclaration() {
        var anchor = new AiAnnotationAnchor("""
            /** 修改后的说明
             * 补充说明 */
            UFUNCTION(BlueprintCallable,
                meta=(DisplayName="说明中的 ; { } 不应结束声明"))
            bool TryOccupy(
                AActor* Occupier);
            // 下一段
            void Other();
            """);
        assertEquals(6, anchor.endLine(annotation(1, 2)));
        assertEquals(8, anchor.endLine(annotation(7, 7)));
    }
    @Test void codeRangesStandaloneCommentsAndFunctionBodiesAreNotGuessedOrMoved() {
        assertEquals(1, new AiAnnotationAnchor("void First();\nvoid Second();").endLine(annotation(1, 1)));
        assertEquals(1, new AiAnnotationAnchor("// 独立说明\n\nvoid Other();").endLine(annotation(1, 1)));
        assertEquals(1, new AiAnnotationAnchor("// 函数说明\nvoid Other() {\nwork();\n}").endLine(annotation(1, 1)));
        assertEquals(1, new AiAnnotationAnchor("// 文件说明\n#define SOMETHING 1\nvoid Other();").endLine(annotation(1, 1)));
        assertEquals(1, new AiAnnotationAnchor("\nvoid Other();").endLine(annotation(1, 1)));
    }
    @Test void unrelatedStringLiteralsAndDigitSeparatorsCannotSwallowLaterComments() {
        var anchor = new AiAnnotationAnchor("int count = 1'000;\nconst char* url = \"https://host/{;\";\n// 说明\nvoid Function();");
        assertEquals(4, anchor.endLine(annotation(3, 3)));
    }
}
