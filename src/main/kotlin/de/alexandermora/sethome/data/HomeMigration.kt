package de.alexandermora.sethome.data

import de.alexandermora.sethome.SetHomeMod

internal object HomeMigration {

    fun migrate(source: HomesFileRepository, target: HomesRepository) {
        val allHomes = source.exportAll()
        var migrated = 0
        var skipped = 0

        for ((playerId, playerHomes) in allHomes) {
            for ((homeName, home) in playerHomes) {
                if (target.getHome(playerId, homeName) != null) {
                    skipped++
                    continue
                }

                runCatching {
                    if (target.setHome(playerId, homeName, home)) {
                        migrated++
                    } else {
                        skipped++
                    }
                }.onFailure { ex ->
                    if (ex !is RuntimeException) throw ex
                    SetHomeMod.LOGGER.error("Failed to migrate home '{}' for player {}", homeName, playerId, ex)
                }
            }
        }

        SetHomeMod.LOGGER.info(
            "File-to-{} migration complete: {} home(s) migrated, {} already present or skipped.",
            target.javaClass.simpleName, migrated, skipped
        )
    }
}
