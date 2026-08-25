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

Note: `java.toolchain.languageVersion` in `build.gradle` is set to Java 25, but the CI workflow
(`.github/workflows/build.yml`) provisions JDK 21. Keep this in mind if a build works locally but not in CI, or
vice versa.

## Configuration

- `minecraft_version`, `neo_version`, `mod_id`, `mod_version`, etc. live in `gradle.properties` and are injected
  into `src/main/templates/META-INF/neoforge.mods.toml` at build time via the `generateModMetadata` Gradle task
  (placeholders like `${mod_id}` are expanded from the matching Gradle property).
- Runtime mod config (`SetHomeConfig`) is a NeoForge `ModConfigSpec` registered in `SetHomeMod`'s constructor and
  written to `config/sethome/sethome-common.toml`. Currently it only exposes `maxHomesPerPlayer`.

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
