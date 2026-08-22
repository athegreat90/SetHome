package de.alexandermora.sethome.data;

import de.alexandermora.sethome.config.SetHomeConfig;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class HomeStorageService {

    private static HomesFileRepository fileRepository;

    private HomeStorageService() {
    }

    public static synchronized void initialize(Path configDir) {
        if (fileRepository == null) {
            fileRepository = new HomesFileRepository(configDir);
            fileRepository.load();
        }
    }

    public static boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        HomesFileRepository repository = repository();

        HomeLocation existing = repository.getHome(playerId, homeName);
        if (existing == null && repository.countHomes(playerId) >= SetHomeConfig.MAX_HOMES_PER_PLAYER.get()) {
            throw new IllegalStateException("Maximum number of homes reached.");
        }

        return repository.setHome(playerId, homeName, location);
    }

    public static HomeLocation getHome(UUID playerId, String homeName) {
        return repository().getHome(playerId, homeName);
    }

    public static Set<String> getHomes(UUID playerId) {
        return repository().getHomes(playerId);
    }

    public static boolean deleteHome(UUID playerId, String homeName) {
        return repository().deleteHome(playerId, homeName);
    }

    private static HomesFileRepository repository() {
        return Objects.requireNonNull(fileRepository, "HomeStorageService has not been initialized yet.");
    }
}
