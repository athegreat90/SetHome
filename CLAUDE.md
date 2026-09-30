# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

SetHome is a server-side NeoForge mod for Minecraft that lets players save, teleport to, list, and delete
named home locations. It exposes four commands: `/sethome <name>`, `/home <name>`, `/homes`, `/delhome <name>`.

This repo was bootstrapped from the NeoForge MDK template — `README.md` still contains the generic MDK/template
instructions (mapping names licensing, "clone this template" guidance) rather than project-specific docs.

## Build and run commands

- `./gradlew build` — compile and package the mod jar (this is what CI runs, see `.github/workflows/build.yml`)
- `./gradlew runClient` — launch a Minecraft client with the mod loaded
- `./gradlew runServer` — launch a dedicated server with the mod loaded
- `./gradlew runGameTestServer` — run the gametest server harness
- `./gradlew runData` — run the NeoForge data generator (outputs to `src/generated/resources/`, using
  `src/main/resources/` as the existing-file source)
- `./gradlew clean` — reset build outputs without touching source
- `./gradlew --refresh-dependencies` — refresh the local dependency cache if the IDE reports missing libraries

On Windows PowerShell, use `.\gradlew.bat` in place of `./gradlew` for every command above.

`settings.gradle` applies the `org.gradle.toolchains.foojay-resolver-convention` plugin so Gradle can
auto-provision the JDK 25 toolchain `build.gradle` requests. Don't infer the project's actual compile target from
CI's `actions/setup-java` step — that only provisions JDK 21 to run Gradle itself, not the project toolchain (see
the JDK 25 vs. 21 note further down).

There is no test suite in this repo (no `src/test` directory) and no lint task beyond normal compilation.
`./gradlew runGameTestServer` is configured, but this repo currently defines no actual GameTests, so — like a
successful `build` — a successful run there doesn't verify mod loading or behavior either.

The mod's source is 100% Kotlin (`src/main/kotlin/`, no `src/main/java/` directory exists — `compileJava`
legitimately runs as `NO-SOURCE`). `compileKotlin` is the task that actually compiles the mod.

Note: this repo lives under a OneDrive-synced folder on Windows. Any Gradle task that deletes/recreates files
under `build/` — `clean`, `compileKotlin`, `compileJava`, `jarJar`, `processResources` — can intermittently fail
with `Unable to delete directory ...`/`AccessDeniedException`/`Cannot snapshot ...: not a regular file` errors on
essentially any subdirectory of `build/` (compile caches, generated sources, jarJar outputs, etc.) — OneDrive/
antivirus briefly holding a file handle, not a real problem. Run `./gradlew --stop` and retry, removing the
specific stuck subdirectory first if the retry hits the same path again; if it keeps moving to a different
subdirectory each retry, it's faster to `rm -rf build` once and do a clean build than to chase it file by file.

Note: `java.toolchain.languageVersion` in `build.gradle` is set to Java 25, but the CI workflow
(`.github/workflows/build.yml`) provisions JDK 21. Keep this in mind if a build works locally but not in CI, or
vice versa. The Kotlin toolchain (`kotlin { jvmToolchain(25) }`) is set to match.

## Configuration

- `minecraft_version`, `neo_version`, `mod_id`, `mod_version`, etc. live in `gradle.properties` and are injected
  into `src/main/templates/META-INF/neoforge.mods.toml` at build time via the `generateModMetadata` Gradle task
  (placeholders like `${mod_id}` are expanded from the matching Gradle property).
- The mod declares support for Minecraft `26.1.2` through `26.3` inclusive via
  `minecraft_version_range=[26.1.2,26.4)` in `gradle.properties`. NeoForge has not shipped a stable build for
  Minecraft 26.3 yet, so `neo_version` is pinned to the latest 26.3 beta (`26.3.0.37-beta`); re-pin it to a
  stable 26.3.0.x build once one exists.
- `neo_version` and `neo_version_range` are deliberately separate properties: `neo_version` is the single exact
  NeoForge artifact ModDevGradle compiles and runs against, while `neo_version_range` is the floor for the
  `neoforge` mod dependency in `neoforge.mods.toml` (pinned to the previously-verified 26.1.2 build). Do not
  collapse these back into one property — doing so would tie the minimum required NeoForge loader version to
  whatever the current compile target is, breaking older installs when `neo_version` is bumped forward. See
  `MEMORY.md` for the reasoning.
- The `net.neoforged.moddev` Gradle plugin version in `build.gradle` matters for which Minecraft versions can
  actually be decompiled/built — an older plugin version can fail with decompile errors on newer Minecraft
  versions even though the NeoForge artifact itself resolves fine. If `./gradlew build` fails inside
  `createMinecraftArtifacts`/`recompile` after a version bump, try bumping this plugin to its latest release
  before assuming the target Minecraft version is unbuildable. See `MEMORY.md`.
- Runtime mod config (`SetHomeConfig`) is a NeoForge `ModConfigSpec` registered in `SetHomeMod`'s `init` block via
  `SetHomeMod.resolveConfigType()` (a private function on `SetHomeMod`'s companion object, not a direct
  `ModConfig.Type.COMMON` reference) and written to `config/sethome/sethome-common.toml`. It exposes
  `maxHomesPerPlayer` plus the storage-backend settings described below. The indirection exists because
  FancyModLoader renamed `ModConfig.Type.COMMON`/`SERVER` to `LOCAL`/`SYNCED` starting with the loader version
  bundled from Minecraft 26.3 onward — see `MEMORY.md` before touching this method or `SetHomeMod`'s config
  registration.
- The mod's source is Kotlin, so it needs [KotlinLangForge](https://modrinth.com/mod/kotlin-lang-forge) (KLF,
  `btwonion`, modId `klf`) installed on the server at runtime to supply the Kotlin stdlib/reflect/coroutines/
  serialization classes. Unlike a normal mod dependency, this is declared via `neoforge.mods.toml`'s top-level
  `modLoader="klf"` / `loaderVersion="[1,)"` — **not** a `[[dependencies.sethome]]` entry — because a standard
  FML dependency version check against KLF's full artifact version string
  (`kotlinlangforge_version` in `gradle.properties`, e.g. `2.14.1-k2.4.20-3.1+neoforge`) does not work: confirmed
  by testing, FML reports the installed version as `[MISSING]` (unparseable as a plain Maven version) even when
  the range matches exactly. The `loaderVersion` instead checks KLF's own small "language provider version"
  (`3.1` for this release). The Kotlin Gradle plugin version in `build.gradle` (currently `2.4.20`, a literal —
  the `plugins {}` block can't reference a property) is deliberately pinned to match the Kotlin version KLF
  bundles; bump both together, never independently, and re-check KLF's Minecraft/NeoForge support range on each
  bump. This replaced an earlier dependency on "Kotlin for Forge" (KFF, `thedarkcolour`), which didn't support
  this project's `minecraft_version=26.3` pin — see `MEMORY.md` for that history and the full KLF investigation.
  KLF is declared as a plain `implementation` dependency rather than `jarJar`: this keeps it off the packaged mod
  jar (only `jarJar`-wrapped dependencies get embedded) while still letting ModDevGradle's dev runs
  (`runClient`/`runServer`/`runGameTestServer`) pick it up as an installed mod.
- `jarJar` does not embed a dependency's transitive dependencies automatically. `build.gradle` explicitly `jarJar`s
  `mongodb-driver-sync`'s transitive `mongodb-driver-core` and `bson` (and `sqlite-jdbc`) as their own separate
  entries for this reason — keep this in mind when changing or adding a `jarJar`-bundled dependency.

## Architecture

Package root: `de.alexandermora.sethome`, all Kotlin under `src/main/kotlin/`.

- `SetHomeMod` — `@Mod`-annotated entry point, a Kotlin `class` with a primary constructor taking `ModContainer`.
  Loaded via KotlinLangForge's `klf` language loader (`modLoader="klf"` in `neoforge.mods.toml` — see
  Configuration above), which accepts a constructor taking any of `IEventBus`/`ModContainer`/
  `KotlinModContainer`/`Dist` (in any combination); this mod only needs `ModContainer`.
  `MOD_ID`/`LOGGER`/`resolveConfigType()` live on its `companion object`. Its
  `init` block registers the config spec, registers `HomeCommands::register` and this-bound `::onServerStarting`/
  `::onServerStopping` references on the event bus, and initializes/shuts down `HomeStorageService` (data
  directory is `config/sethome/`, resolved via `Path.of("config", MOD_ID)` — relative to the server's working
  directory, not the world save directory). This mod is server-side only (see `side="SERVER"` in
  `neoforge.mods.toml`).
- `command/HomeCommands` — a Kotlin `object` (singleton) holding Brigadier command registration and handlers for
  `/sethome`, `/home`, `/homes`, `/delhome`. All player-facing validation and error messaging lives here; handlers
  catch exceptions from the storage layer and translate them into `sendFailure` messages rather than letting them
  propagate. Several Minecraft/NeoForge Java getters are called explicitly (e.g. `player.getUUID()`,
  `player.getYRot()`) rather than via Kotlin's synthetic-property sugar (`.uuid`, `.YRot`) — the JavaBeans
  decapitalization rule Kotlin uses for that sugar capitalizes oddly for names like `UUID`/`YRot`/`XRot`, so
  explicit calls were kept for clarity/safety; don't "clean these up" into property syntax without checking the
  resulting property name is actually what you expect.
- `data/HomeLocation` — an immutable `data class` for a stored home (dimension id, x/y/z, yaw/pitch). Kotlin data
  classes have no equivalent of a Java record's compact constructor, so normalizing the dimension before it
  becomes part of the `val` (and therefore part of `equals`/`hashCode`/`toString`/`copy`) goes through a **private
  primary constructor plus a companion `operator fun invoke`**; callers still just write `HomeLocation(...)`.
  `@ConsistentCopyVisibility` keeps the generated `copy()` private too, so it can't bypass normalization. Dimension
  normalization itself (`normalizeDimension`) accepts both the modern `minecraft:overworld`-style identifier and
  the legacy `ResourceKey[minecraft:dimension / minecraft:overworld]` string form — needed because dimension
  identifiers have been persisted in both forms across versions.
- `data/HomesRepository` — the storage-backend interface (`load`/`setHome`/`getHome`/`getHomes`/`deleteHome`/
  `countHomes`/`close`) implemented by `HomesFileRepository`, `HomesSqliteRepository`, and `HomesMongoRepository`.
  `setHome` returning `false` means "a home with this name already exists for this player", not an error.
- `data/HomeStorageService` — a Kotlin `object` (singleton facade) used by the command layer. Enforces
  `maxHomesPerPlayer` from `SetHomeConfig` before delegating to the active repository, and must be
  `initialize()`d (from `ServerStartingEvent`) before use, else `repository()` throws `NullPointerException`.
  Selects and constructs the configured backend (`createDbRepository`, a `when` over `StorageMode`), falls back to
  `FILE` if the configured backend fails to `load()`, and runs `HomeMigration` when `migrateFromFile` is enabled.
  Migration re-runs on every server startup while `migrateFromFile` stays enabled (it is not one-time), only skips
  homes already present *in the target* repository — so the source file is never touched/consumed, meaning a home
  deleted from the DB target reappears on the next startup — and migrates via the repository directly, bypassing
  `HomeStorageService.setHome`'s `maxHomesPerPlayer` enforcement.
- `data/HomesFileRepository` — the default repository. Persists homes as TOML (via NightConfig) at
  `config/sethome/sethome.toml`, keyed by player UUID then home name. On a parse failure it backs up the broken
  file (`sethome.toml.broken-<timestamp>`) and resets to an empty file rather than crashing startup.
- `data/HomesSqliteRepository` / `data/HomesMongoRepository` — the other two `StorageMode` backends, both fully
  wired into `HomeStorageService` (not placeholders). Each uses `lateinit var` for its connection/collection
  (assigned in `load()`) rather than a nullable field, so using either repository before `load()` throws
  `UninitializedPropertyAccessException` — the same "must be loaded first" contract `HomesFileRepository` gets for
  free from `HomeStorageService` always calling `load()` right after construction.
  `HomesSqliteRepository`'s companion `init` block explicitly `Class.forName("org.sqlite.JDBC")`s before any use,
  since the `jarJar`-shaded driver's service-loader registration has occasionally failed to auto-register itself —
  don't remove this as dead code. `HomesMongoRepository.load()` creates a unique index on `(playerId, homeName)`,
  which is what turns a duplicate `setHome` into a caught `MongoWriteException` (see Error handling convention
  below) rather than a silent overwrite.
- `data/HomeMigration` — an `internal object` (Kotlin's closest equivalent to Java package-private) with the
  `migrate(source, target)` one-way copy from `HomesFileRepository.exportAll()` into another backend.
- `config/StorageMode` — `enum class StorageMode { FILE, MONGODB, SQLITE }`, the single source of truth for
  selectable backends, referenced by both `SetHomeConfig.STORAGE_MODE` and `HomeStorageService`. There is no
  separate/unused second enum in this codebase — if you see a reference elsewhere to a `HomeStorageMode` enum or
  a `MARIADB` mode, that's stale documentation, not current code.

Every repository method that was `synchronized` in the original Java is now annotated `@Synchronized` (Kotlin has
no `synchronized` modifier) — don't drop these when editing repository methods, since `HomeStorageService` and the
command layer assume single-threaded-per-call access to the in-memory/connection state.

All home names and player-facing identifiers are lowercased/trimmed via a private `normalizeHomeName` helper that
is duplicated in `HomeCommands`, `HomesFileRepository`, `HomesSqliteRepository`, and `HomesMongoRepository` — keep
all four in sync if that logic changes.

## Error handling convention

This codebase uses `runCatching`/`onFailure` instead of `try`/`catch` throughout — an established repo-wide
convention (see `MEMORY.md`'s "try/catch → runCatching/onFailure conversion" for the full rationale and history),
not a partial cleanup. New code should not reintroduce `try`/`catch`.

- `runCatching`'s own catch is unconditionally `catch (e: Throwable)`, with no way to narrow it directly. Every
  `onFailure`/`getOrElse` site must immediately guard with `if (ex !is ExpectedType) throw ex` before doing
  anything else, so unrelated exceptions still propagate instead of being silently absorbed. What used to be a
  multi-catch `try` becomes a single `onFailure`/`getOrElse` with an ordered `when (ex) { is Specific -> ...; is
  General -> ...; else -> throw ex }` — most-specific type first, matching the original catch-clause order.
- Use `getOrElse` (not `onFailure`) when the recovery path must produce a replacement value — `onFailure`'s lambda
  always returns `Unit` and can't change what the `Result` holds. Examples: `SetHomeMod.resolveConfigType()`'s
  `COMMON`/`LOCAL` fallback, `HomeStorageService.initialize()`'s DB-to-FILE fallback.
- Some `onFailure` blocks are an intentional silent recovery with no following `getOrThrow()`/rethrow — e.g.
  `HomesFileRepository`'s malformed-file backup-and-reset, `HomesSqliteRepository.close()`'s log-only handler.
  Don't append a trailing `getOrThrow()` to these; that would turn a graceful recovery into a crash.
