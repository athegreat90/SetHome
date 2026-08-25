package de.alexandermora.sethome.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static de.alexandermora.sethome.SetHomeMod.LOGGER;

public final class HomesSqliteRepository implements HomesRepository {

    static {
        try {
            // jarJar-shaded service-loader resources have occasionally failed to auto-register the driver.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final String COLUMN_HOME_NAME = "home_name";
    private static final String COLUMN_DIMENSION = "dimension";
    private static final String COLUMN_X = "x";
    private static final String COLUMN_Y = "y";
    private static final String COLUMN_Z = "z";
    private static final String COLUMN_YAW = "yaw";
    private static final String COLUMN_PITCH = "pitch";

    private static final String CREATE_TABLE = """
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
            """;

    private static final String INSERT_HOME = """
            INSERT OR IGNORE INTO homes(player_id, home_name, dimension, x, y, z, yaw, pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_HOME =
            "SELECT dimension, x, y, z, yaw, pitch FROM homes WHERE player_id = ? AND home_name = ?";
    private static final String SELECT_HOME_NAMES =
            "SELECT home_name FROM homes WHERE player_id = ? ORDER BY home_name";
    private static final String DELETE_HOME =
            "DELETE FROM homes WHERE player_id = ? AND home_name = ?";
    private static final String COUNT_HOMES =
            "SELECT COUNT(*) FROM homes WHERE player_id = ?";

    private final Path dbFilePath;
    private Connection connection;

    public HomesSqliteRepository(Path dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    @Override
    public synchronized void load() {
        try {
            Files.createDirectories(dbFilePath.toAbsolutePath().getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create directory for SQLite database " + dbFilePath, ex);
        }

        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);
            try (PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
                statement.execute();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to open SQLite database " + dbFilePath, ex);
        }
    }

    @Override
    public synchronized boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        String normalizedName = normalizeHomeName(homeName);
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Home name cannot be blank");
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_HOME)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalizedName);
            statement.setString(3, HomeLocation.normalizeDimension(location.dimension()));
            statement.setDouble(4, location.x());
            statement.setDouble(5, location.y());
            statement.setDouble(6, location.z());
            statement.setDouble(7, location.yaw());
            statement.setDouble(8, location.pitch());
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save home '" + normalizedName + "' to SQLite", ex);
        }
    }

    @Override
    public synchronized HomeLocation getHome(UUID playerId, String homeName) {
        String normalizedName = normalizeHomeName(homeName);
        try (PreparedStatement statement = connection.prepareStatement(SELECT_HOME)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalizedName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new HomeLocation(
                        resultSet.getString(COLUMN_DIMENSION),
                        resultSet.getDouble(COLUMN_X),
                        resultSet.getDouble(COLUMN_Y),
                        resultSet.getDouble(COLUMN_Z),
                        (float) resultSet.getDouble(COLUMN_YAW),
                        (float) resultSet.getDouble(COLUMN_PITCH));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read home '" + normalizedName + "' from SQLite", ex);
        }
    }

    @Override
    public synchronized Set<String> getHomes(UUID playerId) {
        Set<String> names = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_HOME_NAMES)) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    names.add(resultSet.getString(COLUMN_HOME_NAME));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list homes for player " + playerId + " from SQLite", ex);
        }
        return Collections.unmodifiableSet(names);
    }

    @Override
    public synchronized boolean deleteHome(UUID playerId, String homeName) {
        String normalizedName = normalizeHomeName(homeName);
        try (PreparedStatement statement = connection.prepareStatement(DELETE_HOME)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, normalizedName);
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete home '" + normalizedName + "' from SQLite", ex);
        }
    }

    @Override
    public synchronized int countHomes(UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_HOMES)) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count homes for player " + playerId + " from SQLite", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            LOGGER.warn("Failed to close SQLite connection to {}", dbFilePath, ex);
        }
    }

    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}