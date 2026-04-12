package de.alexandermora.sethome.data;

import de.alexandermora.sethome.config.SetHomeConfig;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class HomeStorageService {

    private static HomesFileRepository fileRepository;

    private HomeStorageService() {
    }

    public static void initialize(Path configDir) {
        if (fileRepository == null) {
            fileRepository = new HomesFileRepository(configDir);
            fileRepository.load();
        }
    }

    public static boolean setHome(MinecraftServer server, UUID playerId, String homeName, HomeLocation location) {
        ensureInitialized();

        int maxHomes = SetHomeConfig.MAX_HOMES_PER_PLAYER.get();
        HomeLocation existing = fileRepository.getHome(playerId, homeName);

        if (existing == null && fileRepository.countHomes(playerId) >= maxHomes) {
            throw new IllegalStateException("Maximum number of homes reached.");
        }

        return fileRepository.setHome(playerId, homeName, location);
    }

    public static HomeLocation getHome(MinecraftServer server, UUID playerId, String homeName) {
        ensureInitialized();
        return fileRepository.getHome(playerId, homeName);
    }

    public static Set<String> getHomes(MinecraftServer server, UUID playerId) {
        ensureInitialized();
        return fileRepository.getHomes(playerId);
    }

    public static boolean deleteHome(MinecraftServer server, UUID playerId, String homeName) {
        ensureInitialized();
        return fileRepository.deleteHome(playerId, homeName);
    }

    private static void ensureInitialized() {
        Objects.requireNonNull(fileRepository, "HomeStorageService has not been initialized yet.");
    }
}