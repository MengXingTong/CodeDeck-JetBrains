package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexModelCatalogTest {
    @TempDir Path home;

    @Test void suggestionsExcludeHiddenBlankAndDuplicateModels() {
        assertEquals(List.of("visible", "other"), CodexModelCatalog.parse("""
            {"models":[{"slug":"visible","visibility":"list"},{"slug":"hidden","visibility":"hide"},
            {"slug":"visible"},{"slug":" "},{"slug":"other"},{}]}
            """));
        assertTrue(CodexModelCatalog.parse("{}").isEmpty());
    }

    @Test void missingCorruptAndOversizedCachesAreOptional() throws Exception {
        assertTrue(CodexModelCatalog.load(home).isEmpty());
        var file = home.resolve("models_cache.json");
        Files.writeString(file, "partial json");
        assertTrue(CodexModelCatalog.load(home).isEmpty());
        Files.writeString(file, "{\"models\":[{\"slug\":\"cached-model\"}]}");
        assertEquals(List.of("cached-model"), CodexModelCatalog.load(home));
        Files.writeString(file, " ".repeat(4 * 1024 * 1024 + 1));
        assertTrue(CodexModelCatalog.load(home).isEmpty());
    }
}
