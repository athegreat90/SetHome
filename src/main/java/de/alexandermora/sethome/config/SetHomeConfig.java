package de.alexandermora.sethome.config;


import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class SetHomeConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_HOMES_PER_PLAYER;

    public static final ModConfigSpec.EnumValue<StorageMode> STORAGE_MODE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("SetHome common configuration").push("general");

        MAX_HOMES_PER_PLAYER = builder
                .comment("Maximum number of homes allowed per player.")
                .defineInRange("maxHomesPerPlayer", 5, 1, 1000);

        STORAGE_MODE = builder.comment("Allowed: File, MariaDB or MongoDB").defineEnum(
                "storageMode", StorageMode.FILE, List.of(StorageMode.FILE, StorageMode.MONGODB, StorageMode.MARIADB)
        );

        builder.pop();

        SPEC = builder.build();
    }

    private SetHomeConfig() {
    }
}