# Changelog

All notable changes to SetHome are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).

Entries before 3.0.2 were reconstructed from the git history (commit dates and the `mod_version` recorded in
`gradle.properties` at each commit). Only `3.0.0-RC1` and `3.0.1` were ever tagged; other versions map to the commit
named in parentheses.

## [3.0.2] - 2026-11-26

Maintenance release: dependency, Gradle and CI upgrades. No gameplay change; supported Minecraft range
(26.1.2 through 26.3) and the minimum NeoForge loader version are unchanged.

### Changed
- Gradle wrapper 9.8.1 -> 9.9.2 (distribution SHA-256 pinned; wrapper JAR checksum verified).
- NeoForge compile/run target 26.3.0.92 -> 26.3.0.118. The runtime floor `[26.1.2.109,)` is untouched.
- KotlinLangForge 2.15.0 -> 2.20.1 (still Kotlin 2.4.21 and language-provider `3.1`).
- Bundled MongoDB driver (sync, core, BSON) 5.13.0 -> 5.15.0.
- Bundled sqlite-jdbc 3.53.4.0 -> 3.54.1.0.
- GitHub Actions pinned to newer commits: `actions/setup-java` v6.1.0 -> v6.3.0, `gradle/actions/setup-gradle`
  v6.4.0 -> v6.4.1, `Kira-NT/mc-publish` v3.3.1 -> v3.3.2.

### Fixed
- Release automation: a missing tag is now treated as absent and releases trigger from `main` (#4).
- Release automation: the Modrinth version check is now authenticated (#5).
- Release automation: a hidden Modrinth project is treated as having no visible versions (#6).

### Added
- This changelog.

## [3.0.1] - 2026-10-18

Build and CI maintenance; no gameplay change.

### Changed
- Gradle wrapper 9.2.1 -> 9.7.0 -> 9.8.1.
- GitHub Actions pinned to immutable commit SHAs (checkout v7, setup-java v6, setup-gradle v6,
  upload-artifact v7, download-artifact v8, mc-publish v3).

## [3.0.0] - 2026-10-18

First stable release of the Kotlin line, qualified on stable NeoForge 26.3.

### Added
- Release automation: a version change to a stable SemVer on the release branch builds the commit, creates the
  GitHub Release and publishes the jar to Modrinth, with offline Python tests.
- Modrinth publishing workflow and project ID, plus icon, gallery assets and a README Screenshots section.

### Changed
- NeoForge target 26.3.0.37-beta -> stable 26.3.0.92.
- MongoDB driver 5.8.0 -> 5.13.0 and sqlite-jdbc 3.49.1.0 -> 3.53.4.0.
- Kotlin 2.4.20 -> 2.4.21 together with KotlinLangForge 2.14.1 -> 2.15.0.

## [3.0.0-RC1] - 2026-09-30

### Changed
- Source migrated from Java to Kotlin; error handling converted from `try`/`catch` to `runCatching`/`onFailure`.
- The Kotlin runtime now comes from KotlinLangForge (`klf` language loader), replacing the unsupported-on-26.3
  Kotlin for Forge dependency. It must be installed on the server.
- Supported Minecraft range widened to 26.1.2 through 26.3 (`[26.1.2,26.4)`), with the compile target decoupled from
  the minimum NeoForge loader version.
- CI and project toolchain moved to JDK 25.

## [2.1.0-RC4] - 2026-09-09 to 2026-09-20

### Fixed
- `ClassCastException` when reading numeric home fields from MongoDB (2026-09-09).

### Changed
- README rewritten as project documentation; NeoForge 26.1.2.77 -> 26.1.2.109 (2026-09-20).

## [2.1.0-RC3] - 2026-08-25

### Added
- Configurable storage backends: FILE (default), SQLite and MongoDB, selected in `sethome-common.toml`.
- One-way migration from the file store to a database backend (`migrateFromFile`).

## [2.0.0-RC1] - 2026-08-22

### Changed
- Updated for Minecraft 26.1.2 (NeoForge 26.1.2.77).

## [1.0.0-RC1] - 2026-07-06

### Added
- MongoDB integration.

## [1.0.0-Beta6] - 2026-05-12

### Added
- Enums for the different storage-load configurations.

## [1.0.0-Beta3] - 2026-04-12

### Added
- Initial release for Minecraft 1.21.11 (NeoForge 21.1.226): `/sethome`, `/home`, `/homes` and `/delhome`, with a
  file-based store and a configurable `maxHomesPerPlayer` limit.

[3.0.2]: https://github.com/athegreat90/SetHome/compare/3.0.1...HEAD
[3.0.1]: https://github.com/athegreat90/SetHome/compare/3.0.0-RC1...3.0.1
[3.0.0-RC1]: https://github.com/athegreat90/SetHome/releases/tag/3.0.0-RC1
