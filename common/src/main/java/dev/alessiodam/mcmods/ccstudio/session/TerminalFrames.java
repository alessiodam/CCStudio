package dev.alessiodam.mcmods.ccstudio.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.alessiodam.mcmods.ccstudio.platform.TerminalSnapshot;
import dev.alessiodam.mcmods.ccstudio.web.Json;

public final class TerminalFrames {
    private TerminalFrames() {
    }

    public static String encode(TerminalSnapshot terminal) {
        var frame = new JsonObject();
        frame.addProperty("event", "terminal");
        frame.addProperty("width", terminal.width());
        frame.addProperty("height", terminal.height());
        frame.addProperty("colour", terminal.colour());

        var cursor = new JsonObject();
        cursor.addProperty("x", terminal.cursorX());
        cursor.addProperty("y", terminal.cursorY());
        cursor.addProperty("blink", terminal.cursorBlink());
        cursor.addProperty("colour", Integer.toHexString(terminal.cursorColour() & 15));
        frame.add("cursor", cursor);

        var palette = new JsonArray();
        for (var colour : terminal.palette()) palette.add(String.format("%06x", colour & 0xFFFFFF));
        frame.add("palette", palette);

        var text = new JsonArray();
        var foreground = new JsonArray();
        var background = new JsonArray();
        for (var y = 0; y < terminal.height(); y++) {
            text.add(terminal.text()[y]);
            foreground.add(terminal.foreground()[y]);
            background.add(terminal.background()[y]);
        }
        frame.add("text", text);
        frame.add("fg", foreground);
        frame.add("bg", background);
        return Json.write(frame);
    }

    public static String unloaded() {
        var frame = new JsonObject();
        frame.addProperty("event", "terminal");
        frame.addProperty("unloaded", true);
        return Json.write(frame);
    }
}
