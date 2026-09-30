# MEMORY

Decisions made while widening SetHome's supported Minecraft/NeoForge range, dated 2026-09-30.

## Why `[26.1.2,26.4)` instead of a literal two-point range

The ask was "support from 26.1.2 to 26.3, both versions included." Taken literally that could mean just those
two exact versions, but the intent of a version *range* is to accept everything in between, including any
patch releases of 26.2 or 26.3. `minecraft_version_range=[26.1.2,26.4)` accepts 26.1.2 and every later patch of
26.1/26.2/26.3, and excludes 26.4 — which was already in active snapshot development (`26.4-snapshot-2`, per
Mojang's version manifest) at the time this decision was made, so it should not be silently accepted by an
open-ended upper bound.

## Why `neo_version` and `neo_version_range` are separate properties

Before this change, the `neoforge` mod dependency's `versionRange` in `neoforge.mods.toml` was built inline as
`[${neo_version},)` — i.e. the same exact version used to compile/run the mod also set the *minimum* NeoForge
loader version required to run it. That's fine when there's only one supported version, but breaks down once
`neo_version` gets bumped forward to widen support: bumping it to a newer build to compile against would also
raise the floor and silently reject the very older installs (26.1.2, 26.2) the range was supposed to keep
supporting.

The fix: `neo_version` stays the single exact artifact ModDevGradle resolves for compiling/running in
development, while a new `neo_version_range` property (`[26.1.2.109,)`, open-ended) is the actual floor
declared in `neoforge.mods.toml`. The `minecraft` dependency's `minecraft_version_range` is what actually caps
compatibility on the upper end (NeoForge requires both dependencies to be satisfied), so leaving
`neo_version_range` open-ended above is safe. This mirrors the split that already existed between
`minecraft_version` (informational/dev-target) and `minecraft_version_range` (enforced).

## The 26.3 beta initially failed to build — fixed by bumping the ModDevGradle plugin

As of 2026-09-30, NeoForge's Maven repository (`maven.neoforged.net`) has stable releases for the 26.1.x and
26.2.x lines (latest `26.1.2.112`, `26.2.0.88`) but only beta builds for 26.3 (latest `26.3.0.37-beta`) — even
though Minecraft 26.3 itself released as a stable game version on 2026-09-15. The decision was to compile/run
against the 26.3 beta now (`neo_version=26.3.0.37-beta`) rather than wait for a stable NeoForge 26.3 build.

The first attempt failed: `./gradlew build` errored inside ModDevGradle's own `recompile` step, before this
mod's source was even touched:

```
ERROR Line: 44, contents() in <anonymous net.minecraft.core.HolderSet$1> cannot override contents() in
net.minecraft.core.HolderSet.Named
  attempting to assign weaker access privileges; was public in /net/minecraft/core/HolderSet.java
```

This reproduced identically against 26.2.0.88 too (ruling out "beta-only" as the cause), but **not** against the
original 26.1.2.109 pin, which still built fine from cache. The root cause turned out to be the `net.neoforged.moddev`
Gradle plugin version, which was pinned at `2.0.141` in `build.gradle` — too old to correctly decompile/patch
vanilla sources for Minecraft 26.2+. Bumping it to `2.0.148` (latest as of 2026-09-30, released the day before)
fixed the decompile/recompile step for both 26.2.0.88 and 26.3.0.37-beta. **If a future Minecraft/NeoForge bump
hits a similar decompile-stage failure, check for a newer `net.neoforged.moddev` plugin release before assuming
the target Minecraft version itself is unbuildable.**

## Source code impact: `ModConfig.Type.COMMON` → runtime-resolved compat shim

Once the decompile issue was fixed, a real (non-tooling) incompatibility surfaced: `SetHomeMod.java` referenced
`ModConfig.Type.COMMON`, a `FancyModLoader` enum constant. NeoForge 26.1.2.109 and 26.2.0.88 depend on
FancyModLoader `11.0.15`/`11.0.16` (which has `COMMON`/`CLIENT`/`SERVER`/`STARTUP`), but NeoForge 26.3.0.37-beta
depends on FancyModLoader `12.0.8`, which **removed `COMMON` and `SERVER`, replacing them with `LOCAL` and
`SYNCED`** (a genuine breaking API change, matching the FancyModLoader major-version bump from 11.x to 12.x).
This means no single hardcoded enum reference can compile-and-run correctly across the whole 26.1.2–26.3 range.

Fix: `SetHomeMod.resolveConfigType()` resolves the constant by name at runtime (`Type.valueOf("LOCAL")`, falling
back to `Type.valueOf("COMMON")` via `IllegalArgumentException`), since `Enum.valueOf(String)` compiles against
any version of the enum regardless of which named constants actually exist. This was verified empirically, not
just reasoned about: the same compiled source built successfully both against `neo_version=26.3.0.37-beta` (the
shipped compile target) and, via a `-Pneo_version=26.1.2.109` override, against the original pin — confirming
the shim doesn't just compile but resolves the right constant on each loader generation.

**This is the one call site that needed a version-compat shim for the current codebase and current API surface.**
If NeoForge/FancyModLoader breaks other APIs this mod touches (`Commands`, `ResourceKey`, registries,
`ServerPlayer`, `Level`, the event bus) in a future bump, the same runtime-resolution pattern is the template to
follow — do not assume the rest of the codebase is exempt from this kind of break just because this one case was
found and fixed.

## Data sources

Version facts above were fetched live rather than assumed (no local knowledge of post-cutoff Minecraft/NeoForge
releases exists): `https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge` for NeoForge
build lists, and `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (plus per-version package
JSONs for `javaVersion`) for Minecraft release metadata and Java requirements.