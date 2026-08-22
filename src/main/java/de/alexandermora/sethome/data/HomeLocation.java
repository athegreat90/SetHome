package de.alexandermora.sethome.data;

import java.util.Objects;

public record HomeLocation(
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public HomeLocation {
        dimension = normalizeDimension(Objects.requireNonNull(dimension, "dimension"));
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("Dimension cannot be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Home coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Home rotation must be finite");
        }
    }

    /**
     * Accepts both the current identifier form (minecraft:overworld) and the
     * legacy ResourceKey[minecraft:dimension / minecraft:overworld] form.
     */
    public static String normalizeDimension(String dimension) {
        String value = dimension == null ? "" : dimension.trim();

        if (value.startsWith("ResourceKey[") || value.startsWith("ResourceKey(")) {
            int slash = value.indexOf('/');
            int endSquare = value.lastIndexOf(']');
            int endRound = value.lastIndexOf(')');
            int end = Math.max(endSquare, endRound);
            if (slash >= 0 && end > slash) {
                value = value.substring(slash + 1, end).trim();
            }
        }

        return value;
    }
}
