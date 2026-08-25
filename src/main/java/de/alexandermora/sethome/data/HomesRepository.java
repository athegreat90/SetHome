package de.alexandermora.sethome.data;

import java.util.Set;
import java.util.UUID;

public interface HomesRepository {

    void load();

    boolean setHome(UUID playerId, String homeName, HomeLocation location);

    HomeLocation getHome(UUID playerId, String homeName);

    Set<String> getHomes(UUID playerId);

    boolean deleteHome(UUID playerId, String homeName);

    int countHomes(UUID playerId);

    void close();
}