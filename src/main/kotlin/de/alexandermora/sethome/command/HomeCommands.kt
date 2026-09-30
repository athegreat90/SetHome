package de.alexandermora.sethome.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import de.alexandermora.sethome.SetHomeMod
import de.alexandermora.sethome.data.HomeLocation
import de.alexandermora.sethome.data.HomeStorageService
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.*

object HomeCommands {

    fun register(event: RegisterCommandsEvent) {
        val dispatcher: CommandDispatcher<CommandSourceStack> = event.dispatcher

        dispatcher.register(
            Commands.literal("sethome")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { context ->
                            setHome(context.source, StringArgumentType.getString(context, "name"))
                        }
                )
        )

        dispatcher.register(
            Commands.literal("home")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { context ->
                            teleportHome(context.source, StringArgumentType.getString(context, "name"))
                        }
                )
        )

        dispatcher.register(
            Commands.literal("delhome")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { context ->
                            deleteHome(context.source, StringArgumentType.getString(context, "name"))
                        }
                )
        )

        dispatcher.register(
            Commands.literal("homes")
                .executes { context -> listHomes(context.source) }
        )
    }

    private fun setHome(source: CommandSourceStack, rawName: String): Int {
        val player = requirePlayer(source) ?: return 0

        val name = normalizeHomeName(rawName)
        if (name.isBlank()) {
            source.sendFailure(Component.literal("Home name cannot be empty."))
            return 0
        }

        val level: ServerLevel = player.level()
        val location = HomeLocation(
            dimension = level.dimension().identifier().toString(),
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot
        )

        runCatching {
            val created = HomeStorageService.setHome(player.getUUID(), name, location)
            if (!created) {
                source.sendFailure(
                    Component.literal("Home '$name' already exists. Delete it first with /delhome $name.")
                )
                return 0
            }
        }.onFailure { ex ->
            when (ex) {
                is IllegalStateException -> {
                    source.sendFailure(Component.literal(ex.message ?: "Failed to save home '$name'."))
                    return 0
                }
                is RuntimeException -> {
                    SetHomeMod.LOGGER.error("Failed to save home '{}' for {}", name, player.getUUID(), ex)
                    source.sendFailure(Component.literal("Failed to save home '$name'."))
                    return 0
                }
                else -> throw ex
            }
        }

        source.sendSuccess({ Component.literal("Home '$name' saved.") }, false)
        return 1
    }

    private fun teleportHome(source: CommandSourceStack, rawName: String): Int {
        val player = requirePlayer(source) ?: return 0

        val name = normalizeHomeName(rawName)
        val home = HomeStorageService.getHome(player.getUUID(), name)
        if (home == null) {
            source.sendFailure(Component.literal("Home '$name' not found."))
            return 0
        }

        val server = player.level().server
        val targetLevel: ServerLevel? = runCatching {
            val dimensionKey = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.parse(HomeLocation.normalizeDimension(home.dimension))
            )
            server.getLevel(dimensionKey)
        }.onFailure { ex ->
            if (ex !is RuntimeException) throw ex
            SetHomeMod.LOGGER.warn("Invalid dimension '{}' for home '{}'", home.dimension, name, ex)
            source.sendFailure(Component.literal("Home '$name' has an invalid dimension."))
            return 0
        }.getOrThrow()

        if (targetLevel == null) {
            source.sendFailure(Component.literal("The dimension for home '$name' is not currently available."))
            return 0
        }

        runCatching {
            val teleported = player.teleportTo(
                targetLevel,
                home.x,
                home.y,
                home.z,
                setOf(),
                home.yaw,
                home.pitch,
                true
            )

            if (!teleported) {
                source.sendFailure(Component.literal("Minecraft rejected the teleport to home '$name'."))
                return 0
            }
        }.onFailure { ex ->
            if (ex !is RuntimeException) throw ex
            SetHomeMod.LOGGER.error("Failed to teleport {} to home '{}'", player.getUUID(), name, ex)
            source.sendFailure(Component.literal("Failed to teleport to home '$name'."))
            return 0
        }

        source.sendSuccess({ Component.literal("Teleported to home '$name'.") }, false)
        return 1
    }

    private fun deleteHome(source: CommandSourceStack, rawName: String): Int {
        val player = requirePlayer(source) ?: return 0

        val name = normalizeHomeName(rawName)
        runCatching {
            if (!HomeStorageService.deleteHome(player.getUUID(), name)) {
                source.sendFailure(Component.literal("Home '$name' not found."))
                return 0
            }
        }.onFailure { ex ->
            if (ex !is RuntimeException) throw ex
            SetHomeMod.LOGGER.error("Failed to delete home '{}' for {}", name, player.getUUID(), ex)
            source.sendFailure(Component.literal("Failed to delete home '$name'."))
            return 0
        }

        source.sendSuccess({ Component.literal("Home '$name' deleted.") }, false)
        return 1
    }

    private fun listHomes(source: CommandSourceStack): Int {
        val player = requirePlayer(source) ?: return 0

        val homes = HomeStorageService.getHomes(player.getUUID())
        if (homes.isEmpty()) {
            source.sendSuccess({ Component.literal("You have no homes.") }, false)
            return 1
        }

        source.sendSuccess({ Component.literal("Homes: " + homes.joinToString(", ")) }, false)
        return homes.size
    }

    private fun normalizeHomeName(input: String?): String = input?.trim()?.lowercase(Locale.ROOT) ?: ""

    private fun requirePlayer(source: CommandSourceStack): ServerPlayer? {
        val player = runCatching { source.playerOrException }
            .onFailure { ex -> if (ex !is Exception) throw ex }
            .getOrNull()
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by a player."))
        }
        return player
    }
}
