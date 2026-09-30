package de.alexandermora.sethome

import de.alexandermora.sethome.command.HomeCommands
import de.alexandermora.sethome.config.SetHomeConfig
import de.alexandermora.sethome.data.HomeStorageService
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

@Mod(SetHomeMod.MOD_ID)
class SetHomeMod(modContainer: ModContainer) {

    init {
        modContainer.registerConfig(
            resolveConfigType(),
            SetHomeConfig.SPEC,
            Path.of(MOD_ID, "sethome-common.toml").toString()
        )

        NeoForge.EVENT_BUS.addListener(HomeCommands::register)
        NeoForge.EVENT_BUS.addListener(::onServerStarting)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        HomeStorageService.initialize(Path.of("config", MOD_ID))
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        HomeStorageService.shutdown()
    }

    companion object {
        const val MOD_ID = "sethome"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

        // FancyModLoader renamed ModConfig.Type.COMMON to LOCAL starting with loader 12.x (bundled from
        // Minecraft 26.3 onward); loader 11.x (26.1.2/26.2) only has COMMON. Resolving by name at runtime
        // lets one compiled jar register its config correctly across both loader generations.
        private fun resolveConfigType(): ModConfig.Type =
            try {
                ModConfig.Type.valueOf("LOCAL")
            } catch (e: IllegalArgumentException) {
                ModConfig.Type.valueOf("COMMON")
            }
    }
}
