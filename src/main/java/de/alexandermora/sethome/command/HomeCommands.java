package de.alexandermora.sethome.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.alexandermora.sethome.SetHomeMod;
import de.alexandermora.sethome.data.HomeLocation;
import de.alexandermora.sethome.data.HomeStorageService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;
import java.util.Set;

public final class HomeCommands {

    private HomeCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("sethome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> setHome(context.getSource(),
                                StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("home")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> teleportHome(context.getSource(),
                                StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> deleteHome(context.getSource(),
                                StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("homes")
                .executes(context -> listHomes(context.getSource())));
    }

    private static int setHome(CommandSourceStack source, String rawName) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }

        String name = normalizeHomeName(rawName);
        if (name.isBlank()) {
            source.sendFailure(Component.literal("Home name cannot be empty."));
            return 0;
        }

        ServerLevel level = player.level();
        HomeLocation location = new HomeLocation(
                level.dimension().identifier().toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
        );

        try {
            boolean created = HomeStorageService.setHome(player.getUUID(), name, location);
            if (!created) {
                source.sendFailure(Component.literal("Home '" + name + "' already exists. Delete it first with /delhome " + name + "."));
                return 0;
            }
        } catch (IllegalStateException ex) {
            source.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        } catch (RuntimeException ex) {
            SetHomeMod.LOGGER.error("Failed to save home '{}' for {}", name, player.getUUID(), ex);
            source.sendFailure(Component.literal("Failed to save home '" + name + "'."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Home '" + name + "' saved."), false);
        return 1;
    }

    private static int teleportHome(CommandSourceStack source, String rawName) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }

        String name = normalizeHomeName(rawName);
        HomeLocation home = HomeStorageService.getHome(player.getUUID(), name);
        if (home == null) {
            source.sendFailure(Component.literal("Home '" + name + "' not found."));
            return 0;
        }

        MinecraftServer server = player.level().getServer();
        ServerLevel targetLevel;
        try {
            ResourceKey<Level> dimensionKey = ResourceKey.create(
                    Registries.DIMENSION,
                    Identifier.parse(HomeLocation.normalizeDimension(home.dimension()))
            );
            targetLevel = server.getLevel(dimensionKey);
        } catch (RuntimeException ex) {
            SetHomeMod.LOGGER.warn("Invalid dimension '{}' for home '{}'", home.dimension(), name, ex);
            source.sendFailure(Component.literal("Home '" + name + "' has an invalid dimension."));
            return 0;
        }

        if (targetLevel == null) {
            source.sendFailure(Component.literal("The dimension for home '" + name + "' is not currently available."));
            return 0;
        }

        try {
            boolean teleported = player.teleportTo(
                    targetLevel,
                    home.x(),
                    home.y(),
                    home.z(),
                    Set.<Relative>of(),
                    home.yaw(),
                    home.pitch(),
                    true
            );

            if (!teleported) {
                source.sendFailure(Component.literal("Minecraft rejected the teleport to home '" + name + "'."));
                return 0;
            }
        } catch (RuntimeException ex) {
            SetHomeMod.LOGGER.error("Failed to teleport {} to home '{}'", player.getUUID(), name, ex);
            source.sendFailure(Component.literal("Failed to teleport to home '" + name + "'."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Teleported to home '" + name + "'."), false);
        return 1;
    }

    private static int deleteHome(CommandSourceStack source, String rawName) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }

        String name = normalizeHomeName(rawName);
        try {
            if (!HomeStorageService.deleteHome(player.getUUID(), name)) {
                source.sendFailure(Component.literal("Home '" + name + "' not found."));
                return 0;
            }
        } catch (RuntimeException ex) {
            SetHomeMod.LOGGER.error("Failed to delete home '{}' for {}", name, player.getUUID(), ex);
            source.sendFailure(Component.literal("Failed to delete home '" + name + "'."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Home '" + name + "' deleted."), false);
        return 1;
    }

    private static int listHomes(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }

        Set<String> homes = HomeStorageService.getHomes(player.getUUID());
        if (homes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("You have no homes."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Homes: " + String.join(", ", homes)), false);
        return homes.size();
    }

    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
