package com.codexgui.conversation;

import com.codexgui.i18n.Messages;

public record TurnHandle(SessionId sessionId, TurnId turnId, long generation) {
    public TurnHandle {
        if (sessionId == null) throw new IllegalArgumentException(Messages.message("Turn 必须属于 Session"));
        if (turnId == null) throw new IllegalArgumentException(Messages.message("Turn ID 不能为空"));
        if (generation < 1) throw new IllegalArgumentException(Messages.message("Turn generation 必须大于零"));
    }
}
