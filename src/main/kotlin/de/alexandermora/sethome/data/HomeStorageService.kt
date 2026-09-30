package de.alexandermora.sethome.data

import de.alexandermora.sethome.SetHomeMod
import de.alexandermora.sethome.config.SetHomeConfig
import de.alexandermora.sethome.config.StorageMode
import java.nio.file.Path
import java.util.UUID

object HomeStorageService {

    private var repository: HomesRepository? = null

    @Synchronized
    fun initialize(configDir: Path) {
        if (repository != null) {
            return
        }

        val mode = SetHomeConfig.STORAGE_MODE.get()
        var fileRepository: HomesFileRepository? = null
        val selected: HomesRepository

        if (mode == StorageMode.FILE) {
            val repo = HomesFileRepository(configDir)
            repo.load()
            fileRepository = repo
            selected = repo
        } else {
            val dbRepository = createDbRepository(mode, configDir)
            selected = runCatching {
                dbRepository.load()
                dbRepository
            }.getOrElse { ex ->
                if (ex !is RuntimeException) throw ex
                SetHomeMod.LOGGER.error("Failed to initialize {} storage backend; falling back to FILE.", mode, ex)
                val repo = HomesFileRepository(configDir)
                repo.load()
                fileRepository = repo
                repo
            }
        }

        if (SetHomeConfig.MIGRATE_FROM_FILE.get() && selected !== fileRepository) {
            val migrationSource = loadFileRepositoryForMigration(configDir)
            HomeMigration.migrate(migrationSource, selected)
        }

        repository = selected
    }

    fun setHome(playerId: UUID, homeName: String, location: HomeLocation): Boolean {
        val repo = repository()

        val existing = repo.getHome(playerId, homeName)
        if (existing == null && repo.countHomes(playerId) >= SetHomeConfig.MAX_HOMES_PER_PLAYER.get()) {
            throw IllegalStateException("Maximum number of homes reached.")
        }

        return repo.setHome(playerId, homeName, location)
    }

    fun getHome(playerId: UUID, homeName: String): HomeLocation? = repository().getHome(playerId, homeName)

    fun getHomes(playerId: UUID): Set<String> = repository().getHomes(playerId)

    fun deleteHome(playerId: UUID, homeName: String): Boolean = repository().deleteHome(playerId, homeName)

    @Synchronized
    fun shutdown() {
        repository?.close()
        repository = null
    }

    private fun createDbRepository(mode: StorageMode, configDir: Path): HomesRepository = when (mode) {
        StorageMode.MONGODB -> HomesMongoRepository(
            SetHomeConfig.MONGODB_URI.get(),
            SetHomeConfig.MONGODB_DATABASE.get(),
            SetHomeConfig.MONGODB_COLLECTION.get()
        )
        StorageMode.SQLITE -> HomesSqliteRepository(configDir.resolve(SetHomeConfig.SQLITE_FILE_NAME.get()))
        StorageMode.FILE -> throw IllegalStateException("FILE mode does not use createDbRepository")
    }

    private fun loadFileRepositoryForMigration(configDir: Path): HomesFileRepository {
        val migrationSource = HomesFileRepository(configDir)
        migrationSource.load()
        return migrationSource
    }

    private fun repository(): HomesRepository =
        repository ?: throw NullPointerException("HomeStorageService has not been initialized yet.")
}
