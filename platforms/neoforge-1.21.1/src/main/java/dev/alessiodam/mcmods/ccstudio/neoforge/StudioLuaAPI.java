package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dev.alessiodam.mcmods.ccstudio.LuaApi;
import dev.alessiodam.mcmods.ccstudio.StudioException;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public final class StudioLuaAPI implements ILuaAPI {
    private final IComputerSystem system;

    public StudioLuaAPI(IComputerSystem system) {
        this.system = system;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "ccstudio" };
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> open() throws LuaException {
        if (!NeoForgeConfig.ENABLED.get()) throw new LuaException("CC: Studio is disabled on this server");
        var computer = Computers.find(system);
        if (computer == null) throw new LuaException("Cannot locate this computer");
        try {
            return LuaApi.open(new CCComputer(computer));
        } catch (StudioException e) {
            throw new LuaException(e.getMessage());
        }
    }

    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> pending() {
        return LuaApi.pending(system.getID());
    }

    @LuaFunction(mainThread = true)
    public final boolean trust(String code) {
        return LuaApi.trust(system.getID(), code);
    }

    @LuaFunction(mainThread = true)
    public final boolean close() {
        return LuaApi.close(system.getID());
    }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> status() {
        return LuaApi.status(system.getID());
    }

    @LuaFunction
    public final boolean isAvailable() {
        return LuaApi.isAvailable();
    }
}
