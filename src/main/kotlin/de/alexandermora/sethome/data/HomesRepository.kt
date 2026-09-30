package de.alexandermora.sethome.data

import java.util.UUID

interface HomesRepository {

    fun load()

    fun setHome(playerId: UUID, homeName: String, location: HomeLocation): Boolean

    fun getHome(playerId: UUID, homeName: String): HomeLocation?

    fun getHomes(playerId: UUID): Set<String>

    fun deleteHome(playerId: UUID, homeName: String): Boolean

    fun countHomes(playerId: UUID): Int

    fun close()
}
