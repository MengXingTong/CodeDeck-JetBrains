package com.codexgui.service;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Optional suggestions from the same local CLI cache; never starts a process or model request. */
public final class CodexModelCatalog {
    private CodexModelCatalog() {}
    public static List<String> load() {
        try {
            String home = System.getenv("CODEX_HOME");
            Path directory = home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(home);
            return load(directory);
        } catch (Exception ignored) { return List.of(); }
    }
    static List<String> load(Path directory) {
        try {
            Path file = directory.resolve("models_cache.json");
            if (!Files.isRegularFile(file) || Files.size(file) > 4 * 1024 * 1024) return List.of();
            return parse(Files.readString(file));
        } catch (Exception ignored) { return List.of(); }
    }
    static List<String> parse(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("models") || !root.get("models").isJsonArray()) return List.of();
        var ids = new LinkedHashSet<String>();
        for (var value : root.getAsJsonArray("models")) {
            if (!value.isJsonObject()) continue;
            var model = value.getAsJsonObject();
            if (model.has("visibility") && !"list".equals(model.get("visibility").getAsString())) continue;
            if (!model.has("slug") || !model.get("slug").isJsonPrimitive()) continue;
            var id = model.get("slug").getAsString().strip();
            if (!id.isBlank()) ids.add(id);
        }
        return List.copyOf(ids);
    }
}
