package de.alexandermora.sethome.data;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.WritingMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static de.alexandermora.sethome.SetHomeMod.LOGGER;

public final class HomesFileRepository {

    private static final String PLAYERS_KEY = "players";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path filePath;
    private final Map<UUID, Map<String, HomeLocation>> homes = new HashMap<>();

    public HomesFileRepository(Path configDir) {
        this.filePath = configDir.resolve("sethome.toml");
    }

    public synchronized void load() {
        ensureFileExists();
        homes.clear();

        try (CommentedFileConfig config = openConfig()) {
            config.load();

            Object rawPlayers = config.get(PLAYERS_KEY);
            if (!(rawPlayers instanceof UnmodifiableConfig playersConfig)) {
                LOGGER.info("No persisted homes found in {}", filePath);
                return;
            }

            for (UnmodifiableConfig.Entry playerEntry : playersConfig.entrySet()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(playerEntry.getKey());
                } catch (IllegalArgumentException ex) {
                    LOGGER.warn("Skipping invalid player UUID '{}' in {}", playerEntry.getKey(), filePath);
                    continue;
                }

                if (!(playerEntry.getValue() instanceof UnmodifiableConfig playerHomesConfig)) {
                    continue;
                }

                Map<String, HomeLocation> parsedHomes = new HashMap<>();
                for (UnmodifiableConfig.Entry homeEntry : playerHomesConfig.entrySet()) {
                    String homeName = normalizeHomeName(homeEntry.getKey());
                    if (!(homeEntry.getValue() instanceof UnmodifiableConfig homeConfig)) {
                        continue;
                    }

                    try {
                        String dimension = requiredString(homeConfig, "dimension");
                        double x = requiredDouble(homeConfig, "x");
                        double y = requiredDouble(homeConfig, "y");
                        double z = requiredDouble(homeConfig, "z");
                        float yaw = requiredFloat(homeConfig, "yaw");
                        float pitch = requiredFloat(homeConfig, "pitch");

                        parsedHomes.put(homeName, new HomeLocation(dimension, x, y, z, yaw, pitch));
                    } catch (RuntimeException ex) {
                        LOGGER.warn("Skipping malformed home '{}' for player {}", homeName, playerId, ex);
                    }
                }

                if (!parsedHomes.isEmpty()) {
                    homes.put(playerId, parsedHomes);
                }
            }

            LOGGER.info("Loaded {} home(s) for {} player(s) from {}",
                    homes.values().stream().mapToInt(Map::size).sum(), homes.size(), filePath);
        } catch (ParsingException ex) {
            backupAndResetBrokenFile(ex);
        }
    }

    public synchronized void save() {
        ensureFileExists();

        try (CommentedFileConfig config = openConfig()) {
            config.clear();
            CommentedConfig playersConfig = CommentedConfig.inMemory();
            for (Map.Entry<UUID, Map<String, HomeLocation>> playerEntry : homes.entrySet()) {
                CommentedConfig playerHomesConfig = CommentedConfig.inMemory();

                for (Map.Entry<String, HomeLocation> homeEntry : playerEntry.getValue().entrySet()) {
                    HomeLocation home = homeEntry.getValue();
                    CommentedConfig homeConfig = CommentedConfig.inMemory();
                    homeConfig.set("dimension", HomeLocation.normalizeDimension(home.dimension()));
                    homeConfig.set("x", home.x());
                    homeConfig.set("y", home.y());
                    homeConfig.set("z", home.z());
                    homeConfig.set("yaw", home.yaw());
                    homeConfig.set("pitch", home.pitch());
                    playerHomesConfig.set(normalizeHomeName(homeEntry.getKey()), homeConfig);
                }

                playersConfig.set(playerEntry.getKey().toString(), playerHomesConfig);
            }

            config.set(PLAYERS_KEY, playersConfig);
            config.setComment(PLAYERS_KEY, "Persistent named homes, grouped by player UUID.");
            config.save();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to save homes to " + filePath, ex);
        }
    }

    public synchronized boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        String normalizedName = normalizeHomeName(homeName);
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Home name cannot be blank");
        }

        Map<String, HomeLocation> playerHomes = homes.computeIfAbsent(playerId, ignored -> new HashMap<>());
        if (playerHomes.containsKey(normalizedName)) {
            return false;
        }

        playerHomes.put(normalizedName, location);
        save();
        return true;
    }

    public synchronized HomeLocation getHome(UUID playerId, String homeName) {
        return homes.getOrDefault(playerId, Map.of()).get(normalizeHomeName(homeName));
    }

    public synchronized Set<String> getHomes(UUID playerId) {
        return Collections.unmodifiableSet(new TreeSet<>(homes.getOrDefault(playerId, Map.of()).keySet()));
    }

    public synchronized boolean deleteHome(UUID playerId, String homeName) {
        Map<String, HomeLocation> playerHomes = homes.get(playerId);
        if (playerHomes == null || playerHomes.remove(normalizeHomeName(homeName)) == null) {
            return false;
        }

        if (playerHomes.isEmpty()) {
            homes.remove(playerId);
        }
        save();
        return true;
    }

    public synchronized int countHomes(UUID playerId) {
        return homes.getOrDefault(playerId, Map.of()).size();
    }

    private void ensureFileExists() {
        try {
            Files.createDirectories(filePath.getParent());
            if (Files.notExists(filePath)) {
                Files.writeString(filePath, "# SetHome persistent data file\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create homes file " + filePath, ex);
        }
    }

    private CommentedFileConfig openConfig() {
        return CommentedFileConfig.builder(filePath)
                .sync()
                .writingMode(WritingMode.REPLACE)
                .build();
    }

    private void backupAndResetBrokenFile(ParsingException cause) {
        try {
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path backup = filePath.resolveSibling("sethome.toml.broken-" + timestamp);
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(filePath, "# SetHome persistent data file\n", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            homes.clear();
            LOGGER.error("The homes file was malformed. It was backed up to {} and reset.", backup, cause);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to back up malformed homes file " + filePath, cause);
        }
    }

    private static String requiredString(UnmodifiableConfig config, String key) {
        Object value = config.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return String.valueOf(value);
    }

    private static double requiredDouble(UnmodifiableConfig config, String key) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static float requiredFloat(UnmodifiableConfig config, String key) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return Float.parseFloat(String.valueOf(value));
    }

    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
