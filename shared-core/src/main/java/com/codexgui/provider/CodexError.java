package com.codexgui.provider;

import com.codexgui.i18n.Messages;

import com.google.gson.JsonObject;

/** An upstream error may describe an automatic retry rather than a finished turn. */
public record CodexError(String message, boolean willRetry) {
    public static CodexError from(JsonObject params) {
        var retry = params != null && params.has("willRetry")
            && params.get("willRetry").isJsonPrimitive()
            && params.getAsJsonPrimitive("willRetry").isBoolean()
            && params.get("willRetry").getAsBoolean();
        var error = params != null && params.has("error") && params.get("error").isJsonObject()
            ? params.getAsJsonObject("error") : params;
        var message = text(error, "message");
        if (message.isBlank()) message = text(params, "message");
        if (message.isBlank()) message = retry ? Messages.message("Codex 正在重试连接") : Messages.message("Codex 请求失败");
        var details = text(error, "additionalDetails");
        if (details.isBlank()) details = text(params, "additionalDetails");
        if (!details.isBlank() && !message.contains(details)) message += "\n" + details;
        return new CodexError(message, retry);
    }

    private static String text(JsonObject source, String key) {
        return source != null && source.has(key) && source.get(key).isJsonPrimitive()
            && source.getAsJsonPrimitive(key).isString() ? source.get(key).getAsString() : "";
    }
}
