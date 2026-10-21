package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.StudioServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(CCStudio.MOD_ID)
public final class CCStudio {
    public static final String MOD_ID = "ccstudio";

    private final String version;

    public CCStudio(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.SPEC);
        modBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ComputerCraftAPI.registerAPIFactory(StudioLuaAPI::new));
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!NeoForgeConfig.ENABLED.get()) {
            Log.LOGGER.info("CC: Studio is disabled in the config");
            return;
        }
        StudioServer.start(new NeoForgePlatform(event.getServer(), version), NeoForgeConfig.snapshot());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        StudioServer.stop();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        var studio = StudioServer.get();
        if (studio != null) studio.tick();
    }
}
