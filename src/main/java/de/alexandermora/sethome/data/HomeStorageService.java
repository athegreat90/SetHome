package de.alexandermora.sethome.data;

import de.alexandermora.sethome.config.SetHomeConfig;
import de.alexandermora.sethome.config.StorageMode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static de.alexandermora.sethome.SetHomeMod.LOGGER;

public final class HomeStorageService {

    private static HomesRepository repository;

    private HomeStorageService() {
    }

    public static synchronized void initialize(Path configDir) {
        if (repository != null) {
            return;
        }

        StorageMode mode = SetHomeConfig.STORAGE_MODE.get();
        HomesFileRepository fileRepository = null;
        HomesRepository selected;

        if (mode == StorageMode.FILE) {
            fileRepository = new HomesFileRepository(configDir);
            fileRepository.load();
            selected = fileRepository;
        } else {
            HomesRepository dbRepository = createDbRepository(mode, configDir);
            try {
                dbRepository.load();
                selected = dbRepository;
            } catch (RuntimeException ex) {
                LOGGER.error("Failed to initialize {} storage backend; falling back to FILE.", mode, ex);
                fileRepository = new HomesFileRepository(configDir);
                fileRepository.load();
                selected = fileRepository;
            }
        }

        if (SetHomeConfig.MIGRATE_FROM_FILE.get() && selected != fileRepository) {
            HomesFileRepository migrationSource = loadFileRepositoryForMigration(configDir);
            HomeMigration.migrate(migrationSource, selected);
        }

        repository = selected;
    }

    public static boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        HomesRepository repo = repository();

        HomeLocation existing = repo.getHome(playerId, homeName);
        if (existing == null && repo.countHomes(playerId) >= SetHomeConfig.MAX_HOMES_PER_PLAYER.get()) {
            throw new IllegalStateException("Maximum number of homes reached.");
        }

        return repo.setHome(playerId, homeName, location);
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

    public static synchronized void shutdown() {
        if (repository != null) {
            repository.close();
            repository = null;
        }
    }

    private static HomesRepository createDbRepository(StorageMode mode, Path configDir) {
        return switch (mode) {
            case MONGODB -> new HomesMongoRepository(
                    SetHomeConfig.MONGODB_URI.get(),
                    SetHomeConfig.MONGODB_DATABASE.get(),
                    SetHomeConfig.MONGODB_COLLECTION.get());
            case SQLITE -> new HomesSqliteRepository(configDir.resolve(SetHomeConfig.SQLITE_FILE_NAME.get()));
            case FILE -> throw new IllegalStateException("FILE mode does not use createDbRepository");
        };
    }

    private static HomesFileRepository loadFileRepositoryForMigration(Path configDir) {
        HomesFileRepository migrationSource = new HomesFileRepository(configDir);
        migrationSource.load();
        return migrationSource;
    }

    private static HomesRepository repository() {
        return Objects.requireNonNull(repository, "HomeStorageService has not been initialized yet.");
    }
}