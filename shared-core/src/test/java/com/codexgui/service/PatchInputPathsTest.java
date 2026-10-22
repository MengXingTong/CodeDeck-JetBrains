package com.codexgui.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class PatchInputPathsTest {
    @Test void readsDirectAndJsonQuotedCodeModePatchArgumentsWithoutExecutingCode() {
        var patch = "*** Begin Patch\n*** Update File: Source/旧 文件.cpp\n*** Move to: Source/新 文件.cpp\n@@\n-before\n+after\n*** End Patch";
        var expected = List.of("Source/旧 文件.cpp", "Source/新 文件.cpp");
        assertEquals(expected, PatchInputPaths.parse("apply_patch", patch));
        assertEquals(expected, PatchInputPaths.parse("exec", "text(await tools.apply_patch(" + new Gson().toJson(patch) + "));"));
        assertEquals(List.of(), PatchInputPaths.parse("exec", "tools.apply_patch(readArbitraryData());"));
        assertEquals(List.of(), PatchInputPaths.parse("exec", "tools.exec_command({cmd:'echo no patch'})"));
    }
}
