package dev.alessiodam.mcmods.ccstudio.platform;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Supplier;

public interface Platform {
    String modVersion();

    Path gameDirectory();

    @Nullable String serverAddress();

    @Nullable ComputerHandle resolve(int computerId, @Nullable ComputerHandle current, boolean search);

    ComputerStorage openStorage(ComputerHandle computer, Supplier<@Nullable ComputerHandle> current);

    @Nullable ReadOnlyStorage openRom();

    int sendSessionLink(ComputerHandle computer, String url);

    void sendPairingNotice(ComputerHandle computer, String command, String address, String browser);
}
