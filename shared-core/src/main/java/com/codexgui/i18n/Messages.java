package com.codexgui.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Plugin-owned text only. Never pass conversation content, paths, or saved user instructions here. */
public final class Messages {
    private static final Map<String, String> ENGLISH = loadEnglish();
    private static volatile Supplier<Locale> localeSupplier = Locale::getDefault;

    private Messages() {}

    public static void setLocaleSupplier(Supplier<Locale> supplier) {
        localeSupplier = Objects.requireNonNull(supplier);
    }

    public static String languageTag(Locale locale) {
        return "zh".equals(locale.getLanguage()) ? "zh-CN" : "en";
    }

    public static String message(String source) {
        return message(source, localeSupplier.get());
    }

    public static String message(String source, Locale locale) {
        return "zh".equals(locale.getLanguage()) ? source : ENGLISH.getOrDefault(source, source);
    }

    private static Map<String, String> loadEnglish() {
        try (var input = Messages.class.getResourceAsStream("/i18n/en.json")) {
            if (input == null) throw new IllegalStateException("Missing CodeDeck English translations");
            return Map.copyOf(new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType()));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read CodeDeck English translations", error);
        }
    }
}
