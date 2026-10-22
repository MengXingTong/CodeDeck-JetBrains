package com.codexgui.conversation;

import com.codexgui.i18n.Messages;

import java.util.Objects;

public record TurnId(String value) {
    public TurnId {
        value = Objects.requireNonNullElse(value, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(Messages.message("Turn ID 不能为空"));
    }
}
