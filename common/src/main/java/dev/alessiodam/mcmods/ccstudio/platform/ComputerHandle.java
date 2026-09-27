package dev.alessiodam.mcmods.ccstudio.platform;

import org.jspecify.annotations.Nullable;

public interface ComputerHandle {
    int id();

    @Nullable String label();

    boolean isOn();

    boolean isCommandComputer();

    String family();

    TerminalSnapshot terminal();

    ComputerInput createInput();

    void turnOn();

    void shutdown();

    void reboot();

    void queueEvent(String event);
}
