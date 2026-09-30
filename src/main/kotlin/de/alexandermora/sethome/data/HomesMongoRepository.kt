package de.alexandermora.sethome.data

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import org.bson.Document
import org.bson.conversions.Bson
import java.util.Locale
import java.util.UUID

class HomesMongoRepository(
    private val uri: String,
    private val databaseName: String,
    private val collectionName: String
) : HomesRepository {

    private lateinit var mongoClient: MongoClient
    private lateinit var collection: MongoCollection<Document>

    @Synchronized
    override fun load() {
        val client = MongoClients.create(uri)
        val database = client.getDatabase(databaseName)

        runCatching {
            database.runCommand(Document("ping", 1))
            val homesCollection = database.getCollection(collectionName)
            homesCollection.createIndex(
                Indexes.ascending(FIELD_PLAYER_ID, FIELD_HOME_NAME),
                IndexOptions().unique(true)
            )
            collection = homesCollection
        }.onFailure { ex ->
            if (ex !is RuntimeException) throw ex
            client.close()
            throw IllegalStateException("Failed to connect to MongoDB at $uri", ex)
        }

        mongoClient = client
    }

    @Synchronized
    override fun setHome(playerId: UUID, homeName: String, location: HomeLocation): Boolean {
        val normalizedName = normalizeHomeName(homeName)
        require(normalizedName.isNotBlank()) { "Home name cannot be blank" }

        return runCatching {
            collection.insertOne(toDocument(playerId, normalizedName, location))
            true
        }.getOrElse { ex ->
            if (ex !is MongoWriteException) throw ex
            if (ex.error.category == ErrorCategory.DUPLICATE_KEY) false else throw ex
        }
    }

    @Synchronized
    override fun getHome(playerId: UUID, homeName: String): HomeLocation? {
        val doc = collection.find(homeFilter(playerId, normalizeHomeName(homeName))).first()
        return doc?.let { fromDocument(it) }
    }

    @Synchronized
    override fun getHomes(playerId: UUID): Set<String> {
        val names = sortedSetOf<String>()
        collection.find(Filters.eq(FIELD_PLAYER_ID, playerId.toString()))
            .projection(Projections.include(FIELD_HOME_NAME))
            .forEach { doc -> names.add(doc.getString(FIELD_HOME_NAME)) }
        return names
    }

    @Synchronized
    override fun deleteHome(playerId: UUID, homeName: String): Boolean {
        val result = collection.deleteOne(homeFilter(playerId, normalizeHomeName(homeName)))
        return result.deletedCount > 0
    }

    @Synchronized
    override fun countHomes(playerId: UUID): Int =
        collection.countDocuments(Filters.eq(FIELD_PLAYER_ID, playerId.toString())).toInt()

    @Synchronized
    override fun close() {
        if (::mongoClient.isInitialized) {
            mongoClient.close()
        }
    }

    companion object {
        private const val FIELD_PLAYER_ID = "playerId"
        private const val FIELD_HOME_NAME = "homeName"
        private const val FIELD_DIMENSION = "dimension"
        private const val FIELD_X = "x"
        private const val FIELD_Y = "y"
        private const val FIELD_Z = "z"
        private const val FIELD_YAW = "yaw"
        private const val FIELD_PITCH = "pitch"

        private fun homeFilter(playerId: UUID, normalizedName: String): Bson =
            Filters.and(
                Filters.eq(FIELD_PLAYER_ID, playerId.toString()),
                Filters.eq(FIELD_HOME_NAME, normalizedName)
            )

        private fun toDocument(playerId: UUID, homeName: String, location: HomeLocation): Document =
            Document(FIELD_PLAYER_ID, playerId.toString())
                .append(FIELD_HOME_NAME, homeName)
                .append(FIELD_DIMENSION, HomeLocation.normalizeDimension(location.dimension))
                .append(FIELD_X, location.x)
                .append(FIELD_Y, location.y)
                .append(FIELD_Z, location.z)
                .append(FIELD_YAW, location.yaw.toDouble())
                .append(FIELD_PITCH, location.pitch.toDouble())

        private fun fromDocument(doc: Document): HomeLocation =
            HomeLocation(
                doc.getString(FIELD_DIMENSION),
                getAsDouble(doc, FIELD_X),
                getAsDouble(doc, FIELD_Y),
                getAsDouble(doc, FIELD_Z),
                getAsDouble(doc, FIELD_YAW).toFloat(),
                getAsDouble(doc, FIELD_PITCH).toFloat()
            )

        /**
         * Reads a numeric field as a double regardless of whether it was stored as a
         * BSON Int32/Int64/Double, since legacy or externally-written documents may not
         * use the exact Double type that Document.getDouble requires.
         */
        private fun getAsDouble(doc: Document, field: String): Double {
            val value = doc[field]
            if (value !is Number) {
                throw IllegalStateException("Missing or non-numeric field '$field' in home document")
            }
            return value.toDouble()
        }

        private fun normalizeHomeName(input: String?): String = input?.trim()?.lowercase(Locale.ROOT) ?: ""
    }
}
