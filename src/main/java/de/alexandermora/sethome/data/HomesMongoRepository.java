package de.alexandermora.sethome.data;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

public class HomesMongoRepository {
    private final String uri;
    private final Map<UUID, Map<String, HomeLocation>> homes = new HashMap<>();

    public HomesMongoRepository(String uri) {
        this.uri = uri;
    }

    public void load() {
        try (MongoClient mongoClient = MongoClients.create(uri)) {
            MongoDatabase database = mongoClient.getDatabase("minecraft");
            MongoCollection<Document> collection = database.getCollection("homes");
            Document doc = collection.find(eq("title", "Back to the Future")).first();
            if (doc != null) {
                System.out.println(doc.toJson());
            } else {
                System.out.println("No matching documents found.");
            }
        }
    }
}
