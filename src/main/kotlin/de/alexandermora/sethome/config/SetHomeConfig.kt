package de.alexandermora.sethome.config

import net.neoforged.neoforge.common.ModConfigSpec

object SetHomeConfig {

    val SPEC: ModConfigSpec
    val MAX_HOMES_PER_PLAYER: ModConfigSpec.IntValue

    val STORAGE_MODE: ModConfigSpec.EnumValue<StorageMode>
    val MIGRATE_FROM_FILE: ModConfigSpec.BooleanValue

    val MONGODB_URI: ModConfigSpec.ConfigValue<String>
    val MONGODB_DATABASE: ModConfigSpec.ConfigValue<String>
    val MONGODB_COLLECTION: ModConfigSpec.ConfigValue<String>

    val SQLITE_FILE_NAME: ModConfigSpec.ConfigValue<String>

    init {
        val builder = ModConfigSpec.Builder()

        builder.comment("SetHome common configuration").push("general")

        MAX_HOMES_PER_PLAYER = builder
            .comment("Maximum number of homes allowed per player.")
            .defineInRange("maxHomesPerPlayer", 5, 1, 1000)

        builder.pop()

        builder.comment("Storage backend configuration").push("storage")

        STORAGE_MODE = builder
            .comment(
                "Which storage backend to use for home data.",
                "FILE: local TOML file under config/sethome/ (default, no external dependency).",
                "MONGODB: remote MongoDB database (see mongodb.* settings below).",
                "SQLITE: local embedded SQLite database file (see sqlite.* settings below)."
            )
            .defineEnum("storageMode", StorageMode.FILE)

        MIGRATE_FROM_FILE = builder
            .comment(
                "If true and storageMode is not FILE, copy any homes found in the existing FILE-backend",
                "data into the selected backend on every startup. Existing entries in the target backend",
                "are never overwritten, so it is safe to leave this enabled across restarts.",
                "Has no effect when storageMode is FILE."
            )
            .define("migrateFromFile", false)

        builder.push("mongodb")

        MONGODB_URI = builder
            .comment("MongoDB connection URI, e.g. mongodb://localhost:27017")
            .define("uri", "mongodb://localhost:27017")

        MONGODB_DATABASE = builder
            .comment("MongoDB database name to store homes in.")
            .define("database", "sethome")

        MONGODB_COLLECTION = builder
            .comment("MongoDB collection name to store homes in.")
            .define("collection", "homes")

        builder.pop()

        builder.push("sqlite")

        SQLITE_FILE_NAME = builder
            .comment("File name of the SQLite database, created under config/sethome/.")
            .define("fileName", "sethome.db")

        builder.pop()

        builder.pop()
        SPEC = builder.build()
    }
}
