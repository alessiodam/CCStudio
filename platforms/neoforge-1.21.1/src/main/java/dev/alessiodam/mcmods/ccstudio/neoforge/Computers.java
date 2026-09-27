package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerComputerRegistry;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.computer.menu.ComputerMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class Computers {
    private Computers() {
    }

    static ServerComputerRegistry registry(MinecraftServer server) {
        return ServerContext.get(server).registry();
    }

    static @Nullable ServerComputer find(IComputerSystem system) {
        var level = system.getLevel();
        var position = system.getPosition();
        ServerComputer fallback = null;
        for (var computer : registry(level.getServer()).getComputers()) {
            if (computer.getID() != system.getID()) continue;
            if (computer.getLevel() == level && computer.getPosition().equals(position)) return computer;
            if (fallback == null) fallback = computer;
        }
        return fallback;
    }

    static @Nullable ServerComputer findById(MinecraftServer server, int id) {
        for (var computer : registry(server).getComputers()) {
            if (computer.getID() == id) return computer;
        }
        return null;
    }

    static List<ServerPlayer> viewers(MinecraftServer server, ServerComputer computer) {
        var viewers = new ArrayList<ServerPlayer>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ComputerMenu menu && menu.getComputer() == computer) viewers.add(player);
        }
        return viewers;
    }
}
