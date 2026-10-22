package com.codexgui.conversation;

import com.codexgui.i18n.Messages;

import java.util.Objects;

public record SessionId(String value) {
    public SessionId {
        value = Objects.requireNonNullElse(value, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(Messages.message("Session ID 不能为空"));
    }

    public static SessionId of(String value) {
        return new SessionId(value == null || value.isBlank() ? "default" : value);
    }
}
