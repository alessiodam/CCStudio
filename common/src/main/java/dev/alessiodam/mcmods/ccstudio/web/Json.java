package dev.alessiodam.mcmods.ccstudio.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public final class Json {
    private static final Gson PLAIN = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson HTML_SAFE = new Gson();

    private Json() {
    }

    public static String write(JsonElement element) {
        return PLAIN.toJson(element);
    }

    public static String writeHtmlSafe(JsonElement element) {
        return HTML_SAFE.toJson(element);
    }

    public static JsonObject parseObject(String text) {
        var element = JsonParser.parseString(text);
        if (!element.isJsonObject()) throw new JsonParseException("Expected a JSON object");
        return element.getAsJsonObject();
    }

    public static String string(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
    }
}
