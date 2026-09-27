package dev.alessiodam.mcmods.ccstudio.util;

import org.jspecify.annotations.Nullable;

public final class Names {
    private Names() {
    }

    public static String describe(int id, @Nullable String label) {
        return label == null || label.isBlank() ? "Computer #" + id : "Computer #" + id + " (" + label + ")";
    }

    public static String sanitize(String text) {
        var result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            var type = Character.getType(codePoint);
            var unsafe = Character.isISOControl(codePoint) || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.FORMAT || codePoint == '§';
            result.appendCodePoint(unsafe ? '?' : codePoint);
        });
        return result.toString();
    }
}
