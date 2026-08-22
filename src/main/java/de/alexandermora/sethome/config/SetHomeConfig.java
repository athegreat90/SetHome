package de.alexandermora.sethome.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SetHomeConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_HOMES_PER_PLAYER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("SetHome common configuration").push("general");

        MAX_HOMES_PER_PLAYER = builder
                .comment("Maximum number of homes allowed per player.")
                .defineInRange("maxHomesPerPlayer", 5, 1, 1000);

        builder.pop();
        SPEC = builder.build();
    }

    private SetHomeConfig() {
    }
}
