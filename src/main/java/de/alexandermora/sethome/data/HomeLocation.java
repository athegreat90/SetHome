package de.alexandermora.sethome.data;

public record HomeLocation(
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public static HomeLocation fromDimensionString(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        return new HomeLocation(dimension, x, y, z, yaw, pitch);
    }
}