package de.alexandermora.sethome.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record HomeLocation(
        ResourceKey<Level> dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    public static HomeLocation fromDimensionString(
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        ResourceKey<Level> dimensionKey = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(dimensionId)
        );

        return new HomeLocation(dimensionKey, x, y, z, yaw, pitch);
    }
}