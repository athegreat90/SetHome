package de.alexandermora.sethome.data

import com.electronwill.nightconfig.core.CommentedConfig
import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.core.io.ParsingException
import com.electronwill.nightconfig.core.io.WritingMode
import de.alexandermora.sethome.SetHomeMod
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class HomesFileRepository(configDir: Path) : HomesRepository {

    private val filePath: Path = configDir.resolve("sethome.toml")
    private val homes: MutableMap<UUID, MutableMap<String, HomeLocation>> = HashMap()

    @Synchronized
    override fun load() {
        ensureFileExists()
        homes.clear()

        try {
            openConfig().use { config ->
                config.load()

                val playersConfig = config.get<Any?>(PLAYERS_KEY) as? UnmodifiableConfig
                if (playersConfig == null) {
                    SetHomeMod.LOGGER.info("No persisted homes found in {}", filePath)
                    return
                }

                for (playerEntry in playersConfig.entrySet()) {
                    val playerId = try {
                        UUID.fromString(playerEntry.key)
                    } catch (ex: IllegalArgumentException) {
                        SetHomeMod.LOGGER.warn("Skipping invalid player UUID '{}' in {}", playerEntry.key, filePath)
                        continue
                    }

                    val playerHomesConfig = playerEntry.getValue<Any?>() as? UnmodifiableConfig ?: continue

                    val parsedHomes = HashMap<String, HomeLocation>()
                    for (homeEntry in playerHomesConfig.entrySet()) {
                        val homeName = normalizeHomeName(homeEntry.key)
                        val homeConfig = homeEntry.getValue<Any?>() as? UnmodifiableConfig ?: continue

                        try {
                            val dimension = requiredString(homeConfig, "dimension")
                            val x = requiredDouble(homeConfig, "x")
                            val y = requiredDouble(homeConfig, "y")
                            val z = requiredDouble(homeConfig, "z")
                            val yaw = requiredFloat(homeConfig, "yaw")
                            val pitch = requiredFloat(homeConfig, "pitch")

                            parsedHomes[homeName] = HomeLocation(dimension, x, y, z, yaw, pitch)
                        } catch (ex: RuntimeException) {
                            SetHomeMod.LOGGER.warn("Skipping malformed home '{}' for player {}", homeName, playerId, ex)
                        }
                    }

                    if (parsedHomes.isNotEmpty()) {
                        homes[playerId] = parsedHomes
                    }
                }

                SetHomeMod.LOGGER.info(
                    "Loaded {} home(s) for {} player(s) from {}",
                    homes.values.sumOf { it.size }, homes.size, filePath
                )
            }
        } catch (ex: ParsingException) {
            backupAndResetBrokenFile(ex)
        }
    }

    @Synchronized
    fun save() {
        ensureFileExists()

        try {
            openConfig().use { config ->
                config.clear()
                val playersConfig = CommentedConfig.inMemory()
                for ((playerId, playerHomes) in homes) {
                    val playerHomesConfig = CommentedConfig.inMemory()

                    for ((homeName, home) in playerHomes) {
                        val homeConfig = CommentedConfig.inMemory()
                        homeConfig.set<Any>("dimension", HomeLocation.normalizeDimension(home.dimension))
                        homeConfig.set<Any>("x", home.x)
                        homeConfig.set<Any>("y", home.y)
                        homeConfig.set<Any>("z", home.z)
                        homeConfig.set<Any>("yaw", home.yaw)
                        homeConfig.set<Any>("pitch", home.pitch)
                        playerHomesConfig.set<Any>(normalizeHomeName(homeName), homeConfig)
                    }

                    playersConfig.set<Any>(playerId.toString(), playerHomesConfig)
                }

                config.set<Any>(PLAYERS_KEY, playersConfig)
                config.setComment(PLAYERS_KEY, "Persistent named homes, grouped by player UUID.")
                config.save()
            }
        } catch (ex: RuntimeException) {
            throw IllegalStateException("Failed to save homes to $filePath", ex)
        }
    }

    @Synchronized
    override fun setHome(playerId: UUID, homeName: String, location: HomeLocation): Boolean {
        val normalizedName = normalizeHomeName(homeName)
        require(normalizedName.isNotBlank()) { "Home name cannot be blank" }

        val playerHomes = homes.getOrPut(playerId) { HashMap() }
        if (playerHomes.containsKey(normalizedName)) {
            return false
        }

        playerHomes[normalizedName] = location
        save()
        return true
    }

    @Synchronized
    override fun getHome(playerId: UUID, homeName: String): HomeLocation? =
        homes[playerId]?.get(normalizeHomeName(homeName))

    @Synchronized
    override fun getHomes(playerId: UUID): Set<String> =
        (homes[playerId]?.keys ?: emptySet()).toSortedSet()

    @Synchronized
    override fun deleteHome(playerId: UUID, homeName: String): Boolean {
        val playerHomes = homes[playerId] ?: return false
        if (playerHomes.remove(normalizeHomeName(homeName)) == null) {
            return false
        }

        if (playerHomes.isEmpty()) {
            homes.remove(playerId)
        }
        save()
        return true
    }

    @Synchronized
    override fun countHomes(playerId: UUID): Int = homes[playerId]?.size ?: 0

    @Synchronized
    override fun close() {
        // Nothing to release: state is flushed to disk on every mutation already.
    }

    @Synchronized
    fun exportAll(): Map<UUID, Map<String, HomeLocation>> =
        homes.mapValues { HashMap(it.value) }

    private fun ensureFileExists() {
        try {
            Files.createDirectories(filePath.parent)
            if (Files.notExists(filePath)) {
                Files.writeString(
                    filePath, "# SetHome persistent data file\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
                )
            }
        } catch (ex: IOException) {
            throw IllegalStateException("Failed to create homes file $filePath", ex)
        }
    }

    private fun openConfig(): CommentedFileConfig =
        CommentedFileConfig.builder(filePath)
            .sync()
            .writingMode(WritingMode.REPLACE)
            .build()

    private fun backupAndResetBrokenFile(cause: ParsingException) {
        try {
            val timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP)
            val backup = filePath.resolveSibling("sethome.toml.broken-$timestamp")
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(
                filePath, "# SetHome persistent data file\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE
            )
            homes.clear()
            SetHomeMod.LOGGER.error("The homes file was malformed. It was backed up to {} and reset.", backup, cause)
        } catch (ex: IOException) {
            throw IllegalStateException("Failed to back up malformed homes file $filePath", cause)
        }
    }

    companion object {
        private const val PLAYERS_KEY = "players"
        private val BACKUP_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private fun requiredString(config: UnmodifiableConfig, key: String): String {
            val value = config.get<Any?>(key) ?: throw IllegalArgumentException("Missing field: $key")
            return value.toString()
        }

        private fun requiredDouble(config: UnmodifiableConfig, key: String): Double {
            val value = config.get<Any?>(key)
            if (value is Number) {
                return value.toDouble()
            }
            if (value == null) {
                throw IllegalArgumentException("Missing field: $key")
            }
            return value.toString().toDouble()
        }

        private fun requiredFloat(config: UnmodifiableConfig, key: String): Float {
            val value = config.get<Any?>(key)
            if (value is Number) {
                return value.toFloat()
            }
            if (value == null) {
                throw IllegalArgumentException("Missing field: $key")
            }
            return value.toString().toFloat()
        }

        private fun normalizeHomeName(input: String?): String = input?.trim()?.lowercase(Locale.ROOT) ?: ""
    }
}
