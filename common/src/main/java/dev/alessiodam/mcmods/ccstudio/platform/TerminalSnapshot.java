package dev.alessiodam.mcmods.ccstudio.platform;

public record TerminalSnapshot(
        int width,
        int height,
        boolean colour,
        int cursorX,
        int cursorY,
        boolean cursorBlink,
        int cursorColour,
        int[] palette,
        String[] text,
        String[] foreground,
        String[] background
) {
}
