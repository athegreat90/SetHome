package de.alexandermora.sethome.data;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import de.alexandermora.sethome.config.SetHomeConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static de.alexandermora.sethome.SetHomeMod.LOGGER;
import static java.nio.file.Files.*;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class HomesFileRepository {

    private static final String PLAYERS_KEY = "players";

    private final Path filePath;
    private final Map<UUID, Map<String, HomeLocation>> homes = new HashMap<>();

    public HomesFileRepository(Path configDir) {
        this.filePath = configDir.resolve("sethome.toml");
    }

    public void load() {
        LOGGER.info("Type storage: {}", SetHomeConfig.STORAGE_MODE.get().name());
        LOGGER.info("Loading homes...");
        ensureFileExists();
        homes.clear();

        try {
            if (size(filePath) == 0L) {
                writeString(filePath, "# SetHome data file\n", StandardCharsets.UTF_8);
                return;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to inspect config file: " + filePath, e);
        }

        try (CommentedFileConfig config = openConfig()) {
            config.load();

            Object rawPlayers = config.get(PLAYERS_KEY);
            if (!(rawPlayers instanceof UnmodifiableConfig playersConfig)) {
                LOGGER.info("No players section found in {}", filePath);
                return;
            }

            for (UnmodifiableConfig.Entry playerEntry : playersConfig.entrySet()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(playerEntry.getKey());
                } catch (IllegalArgumentException ex) {
                    LOGGER.warn("Invalid UUID key in homes file: {}", playerEntry.getKey());
                    continue;
                }

                Object playerValue = playerEntry.getValue();
                if (!(playerValue instanceof UnmodifiableConfig rawPlayerHomes)) {
                    continue;
                }

                Map<String, HomeLocation> parsedHomes = new HashMap<>();

                for (UnmodifiableConfig.Entry homeEntry : rawPlayerHomes.entrySet()) {
                    String homeName = normalizeHomeName(homeEntry.getKey());

                    Object rawHomeValue = homeEntry.getValue();
                    if (!(rawHomeValue instanceof UnmodifiableConfig rawHome)) {
                        continue;
                    }
                    LOGGER.info(
                            "Raw home '{}' -> dimension={}, x={}, y={}, z={}, yaw={}, pitch={}",
                            homeName,
                            rawHome.get("dimension"),
                            rawHome.get("x"),
                            rawHome.get("y"),
                            rawHome.get("z"),
                            rawHome.get("yaw"),
                            rawHome.get("pitch")
                    );
                    try {
                        String dimension = rawHome.get("dimension").toString();
                        double x = toDouble(rawHome.get("x"));
                        double y = toDouble(rawHome.get("y"));
                        double z = toDouble(rawHome.get("z"));
                        float yaw = toFloat(rawHome.get("yaw"));
                        float pitch = toFloat(rawHome.get("pitch"));

                        parsedHomes.put(homeName, HomeLocation.fromDimensionString(dimension, x, y, z, yaw, pitch));

                        LOGGER.info(
                                "Set home '{}' -> dimension={}, x={}, y={}, z={}, yaw={}, pitch={}",
                                homeName,
                                parsedHomes.get("dimension"),
                                parsedHomes.get("x"),
                                parsedHomes.get("y"),
                                parsedHomes.get("z"),
                                parsedHomes.get("yaw"),
                                parsedHomes.get("pitch")
                        );
                    } catch (Exception ex) {
                        LOGGER.warn("Failed to read home '{}' for player {}", homeName, playerId, ex);
                    }
                }

                if (!parsedHomes.isEmpty()) {
                    homes.put(playerId, parsedHomes);
                }
            }

            LOGGER.info("Loaded homes for {} player(s)", homes.size());
        } catch (com.electronwill.nightconfig.core.io.ParsingException e) {
            backupAndResetBrokenFile(e);
        }
    }

    public void save() {
        ensureFileExists();

        try (CommentedFileConfig config = openConfig()) {
            config.clear();

            CommentedConfig playersConfig = CommentedConfig.inMemory();

            for (Map.Entry<UUID, Map<String, HomeLocation>> playerEntry : homes.entrySet()) {
                String uuid = playerEntry.getKey().toString();

                CommentedConfig playerHomesConfig = CommentedConfig.inMemory();

                for (Map.Entry<String, HomeLocation> homeEntry : playerEntry.getValue().entrySet()) {
                    String homeName = normalizeHomeName(homeEntry.getKey());
                    HomeLocation home = homeEntry.getValue();

                    CommentedConfig homeConfig = CommentedConfig.inMemory();
                    homeConfig.set("dimension", home.dimension());
                    homeConfig.set("x", home.x());
                    homeConfig.set("y", home.y());
                    homeConfig.set("z", home.z());
                    homeConfig.set("yaw", home.yaw());
                    homeConfig.set("pitch", home.pitch());

                    playerHomesConfig.set(homeName, homeConfig);
                }

                playersConfig.set(uuid, playerHomesConfig);
            }

            config.set(PLAYERS_KEY, playersConfig);
            config.save();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save homes file: " + filePath, e);
        }
    }

    public boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        String normalizedName = normalizeHomeName(homeName);

        Map<String, HomeLocation> playerHomes =
                homes.computeIfAbsent(playerId, ignored -> new HashMap<>());

        if (playerHomes.containsKey(normalizedName)) {
            return false;
        }

        playerHomes.put(normalizedName, location);
        save();
        return true;
    }

    public HomeLocation getHome(UUID playerId, String homeName) {
        return homes.getOrDefault(playerId, Map.of())
                .get(normalizeHomeName(homeName));
    }

    public java.util.Set<String> getHomes(UUID playerId) {
        return new TreeSet<>(homes.getOrDefault(playerId, java.util.Map.of()).keySet());
    }

    public boolean deleteHome(UUID playerId, String homeName) {
        Map<String, HomeLocation> playerHomes = homes.get(playerId);
        if (playerHomes == null) {
            return false;
        }

        HomeLocation removed = playerHomes.remove(normalizeHomeName(homeName));
        if (removed == null) {
            return false;
        }

        if (playerHomes.isEmpty()) {
            homes.remove(playerId);
        }

        save();
        return true;
    }

    public int countHomes(UUID playerId) {
        return homes.getOrDefault(playerId, Map.of()).size();
    }

    private void ensureFileExists() {
        try {
            createDirectories(filePath.getParent());

            if (notExists(filePath)) {
                writeString(filePath, "# SetHome data file\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create config file: " + filePath, e);
        }
    }

    private CommentedFileConfig openConfig() {
        return CommentedFileConfig.builder(filePath)
                .sync()
                .writingMode(WritingMode.REPLACE)
                .build();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static float toFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return Float.parseFloat(String.valueOf(value));
    }


    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }


    private void backupAndResetBrokenFile(Exception cause) {
        try {
            Path brokenPath = filePath.resolveSibling(filePath.getFileName() + ".broken");
            copy(filePath, brokenPath, REPLACE_EXISTING);

            writeString(filePath, "# SetHome data file\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);

            homes.clear();
        } catch (IOException e) {
            throw new RuntimeException("Failed to reset broken config file: " + filePath, cause);
        }
    }

}