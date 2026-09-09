package de.alexandermora.sethome.data;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class HomesMongoRepository implements HomesRepository {

    private static final String FIELD_PLAYER_ID = "playerId";
    private static final String FIELD_HOME_NAME = "homeName";
    private static final String FIELD_DIMENSION = "dimension";
    private static final String FIELD_X = "x";
    private static final String FIELD_Y = "y";
    private static final String FIELD_Z = "z";
    private static final String FIELD_YAW = "yaw";
    private static final String FIELD_PITCH = "pitch";

    private final String uri;
    private final String databaseName;
    private final String collectionName;

    private MongoClient mongoClient;
    private MongoCollection<Document> collection;

    public HomesMongoRepository(String uri, String databaseName, String collectionName) {
        this.uri = uri;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @Override
    public synchronized void load() {
        MongoClient client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase(databaseName);

        try {
            database.runCommand(new Document("ping", 1));
            MongoCollection<Document> homesCollection = database.getCollection(collectionName);
            homesCollection.createIndex(
                    Indexes.ascending(FIELD_PLAYER_ID, FIELD_HOME_NAME),
                    new IndexOptions().unique(true));
            this.collection = homesCollection;
        } catch (RuntimeException ex) {
            client.close();
            throw new IllegalStateException("Failed to connect to MongoDB at " + uri, ex);
        }

        this.mongoClient = client;
    }

    @Override
    public synchronized boolean setHome(UUID playerId, String homeName, HomeLocation location) {
        String normalizedName = normalizeHomeName(homeName);
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Home name cannot be blank");
        }

        try {
            collection.insertOne(toDocument(playerId, normalizedName, location));
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public synchronized HomeLocation getHome(UUID playerId, String homeName) {
        Document doc = collection.find(homeFilter(playerId, normalizeHomeName(homeName))).first();
        return doc == null ? null : fromDocument(doc);
    }

    @Override
    public synchronized Set<String> getHomes(UUID playerId) {
        Set<String> names = new TreeSet<>();
        collection.find(Filters.eq(FIELD_PLAYER_ID, playerId.toString()))
                .projection(Projections.include(FIELD_HOME_NAME))
                .forEach(doc -> names.add(doc.getString(FIELD_HOME_NAME)));
        return Collections.unmodifiableSet(names);
    }

    @Override
    public synchronized boolean deleteHome(UUID playerId, String homeName) {
        DeleteResult result = collection.deleteOne(homeFilter(playerId, normalizeHomeName(homeName)));
        return result.getDeletedCount() > 0;
    }

    @Override
    public synchronized int countHomes(UUID playerId) {
        return (int) collection.countDocuments(Filters.eq(FIELD_PLAYER_ID, playerId.toString()));
    }

    @Override
    public synchronized void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    private static Bson homeFilter(UUID playerId, String normalizedName) {
        return Filters.and(
                Filters.eq(FIELD_PLAYER_ID, playerId.toString()),
                Filters.eq(FIELD_HOME_NAME, normalizedName));
    }

    private static Document toDocument(UUID playerId, String homeName, HomeLocation location) {
        return new Document(FIELD_PLAYER_ID, playerId.toString())
                .append(FIELD_HOME_NAME, homeName)
                .append(FIELD_DIMENSION, HomeLocation.normalizeDimension(location.dimension()))
                .append(FIELD_X, location.x())
                .append(FIELD_Y, location.y())
                .append(FIELD_Z, location.z())
                .append(FIELD_YAW, (double) location.yaw())
                .append(FIELD_PITCH, (double) location.pitch());
    }

    private static HomeLocation fromDocument(Document doc) {
        return new HomeLocation(
                doc.getString(FIELD_DIMENSION),
                getAsDouble(doc, FIELD_X),
                getAsDouble(doc, FIELD_Y),
                getAsDouble(doc, FIELD_Z),
                (float) getAsDouble(doc, FIELD_YAW),
                (float) getAsDouble(doc, FIELD_PITCH)
        );
    }

    /**
     * Reads a numeric field as a double regardless of whether it was stored as a
     * BSON Int32/Int64/Double, since legacy or externally-written documents may not
     * use the exact Double type that {@link Document#getDouble(Object)} requires.
     */
    private static double getAsDouble(Document doc, String field) {
        Object value = doc.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Missing or non-numeric field '" + field + "' in home document");
        }
        return number.doubleValue();
    }

    private static String normalizeHomeName(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}