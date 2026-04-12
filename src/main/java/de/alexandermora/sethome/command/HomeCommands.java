package de.alexandermora.sethome.command;


import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.alexandermora.sethome.SetHomeMod;
import de.alexandermora.sethome.data.HomeLocation;
import de.alexandermora.sethome.data.HomeStorageService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

public final class HomeCommands {

    private HomeCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        try
        {
            dispatcher.register(
                    Commands.literal("sethome")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        String rawName = StringArgumentType.getString(ctx, "name");
                                        String name = normalizeHomeName(rawName);

                                        if (name.isBlank()) {
                                            ctx.getSource().sendFailure(Component.literal("Home name cannot be empty."));
                                            return 0;
                                        }

                                        ServerLevel level = (ServerLevel) player.level();
                                        MinecraftServer server = level.getServer();

                                        HomeLocation location = new HomeLocation(
                                                level.dimension(),
                                                player.getX(),
                                                player.getY(),
                                                player.getZ(),
                                                player.getYRot(),
                                                player.getXRot()
                                        );

                                        boolean created;
                                        try {
                                            created = HomeStorageService.setHome(
                                                    server,
                                                    player.getUUID(),
                                                    name,
                                                    location
                                            );
                                        } catch (IllegalStateException ex) {
                                            ctx.getSource().sendFailure(Component.literal(ex.getMessage()));
                                            return 0;
                                        } catch (Exception ex) {
                                            ctx.getSource().sendFailure(Component.literal("Failed to save home '" + name + "'."));
                                            return 0;
                                        }

                                        if (!created) {
                                            ctx.getSource().sendFailure(Component.literal("Home '" + name + "' already exists."));
                                            return 0;
                                        }

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("Home '" + name + "' saved."),
                                                false
                                        );
                                        return 1;
                                    }))
            );

            dispatcher.register(
                    Commands.literal("home")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        String rawName = StringArgumentType.getString(ctx, "name");
                                        String name = normalizeHomeName(rawName);

                                        if (name.isBlank()) {
                                            ctx.getSource().sendFailure(Component.literal("Home name cannot be empty."));
                                            return 0;
                                        }

                                        ServerLevel level = (ServerLevel) player.level();
                                        MinecraftServer server = level.getServer();

                                        HomeLocation home;
                                        try {
                                            home = HomeStorageService.getHome(
                                                    server,
                                                    player.getUUID(),
                                                    name
                                            );
                                        } catch (Exception ex) {
                                            ctx.getSource().sendFailure(Component.literal("Failed to load home '" + name + "'."));
                                            return 0;
                                        }

                                        if (home == null) {
                                            ctx.getSource().sendFailure(Component.literal("Home '" + name + "' not found."));
                                            return 0;
                                        }

                                        ServerLevel targetLevel = server.getLevel(home.dimension());
                                        if (targetLevel == null) {
                                            ctx.getSource().sendFailure(Component.literal("Target dimension is unavailable."));
                                            return 0;
                                        }

                                        player.teleportTo(
                                                targetLevel,
                                                home.x(),
                                                home.y(),
                                                home.z(),
                                                home.yaw(),
                                                home.pitch()
                                        );

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("Teleported to home '" + name + "'."),
                                                false
                                        );
                                        return 1;
                                    }))
            );

            dispatcher.register(
                    Commands.literal("delhome")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        String rawName = StringArgumentType.getString(ctx, "name");
                                        String name = normalizeHomeName(rawName);

                                        if (name.isBlank()) {
                                            ctx.getSource().sendFailure(Component.literal("Home name cannot be empty."));
                                            return 0;
                                        }

                                        ServerLevel level = (ServerLevel) player.level();
                                        MinecraftServer server = level.getServer();

                                        boolean removed;
                                        try {
                                            removed = HomeStorageService.deleteHome(
                                                    server,
                                                    player.getUUID(),
                                                    name
                                            );
                                        } catch (Exception ex) {
                                            ctx.getSource().sendFailure(Component.literal("Failed to delete home '" + name + "'."));
                                            return 0;
                                        }

                                        if (!removed) {
                                            ctx.getSource().sendFailure(Component.literal("Home '" + name + "' not found."));
                                            return 0;
                                        }

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("Home '" + name + "' deleted."),
                                                false
                                        );
                                        return 1;
                                    }))
            );

            dispatcher.register(
                    Commands.literal("homes")
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                ServerLevel level = (ServerLevel) player.level();
                                MinecraftServer server = level.getServer();

                                java.util.Set<String> homes = HomeStorageService.getHomes(server, player.getUUID());

                                if (homes.isEmpty()) {
                                    ctx.getSource().sendFailure(Component.literal("You have no homes."));
                                    return 0;
                                }

                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Homes: " + String.join(", ", homes)),
                                        false
                                );
                                return homes.size();
                            })
            );
        } catch (Exception e) {
            SetHomeMod.LOGGER.error("Failed to register commands!", e);
        }
    }

    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}