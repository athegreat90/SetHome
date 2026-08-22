package de.alexandermora.sethome;

import de.alexandermora.sethome.command.HomeCommands;
import de.alexandermora.sethome.config.SetHomeConfig;
import de.alexandermora.sethome.data.HomeStorageService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(SetHomeMod.MOD_ID)
public final class SetHomeMod {

    public static final String MOD_ID = "sethome";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public SetHomeMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(
                ModConfig.Type.COMMON,
                SetHomeConfig.SPEC,
                Path.of(MOD_ID, "sethome-common.toml").toString()
        );

        NeoForge.EVENT_BUS.addListener(HomeCommands::register);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void onServerStarting(ServerStartingEvent event) {
        HomeStorageService.initialize(Path.of("config", MOD_ID));
    }
}
