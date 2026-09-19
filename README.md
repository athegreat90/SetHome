# SetHome

SetHome is a server-side NeoForge mod that lets players save, teleport to, list, and delete named home
locations. Homes belong to individual players, are stored by player UUID, and persist across server restarts.

Each home remembers its dimension, coordinates, and facing direction. Players can teleport between
dimensions as long as the saved dimension is available on the server. Server owners can configure the
home limit and choose local TOML, SQLite, or MongoDB storage.

## Requirements and installation

The current source targets:

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.109 or a newer version compatible with Minecraft 26.1.2 |
| Java | 25 |

Minecraft, NeoForge, and mod versions are defined in [gradle.properties](gradle.properties).

1. Set up a NeoForge server using the versions above.
2. Obtain the SetHome JAR, or [build it from source](#development).
3. Place the mod JAR in the server's `mods/` directory.
4. Start the server to generate `config/sethome/sethome-common.toml` and initialize home storage.
5. Join the server and save your first home with `/sethome base`.

Players can use the commands without installing SetHome on their clients.

## Commands

| Command | Description |
| --- | --- |
| `/sethome <name>` | Save your current location and facing direction under the given name. |
| `/home <name>` | Teleport to one of your saved homes. |
| `/homes` | List your saved home names alphabetically. |
| `/delhome <name>` | Delete one of your saved homes. |

All four commands are available to players without operator permissions and must be run as a player.
Each player manages their own homes.

- Home names are single words without spaces and are normalized to lowercase: `Base` and `base` refer
  to the same home.
- Players can save **5 homes by default**. The server owner can change this limit in the configuration.
- An existing name cannot be overwritten. To move a home, delete it with `/delhome <name>`, then run
  `/sethome <name>` at the new location.

Example:

```text
/sethome base
/sethome mine
/homes
/home base
/delhome mine
```

## Configuration

The configuration file is generated at `config/sethome/sethome-common.toml`. Its default settings are:

```toml
[general]
maxHomesPerPlayer = 5

[storage]
storageMode = "FILE"
migrateFromFile = false

[storage.mongodb]
uri = "mongodb://localhost:27017"
database = "sethome"
collection = "homes"

[storage.sqlite]
fileName = "sethome.db"
```

`general.maxHomesPerPlayer` accepts values from **1 to 1000**. The limit applies when creating a new
home; lowering it preserves existing homes.

Stop the server before editing the configuration and start it again afterward. Storage backend,
connection, and migration settings are applied at server startup.

### Storage backends

Set `storage.storageMode` to one of the following values:

| Mode | Storage location | Setup |
| --- | --- | --- |
| `FILE` (default) | `config/sethome/sethome.toml` | Local TOML file, created automatically. |
| `SQLITE` | `config/sethome/sethome.db` by default | Embedded database, created automatically; customize its filename with `storage.sqlite.fileName`. |
| `MONGODB` | The configured MongoDB database and collection | Provide a running MongoDB instance and configure `storage.mongodb.uri`, `database`, and `collection`. |

The MongoDB and SQLite drivers are bundled with the mod. All local paths above are relative to the
server's working directory. Local home data is stored under `config/sethome/`, shared by worlds run
from that directory.

If the selected database backend fails to load at startup, SetHome logs the failure and uses the FILE
backend for that server session. Each backend keeps its own data; use the migration option below to
copy existing file-backed homes into a database.

If the FILE backend's TOML data cannot be parsed, SetHome copies the broken file to
`config/sethome/sethome.toml.broken-<timestamp>`, resets the active file, and starts with an empty home list.

### Migrating from FILE to SQLite or MongoDB

1. Stop the server and keep the existing `config/sethome/sethome.toml` in place.
2. Set `storageMode` to `"SQLITE"` or `"MONGODB"` in the `[storage]` section and configure that backend.
3. Set `migrateFromFile = true` in the same section, then start the server.
4. Check the server log for the migration result. After the import succeeds, stop the server, set
   `migrateFromFile = false`, and start it again to finish the migration.

Migration copies homes from the TOML file into the selected database, skipping homes that already
exist for the same player and name. The source file is retained. While enabled, migration runs on
every startup, so a home deleted from the database can be imported again if it still exists in the
source file. The setting has no effect in FILE mode or when startup falls back to FILE.

## Development

Install JDK **25**, clone this repository, and run the included Gradle wrapper from the repository root.

On Linux or macOS:

```sh
./gradlew build
```

On Windows PowerShell:

```powershell
.\gradlew.bat build
```

The packaged mod JAR is written to `build/libs/` as `sethome-<version>.jar`.

Useful development commands (use `.\gradlew.bat` instead of `./gradlew` on Windows):

| Command | Purpose |
| --- | --- |
| `./gradlew runServer` | Launch a development dedicated server with the mod loaded. |
| `./gradlew runClient` | Launch a development Minecraft client with the mod loaded. |
| `./gradlew runData` | Generate resources under `src/generated/resources/`. |
| `./gradlew clean` | Remove build outputs. |
| `./gradlew build --refresh-dependencies` | Refresh the dependency cache and rebuild. |

### Resources and licensing

- [NeoForge documentation](https://docs.neoforged.net/)
- [NeoForge Discord](https://discord.neoforged.net/)
- The project uses Mojang's official mapping names, which are covered by the
  [Mojang mapping license](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md).
- The mod license is **All Rights Reserved**, as declared in [gradle.properties](gradle.properties).
