package de.alexandermora.sethome.data;

import java.util.Map;
import java.util.UUID;

import static de.alexandermora.sethome.SetHomeMod.LOGGER;

final class HomeMigration {

    private HomeMigration() {
    }

    static void migrate(HomesFileRepository source, HomesRepository target) {
        Map<UUID, Map<String, HomeLocation>> allHomes = source.exportAll();
        int migrated = 0;
        int skipped = 0;

        for (Map.Entry<UUID, Map<String, HomeLocation>> playerEntry : allHomes.entrySet()) {
            UUID playerId = playerEntry.getKey();

            for (Map.Entry<String, HomeLocation> homeEntry : playerEntry.getValue().entrySet()) {
                String homeName = homeEntry.getKey();

                if (target.getHome(playerId, homeName) != null) {
                    skipped++;
                    continue;
                }

                try {
                    if (target.setHome(playerId, homeName, homeEntry.getValue())) {
                        migrated++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException ex) {
                    LOGGER.error("Failed to migrate home '{}' for player {}", homeName, playerId, ex);
                }
            }
        }

        LOGGER.info("File-to-{} migration complete: {} home(s) migrated, {} already present or skipped.",
                target.getClass().getSimpleName(), migrated, skipped);
    }
}