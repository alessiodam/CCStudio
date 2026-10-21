package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.core.computer.ComputerEvents;
import dan200.computercraft.core.util.StringUtil;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerInput;
import dev.alessiodam.mcmods.ccstudio.platform.TerminalSnapshot;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

final class CCComputer implements ComputerHandle {
    final ServerComputer computer;

    CCComputer(ServerComputer computer) {
        this.computer = computer;
    }

    @Override
    public int id() {
        return computer.getID();
    }

    @Override
    public @Nullable String label() {
        return computer.getLabel();
    }

    @Override
    public boolean isOn() {
        return computer.isOn();
    }

    @Override
    public boolean isCommandComputer() {
        return computer.getFamily() == ComputerFamily.COMMAND;
    }

    @Override
    public String family() {
        return computer.getFamily().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TerminalSnapshot terminal() {
        var terminal = computer.getTerminalState().create();
        var palette = new int[16];
        for (var digit = 0; digit < 16; digit++) palette[digit] = terminal.getPalette().getRenderColours(15 - digit) & 0xFFFFFF;
        var height = terminal.getHeight();
        var text = new String[height];
        var foreground = new String[height];
        var background = new String[height];
        for (var y = 0; y < height; y++) {
            text[y] = terminal.getLine(y).toString();
            foreground[y] = terminal.getTextColourLine(y).toString();
            background[y] = terminal.getBackgroundColourLine(y).toString();
        }
        return new TerminalSnapshot(
                terminal.getWidth(), height, terminal.isColour(),
                terminal.getCursorX(), terminal.getCursorY(), terminal.getCursorBlink(), terminal.getTextColour(),
                palette, text, foreground, background
        );
    }

    @Override
    public ComputerInput createInput() {
        var terminal = computer.getTerminalState().create();
        return new Input(computer, terminal.isColour(), terminal.getWidth(), terminal.getHeight());
    }

    @Override
    public void turnOn() {
        computer.turnOn();
    }

    @Override
    public void shutdown() {
        computer.shutdown();
    }

    @Override
    public void reboot() {
        computer.reboot();
    }

    @Override
    public void queueEvent(String event) {
        computer.queueEvent(event);
    }

    private static final class Input implements ComputerInput {
        private final ServerComputer computer;
        private final boolean mouseSupport;
        private final int width;
        private final int height;
        private final IntSet keysDown = new IntOpenHashSet();
        private int lastMouseX;
        private int lastMouseY;
        private int lastMouseDown = -1;

        Input(ServerComputer computer, boolean mouseSupport, int width, int height) {
            this.computer = computer;
            this.mouseSupport = mouseSupport;
            this.width = width;
            this.height = height;
        }

        @Override
        public void keyDown(int key) {
            if (key < 0) return;
            ComputerEvents.keyDown(computer, key, !keysDown.add(key));
        }

        @Override
        public void keyUp(int key) {
            if (key < 0) return;
            keysDown.remove(key);
            ComputerEvents.keyUp(computer, key);
        }

        @Override
        public void codepointTyped(int codepoint) {
            var character = StringUtil.unicodeToTerminal(codepoint);
            if (StringUtil.isTypableChar(character)) ComputerEvents.charTyped(computer, (byte) character);
        }

        @Override
        public void paste(String text) {
            var clipboard = StringUtil.getClipboardString(text);
            if (!clipboard.hasRemaining()) return;
            for (var i = clipboard.position(); i < clipboard.limit(); i++) {
                if (!StringUtil.isTypableChar(clipboard.get(i) & 0xFF)) return;
            }
            ComputerEvents.paste(computer, clipboard);
        }

        @Override
        public void mouseClick(int button, int x, int y) {
            if (!mouseSupport || button < 1 || button > 3) return;
            lastMouseX = clamp(x, width);
            lastMouseY = clamp(y, height);
            lastMouseDown = button;
            ComputerEvents.mouseClick(computer, button, lastMouseX, lastMouseY);
        }

        @Override
        public void mouseUp(int button, int x, int y) {
            if (!mouseSupport || button < 1 || button > 3) return;
            lastMouseX = clamp(x, width);
            lastMouseY = clamp(y, height);
            if (lastMouseDown != button) return;
            ComputerEvents.mouseUp(computer, button, lastMouseX, lastMouseY);
            lastMouseDown = -1;
        }

        @Override
        public void mouseDrag(int button, int x, int y) {
            if (!mouseSupport || button < 1 || button > 3) return;
            x = clamp(x, width);
            y = clamp(y, height);
            if (button != lastMouseDown || (x == lastMouseX && y == lastMouseY)) return;
            lastMouseX = x;
            lastMouseY = y;
            ComputerEvents.mouseDrag(computer, button, x, y);
        }

        @Override
        public void mouseScroll(int direction, int x, int y) {
            if (!mouseSupport || direction == 0) return;
            lastMouseX = clamp(x, width);
            lastMouseY = clamp(y, height);
            ComputerEvents.mouseScroll(computer, direction, lastMouseX, lastMouseY);
        }

        @Override
        public void releaseAll() {
            for (var key : keysDown) ComputerEvents.keyUp(computer, key);
            keysDown.clear();
            if (lastMouseDown != -1) ComputerEvents.mouseUp(computer, lastMouseDown, lastMouseX, lastMouseY);
            lastMouseDown = -1;
        }

        private static int clamp(int value, int max) {
            return Math.min(Math.max(value, 1), max);
        }
    }
}
