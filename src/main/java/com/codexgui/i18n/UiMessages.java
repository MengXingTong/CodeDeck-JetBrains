package com.codexgui.i18n;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;

import java.util.Locale;

/** Resolves the IDE UI locale rather than the operating system locale. */
public final class UiMessages {
    static { Messages.setLocaleSupplier(UiMessages::locale); }

    private UiMessages() {}

    public static Locale locale() {
        return ApplicationManager.getApplication() == null ? Locale.getDefault() : DynamicBundle.getLocale();
    }

    public static String languageTag() {
        return Messages.languageTag(locale());
    }

    public static String message(String source) {
        return Messages.message(source, locale());
    }
}
