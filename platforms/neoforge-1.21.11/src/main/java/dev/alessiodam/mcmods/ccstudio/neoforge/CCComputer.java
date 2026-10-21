package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerInput;
import dev.alessiodam.mcmods.ccstudio.platform.TerminalSnapshot;
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
        return new Input(computer.createComputerInput());
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

    private record Input(UserComputerInput input) implements ComputerInput {
        @Override
        public void keyDown(int key) {
            input.keyDown(key);
        }

        @Override
        public void keyUp(int key) {
            input.keyUp(key);
        }

        @Override
        public void codepointTyped(int codepoint) {
            input.codepointTyped(codepoint);
        }

        @Override
        public void paste(String text) {
            input.paste(text);
        }

        @Override
        public void mouseClick(int button, int x, int y) {
            input.mouseClick(button, x, y);
        }

        @Override
        public void mouseUp(int button, int x, int y) {
            input.mouseUp(button, x, y);
        }

        @Override
        public void mouseDrag(int button, int x, int y) {
            input.mouseDrag(button, x, y);
        }

        @Override
        public void mouseScroll(int direction, int x, int y) {
            input.mouseScroll(direction, x, y);
        }

        @Override
        public void releaseAll() {
            input.releaseInputs();
        }
    }
}
