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

There is no test suite in this repo (no `src/test` directory) and no lint task beyond normal compilation.

Note: this repo lives under a OneDrive-synced folder on Windows. `./gradlew clean` (and occasionally
`compileJava`/`jarJar`) can intermittently fail with `Unable to delete directory ... Failed to delete some
children` — OneDrive/antivirus briefly holding a file handle in `build/`, not a real problem. Run
`./gradlew --stop` and retry (optionally removing the specific stuck subdirectory first) rather than treating it
as a build/code issue.

Note: `java.toolchain.languageVersion` in `build.gradle` is set to Java 25, but the CI workflow
(`.github/workflows/build.yml`) provisions JDK 21. Keep this in mind if a build works locally but not in CI, or
vice versa.

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
- Runtime mod config (`SetHomeConfig`) is a NeoForge `ModConfigSpec` registered in `SetHomeMod`'s constructor via
  `SetHomeMod.resolveConfigType()` (not a direct `ModConfig.Type.COMMON` reference) and written to
  `config/sethome/sethome-common.toml`. Currently it only exposes `maxHomesPerPlayer`. The indirection exists
  because FancyModLoader renamed `ModConfig.Type.COMMON`/`SERVER` to `LOCAL`/`SYNCED` starting with the loader
  version bundled from Minecraft 26.3 onward — see `MEMORY.md` before touching this method or `SetHomeMod`'s
  config registration.

## Architecture

Package root: `de.alexandermora.sethome`.

- `SetHomeMod` — `@Mod` entry point. Registers the config spec, registers `HomeCommands::register` on the
  command-registration event, and initializes `HomeStorageService` on `ServerStartingEvent` (data directory is
  `config/sethome/`). This mod is server-side only (see `side="SERVER"` in `neoforge.mods.toml`).
- `command/HomeCommands` — Brigadier command registration and handlers for `/sethome`, `/home`, `/homes`,
  `/delhome`. All player-facing validation and error messaging lives here; handlers catch exceptions from the
  storage layer and translate them into `sendFailure` messages rather than letting them propagate.
- `data/HomeLocation` — immutable record for a stored home (dimension id, x/y/z, yaw/pitch). Its compact
  constructor normalizes the dimension string via `normalizeDimension`, which accepts both the modern
  `minecraft:overworld`-style identifier and the legacy `ResourceKey[minecraft:dimension / minecraft:overworld]`
  string form — needed because dimension identifiers have been persisted in both forms across versions.
- `data/HomeStorageService` — static facade used by the command layer. Enforces `maxHomesPerPlayer` from
  `SetHomeConfig` before delegating to the active repository, and must be `initialize()`d (from
  `ServerStartingEvent`) before use, else it throws.
- `data/HomesFileRepository` — the only repository currently wired into `HomeStorageService`. Persists homes as
  TOML (via NightConfig) at `config/sethome/sethome.toml`, keyed by player UUID then home name. On a parse
  failure it backs up the broken file (`sethome.toml.broken-<timestamp>`) and resets to an empty file rather than
  crashing startup.
- `data/HomesMongoRepository` — an in-progress alternate backend; not yet wired into `HomeStorageService` and not
  functionally complete (its `load()` currently issues a placeholder query rather than loading real home data).
- `config/StorageMode` (`FILE`, `MONGODB`, `MARIADB`) and the top-level `HomeStorageMode` (`SAVED_DATA`, `FILE`)
  are two separate, currently-unused enums for selecting a storage backend. Neither is referenced by
  `SetHomeConfig` or `HomeStorageService` yet — storage backend selection is not actually configurable at
  runtime today, despite `HomesMongoRepository` existing. Treat both enums as signals of planned-but-unfinished
  work rather than as the current source of truth for which backends are supported.

All home names and player-facing identifiers are lowercased/trimmed via a local `normalizeHomeName` helper that
is duplicated in both `HomeCommands` and `HomesFileRepository` — keep both in sync if that logic changes.
