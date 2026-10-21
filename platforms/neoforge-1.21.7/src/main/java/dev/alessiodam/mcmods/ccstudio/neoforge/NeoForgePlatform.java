package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerStorage;
import dev.alessiodam.mcmods.ccstudio.platform.Platform;
import dev.alessiodam.mcmods.ccstudio.platform.ReadOnlyStorage;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Supplier;

final class NeoForgePlatform implements Platform {
    private final MinecraftServer server;
    private final String version;

    NeoForgePlatform(MinecraftServer server, String version) {
        this.server = server;
        this.version = version;
    }

    @Override
    public String modVersion() {
        return version;
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public @Nullable String serverAddress() {
        return server.getLocalIp();
    }

    @Override
    public @Nullable ComputerHandle resolve(int computerId, @Nullable ComputerHandle current, boolean search) {
        if (current instanceof CCComputer computer && Computers.registry(server).get(computer.computer.getInstanceUUID()) == computer.computer) {
            return current;
        }
        if (!search) return null;
        var found = Computers.findById(server, computerId);
        return found == null ? null : new CCComputer(found);
    }

    @Override
    public ComputerStorage openStorage(ComputerHandle computer, Supplier<@Nullable ComputerHandle> current) {
        return new CCStorage(server, (CCComputer) computer, current);
    }

    @Override
    public @Nullable ReadOnlyStorage openRom() {
        var mount = ComputerCraftAPI.createResourceMount(server, "computercraft", "lua/rom");
        return mount == null ? null : new CCStorage.Rom(mount);
    }

    @Override
    public int sendSessionLink(ComputerHandle computer, String url) {
        return ChatMessages.sendLink(server, ((CCComputer) computer).computer, url);
    }

    @Override
    public void sendPairingNotice(ComputerHandle computer, String command, String address, String browser) {
        ChatMessages.sendPairingNotice(server, ((CCComputer) computer).computer, command, address, browser);
    }
}
