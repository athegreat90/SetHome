package de.alexandermora.sethome.data

import de.alexandermora.sethome.SetHomeMod
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Locale
import java.util.UUID

class HomesSqliteRepository(private val dbFilePath: Path) : HomesRepository {

    private lateinit var connection: Connection

    @Synchronized
    override fun load() {
        runCatching {
            Files.createDirectories(dbFilePath.toAbsolutePath().parent)
        }.onFailure { ex ->
            if (ex !is IOException) throw ex
            throw IllegalStateException("Failed to create directory for SQLite database $dbFilePath", ex)
        }

        runCatching {
            connection = DriverManager.getConnection("jdbc:sqlite:$dbFilePath")
            connection.prepareStatement(CREATE_TABLE).use { it.execute() }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to open SQLite database $dbFilePath", ex)
        }
    }

    @Synchronized
    override fun setHome(playerId: UUID, homeName: String, location: HomeLocation): Boolean {
        val normalizedName = normalizeHomeName(homeName)
        require(normalizedName.isNotBlank()) { "Home name cannot be blank" }

        return runCatching {
            connection.prepareStatement(INSERT_HOME).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, normalizedName)
                statement.setString(3, HomeLocation.normalizeDimension(location.dimension))
                statement.setDouble(4, location.x)
                statement.setDouble(5, location.y)
                statement.setDouble(6, location.z)
                statement.setDouble(7, location.yaw.toDouble())
                statement.setDouble(8, location.pitch.toDouble())
                statement.executeUpdate() > 0
            }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to save home '$normalizedName' to SQLite", ex)
        }.getOrThrow()
    }

    @Synchronized
    override fun getHome(playerId: UUID, homeName: String): HomeLocation? {
        val normalizedName = normalizeHomeName(homeName)
        return runCatching {
            connection.prepareStatement(SELECT_HOME).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, normalizedName)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        HomeLocation(
                            resultSet.getString(COLUMN_DIMENSION),
                            resultSet.getDouble(COLUMN_X),
                            resultSet.getDouble(COLUMN_Y),
                            resultSet.getDouble(COLUMN_Z),
                            resultSet.getDouble(COLUMN_YAW).toFloat(),
                            resultSet.getDouble(COLUMN_PITCH).toFloat()
                        )
                    }
                }
            }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to read home '$normalizedName' from SQLite", ex)
        }.getOrThrow()
    }

    @Synchronized
    override fun getHomes(playerId: UUID): Set<String> {
        val names = sortedSetOf<String>()
        runCatching {
            connection.prepareStatement(SELECT_HOME_NAMES).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        names.add(resultSet.getString(COLUMN_HOME_NAME))
                    }
                }
            }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to list homes for player $playerId from SQLite", ex)
        }
        return names
    }

    @Synchronized
    override fun deleteHome(playerId: UUID, homeName: String): Boolean {
        val normalizedName = normalizeHomeName(homeName)
        return runCatching {
            connection.prepareStatement(DELETE_HOME).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, normalizedName)
                statement.executeUpdate() > 0
            }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to delete home '$normalizedName' from SQLite", ex)
        }.getOrThrow()
    }

    @Synchronized
    override fun countHomes(playerId: UUID): Int =
        runCatching {
            connection.prepareStatement(COUNT_HOMES).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
            }
        }.onFailure { ex ->
            if (ex !is SQLException) throw ex
            throw IllegalStateException("Failed to count homes for player $playerId from SQLite", ex)
        }.getOrThrow()

    @Synchronized
    override fun close() {
        if (!::connection.isInitialized) {
            return
        }
        runCatching { connection.close() }
            .onFailure { ex ->
                if (ex !is SQLException) throw ex
                SetHomeMod.LOGGER.warn("Failed to close SQLite connection to {}", dbFilePath, ex)
            }
    }

    companion object {
        init {
            // jarJar-shaded service-loader resources have occasionally failed to auto-register the driver.
            runCatching { Class.forName("org.sqlite.JDBC") }
                .onFailure { ex ->
                    if (ex !is ClassNotFoundException) throw ex
                    throw ExceptionInInitializerError(ex)
                }
        }

        private const val COLUMN_HOME_NAME = "home_name"
        private const val COLUMN_DIMENSION = "dimension"
        private const val COLUMN_X = "x"
        private const val COLUMN_Y = "y"
        private const val COLUMN_Z = "z"
        private const val COLUMN_YAW = "yaw"
        private const val COLUMN_PITCH = "pitch"

        private val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS homes (
                player_id TEXT NOT NULL,
                home_name TEXT NOT NULL,
                dimension TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL,
                PRIMARY KEY (player_id, home_name)
            )
            """.trimIndent()

        private val INSERT_HOME = """
            INSERT OR IGNORE INTO homes(player_id, home_name, dimension, x, y, z, yaw, pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        private const val SELECT_HOME =
            "SELECT dimension, x, y, z, yaw, pitch FROM homes WHERE player_id = ? AND home_name = ?"
        private const val SELECT_HOME_NAMES =
            "SELECT home_name FROM homes WHERE player_id = ? ORDER BY home_name"
        private const val DELETE_HOME =
            "DELETE FROM homes WHERE player_id = ? AND home_name = ?"
        private const val COUNT_HOMES =
            "SELECT COUNT(*) FROM homes WHERE player_id = ?"

        private fun normalizeHomeName(input: String?): String = input?.trim()?.lowercase(Locale.ROOT) ?: ""
    }
}
