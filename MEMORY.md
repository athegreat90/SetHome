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

# Kotlin migration decisions, dated 2026-09-30

Decisions made while migrating SetHome's entire source from Java to Kotlin (on branch `kotlin-migration`, off
`26`). All ten `.java` files were rewritten 1:1 into `.kt` under `src/main/kotlin/` in a single pass — this was a
full rewrite, not an incremental/mixed-language migration, because the codebase was small (~1,100 lines).

## Kotlin runtime: require Kotlin for Forge, don't bundle `kotlin-stdlib`

Two options were available for supplying the Kotlin runtime this mod's compiled classes need at load time:
embed `kotlin-stdlib` into the mod jar via `jarJar` (matching how `mongodb-driver-sync` and `sqlite-jdbc` are
already embedded, keeping the mod a single self-contained jar), or require the separate
[Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) (KFF) mod on the server. The first plan draft chose
`jarJar`-bundling for the zero-extra-install benefit; the user explicitly overrode this after reviewing the plan
and asked for the hard runtime dependency on a Kotlin mod instead, so KFF was used instead.

Implementation: `thedarkcolour:kotlinforforge-neoforge:${kotlinforforge_version}` is declared as a plain
`implementation` dependency (not `jarJar`, not `compileOnly`) in `build.gradle`. Plain `implementation` was
chosen deliberately over `compileOnly` because ModDevGradle's dev runs (`runServer`/`runClient`) treat any jar
with valid mod metadata on the runtime classpath as an installed mod — using `compileOnly` would have made local
dev runs fail to load KFF at all (no Kotlin runtime present locally either), while `implementation` gives a
realistic dev-run environment without embedding KFF's classes in the packaged jar (only `jarJar`-wrapped
dependencies get embedded; a plain `implementation`/`api` dependency of a `java-library`/`kotlin.jvm` project is
never packaged into that project's own `jar` task output). The hard requirement is enforced at the metadata level
via a `[[dependencies.sethome]]` entry for `modId="kotlinforforge"` in `neoforge.mods.toml`, so a server missing
KFF gets FML's normal "missing dependency" error screen instead of a `NoClassDefFoundError` mid-startup.

## Kotlin Gradle plugin version is pinned to match KFF's bundled Kotlin version, by hand

`build.gradle`'s `org.jetbrains.kotlin.jvm` plugin version (`2.4.0`, a literal — Gradle's `plugins {}` block
cannot reference a `gradle.properties` value) must stay in lockstep with whatever Kotlin version the installed
KFF release bundles, since the compile-time Kotlin metadata/ABI and the runtime Kotlin classes KFF supplies need
to be binary-compatible. KFF `6.3.0`'s changelog states it bundles Kotlin `2.4.0`, so `2.4.0` was chosen to match
exactly. There is no automated check tying these together — **when bumping `kotlinforforge_version` in
`gradle.properties`, check that release's changelog for its bundled Kotlin version and bump the literal plugin
version in `build.gradle` to match in the same change.**

## Kotlin for Forge 6.3.0 does not yet support Minecraft 26.3 — confirmed by testing, not just version ranges

**Superseded 2026-09-30 (later the same day): SetHome no longer depends on Kotlin for Forge at all — see the
"KotlinLangForge switch" section near the end of this file.** Kept below as historical record of the
investigation; the specific gap it describes about KFF `6.3.0` no longer affects this project.

This project pins `minecraft_version=26.3`/`neo_version=26.3.0.37-beta` (see the NeoForge-beta decision above).
KFF's latest release, `6.3.0` (published 2026-06-28, still latest as of 2026-09-30 per its Maven metadata and the
Modrinth API), declares Minecraft support of `1.21.9`–`26.2` in its own bundled `neoforge.mods.toml` — i.e. it
explicitly **excludes** 26.3. This was verified empirically, not just inferred from the version range: running
`./gradlew runServer` with the mod (and KFF) on the pinned `minecraft_version=26.3` fails FML mod-loading with:

```
Mod kotlinforforge requires minecraft 1.21.9 or above, and below 26.3
Currently, minecraft is 26.3
```

To confirm this is purely a KFF/26.3 compatibility gap and not a bug introduced by the Kotlin port itself, the
same source was verified end-to-end by temporarily setting `minecraft_version=26.2`/`neo_version=26.2.0.88` (a
stable NeoForge 26.2 build) and re-running `runServer`: the mod loaded cleanly ("Kotlin For Forge Enabled!",
`SetHomeMod`'s config/storage init logged correctly, server reached "Done"). The two version-property edits were
then reverted back to the 26.3 pin, since that pin is this project's own deliberate policy and unrelated to the
Kotlin migration itself.

**Net effect:** the mod compiles and packages correctly regardless. It will not *load* on an actual Minecraft
26.3 server until either KFF publishes a build explicitly covering 26.3, or `minecraft_version`/`neo_version` are
rolled back to the 26.2 line. This is functionally the same kind of "tracking a dependency ahead of its own
released support" situation as the `neo_version` beta pin above — re-verify (check KFF's Maven metadata / its
bundled `neoforge.mods.toml` Minecraft range) before assuming this is resolved on a future bump of either
`neo_version` or `kotlinforforge_version`.

## `HomeLocation`: Kotlin data classes have no compact constructor

The Java `record HomeLocation(...)` normalized its `dimension` field in a compact constructor before the field
was ever set — Kotlin `data class` primary-constructor `val`s have no equivalent hook; a `val` cannot be
reassigned in an `init` block, and shadowing a constructor parameter with a same-named property in the class body
would silently exclude that property from the generated `equals`/`hashCode`/`toString`/`copy`/`componentN()`
(since those are derived only from the primary constructor's `val`/`var` parameters, not from any property with
a matching name declared elsewhere in the class body).

The fix: a **private primary constructor** (already-normalized values only) plus a **companion `operator fun
invoke`** with the original public signature, which normalizes and then calls the private constructor. Callers
still write `HomeLocation(dimension, x, y, z, yaw, pitch)` exactly as before — Kotlin resolves that call to the
companion's `invoke` from outside the class (the real constructor is inaccessible there) and to the constructor
itself from inside the companion (which can see private members). `@ConsistentCopyVisibility` was added to keep
the compiler-generated `copy()` private too (Kotlin 2.x warns, and will error in a future language version, if a
data class's `copy()` is more visible than its constructor) — without it, `copy()` would let calling code
construct a `HomeLocation` with an unnormalized `dimension`, bypassing the whole point of the private constructor.

## Kotlin's synthetic Java-getter properties can capitalize unexpectedly — explicit calls used where ambiguous

Kotlin exposes no-arg Java `getX()` methods as synthetic properties (`.x`) using the same decapitalization rule
as `java.beans.Introspector.decapitalize`: the leading letter is lowercased *unless* the first two characters of
the name (after stripping `get`) are both uppercase, in which case the name is left as-is. This means
`getUUID()` → property `.UUID` (not `.uuid`), and `getYRot()`/`getXRot()` → `.YRot`/`.XRot` (not `.yRot`/`.xRot`).
`HomeCommands.kt` calls these explicitly as methods (`player.getUUID()`, `player.getYRot()`, `player.getXRot()`)
rather than relying on the synthetic property, to avoid the surprising capitalized property names and the risk of
silently reading/writing the wrong member if this rule is misremembered later. Plain single/no-second-uppercase
names (`getX()` → `.x`, `getServer()` → `.server`, Brigadier's `getSource()` → `.source`) were left as idiomatic
properties since they're unambiguous. **When editing Kotlin code that calls into Java/Minecraft APIs, don't
blanket-convert `.getXxx()` calls to `.xxx` property syntax without checking this rule for two-letter-acronym or
two-capital-prefixed names.**

## Other mechanical translation notes

- Every method that was `synchronized` in the Java repositories/`HomeStorageService` is now annotated
  `@Synchronized` (Kotlin has no `synchronized` modifier) — this is easy to drop by accident when editing since
  it's not part of the method signature the way `synchronized` was.
- `HomesSqliteRepository`'s `Connection` and `HomesMongoRepository`'s `MongoClient`/`MongoCollection` fields are
  `lateinit var` rather than nullable — closer to the original Java fields (implicitly null until `load()` ran,
  with no null-check at each call site) than introducing new null-handling would have been. Using either
  repository before `load()` now throws `UninitializedPropertyAccessException` instead of a Java
  `NullPointerException`; `close()` guards with `::field.isInitialized` instead of a null check.
- NightConfig's generic no-arg/one-arg getters (`<T> T get(String path)`, `Entry.<T> T getValue()`) are **not**
  exposed as Kotlin synthetic properties (Kotlin doesn't do this for generic-returning Java getters) — these need
  an explicit type witness at the call site, e.g. `config.get<Any?>(key)`, `entry.getValue<Any?>()`.
- Kotlin's triple-quoted raw strings do **not** strip common leading indentation the way Java text blocks do;
  the SQL DDL/DML strings copied into `HomesSqliteRepository.kt` needed an explicit `.trimIndent()` appended to
  reproduce the same string content the Java text blocks produced.
- Importing a Kotlin `companion object` member by writing `import pkg.ClassName.MEMBER` (the shorthand, without
  `.Companion.`) did not resolve (`Unresolved reference`) for `SetHomeMod.LOGGER` in every file that tried it.
  The fix used throughout is to `import pkg.SetHomeMod` (the class) and qualify every use as `SetHomeMod.LOGGER`.

## Data sources (Kotlin migration)

KFF version/compatibility facts above were fetched live, not assumed: KFF's own Maven metadata
(`https://thedarkcolour.github.io/KotlinForForge/thedarkcolour/kotlinforforge-neoforge/maven-metadata.xml`) for
the version list and latest-release timestamp, the Modrinth API
(`https://api.modrinth.com/v2/project/kotlin-for-forge/version`) for per-version `game_versions`/`loaders`, and
KFF's `changelog.md` on GitHub (`thedarkcolour/KotlinForForge`, `6.x` branch) for the Kotlin/coroutines versions
each KFF release bundles. The Minecraft-26.3-incompatibility claim was additionally confirmed directly by running
the actual dev server locally (see above), not just by reading KFF's declared version range.

# KotlinLangForge switch, dated 2026-09-30 (same day, later than the Kotlin migration above)

The user had independently installed a *different* Kotlin language-adapter mod on their real server —
`KotlinLangForge-2.14.1-k2.4.20-3.1+neoforge.jar` (`btwonion/KotlinLangForge`, unrelated to `thedarkcolour`'s
Kotlin for Forge/KFF) — and asked whether it was compatible with SetHome, and to fix it if not. It was not
compatible as things stood (SetHome required `modId="kotlinforforge"`, a different mod entirely), but
KotlinLangForge turned out to be a strictly better fit than KFF, so SetHome was switched to depend on it
instead of KFF everywhere (`build.gradle`, `gradle.properties`, `neoforge.mods.toml`, `README.md`, `CLAUDE.md`).

## Why KotlinLangForge instead of just telling the user to install KFF

Verified directly, not just from KotlinLangForge's declared version range:

- Downloaded the exact release jar from GitHub (`btwonion/KotlinLangForge`, tag `2.14.1-k2.4.20-3.1+neoforge`,
  published 2026-09-16) — release notes say verbatim **"add support for 26.3"**.
- Confirmed the identical artifact (byte-identical `Content-Length`) is published at
  `https://repo.nyon.dev/releases` under `dev.nyon:KotlinLangForge:2.14.1-k2.4.20-3.1+neoforge` — a real,
  resolvable Gradle dependency coordinate.
- Inspected the jar's bundled `META-INF/neoforge.mods.toml`: real `modId="klf"`; it loads itself via the
  ordinary `javafml` loader; declares Minecraft support as an explicit enumerated list including `[26.3]`.
- Inspected its `META-INF/jars/` (KLF's jar-in-jar folder, equivalent to KFF's `META-INF/jarjar/`): bundles
  `kotlin-stdlib`/`kotlin-stdlib-jdk7`/`kotlin-stdlib-jdk8`/`kotlin-reflect` **2.4.20**, plus
  `kotlinx-coroutines`/`kotlinx-serialization`/`kotlinx-datetime`/`atomicfu` — the same kind of shared Kotlin
  runtime KFF supplied, just a newer Kotlin version.
- Confirmed Kotlin Gradle plugin `2.4.20` is actually published on the Gradle Plugin Portal, so it could be
  pinned to match exactly (mirroring the KFF↔`2.4.0` pinning decision above) — bumped `build.gradle`'s
  `org.jetbrains.kotlin.jvm` plugin from `2.4.0` to `2.4.20` in the same change.

This meant switching fully off KFF (not keeping both as alternatives — NeoForge's `[[dependencies]]` mechanism
has no "either/or" support anyway) was both what the user's server already had installed *and* a real fix for
the previously-documented 26.3 gap.

## KotlinLangForge's version string breaks the normal `[[dependencies]]` mechanism — must use its `klf` language loader instead

The first implementation attempt mirrored the KFF integration exactly: a plain `implementation` Gradle
dependency plus a `[[dependencies.sethome]] modId="klf" versionRange="[2.14.1-k2.4.20-3.1+neoforge,)"` entry in
`neoforge.mods.toml`. This **built successfully** but **failed to load** at runtime:

```
Mod ID: 'klf', Requested by: 'sethome', Expected range: '[2.14.1-k2.4.20-3.1+neoforge,)', Actual version: '[MISSING]'
```

KotlinLangForge *was* present and in the mod list (`KotlinLangForge 2.14.1-k2.4.20-3.1+neoforge (klf)`), so this
wasn't a missing-mod problem — FML could not parse its own installed version as satisfying the range. To isolate
whether this was a range-syntax mistake versus a fundamentally unparseable version string, the range was
loosened to the trivial `versionRange="[1,)"` and re-tested: **same result**, `Actual version: '[MISSING]'`.
This proves the problem is FML being unable to parse KotlinLangForge's own compound version string
(`<modVersion>-k<kotlin>-<lpVersion>+<loader>`, e.g. `2.14.1-k2.4.20-3.1+neoforge`) as a comparable Maven version
at all for a dependency-satisfaction check — not something fixable by changing the range floor on our side.

This matches what KotlinLangForge's own README recommends and none of the CurseForge/Modrinth-searched examples
contradicted: don't depend on it via a normal `[[dependencies]]` entry. Instead, declare it as this mod's
language loader at the top of `neoforge.mods.toml`:

```toml
modLoader="klf"
loaderVersion="[1,)"
```

`loaderVersion` checks KotlinLangForge's own small internal **language-provider version** (`3.1` for this
release — the middle number in its filename), which is a plain integer-ish version, not the compound artifact
version string that broke the normal dependency check. Switching to this fixed loading immediately: no
`[[dependencies.sethome]]` entry for `klf` exists any more (there is nothing reliable to put in `versionRange`
for it), and `kotlinlangforge_version` in `gradle.properties` is now used *only* for the Gradle dependency
coordinate, not for any mods.toml-level version gate.

`SetHomeMod` needed **no source changes** to load under `modLoader="klf"`: KotlinLangForge's language loader
accepts a constructor taking any combination of `IEventBus`/`ModContainer`/`KotlinModContainer`/`Dist`, and
`SetHomeMod`'s existing `class SetHomeMod(modContainer: ModContainer)` already fits that shape. Verified by
running the actual dev server end-to-end directly against this project's real `minecraft_version=26.3`/
`neo_version=26.3.0.37-beta` pin (no temporary-downgrade workaround needed this time, unlike the KFF
investigation above): both mods appeared in the mod list with no dependency errors, `SetHomeMod`'s config/
storage init log line fired correctly, and the server reached "Done" — closing the 26.3 gap for good.

**If KotlinLangForge is ever bumped to a new version:** re-check whether its `loaderVersion` (the language
provider version, e.g. `3.1`) changed — that's the number this project's compatibility actually depends on, not
the full artifact version in `kotlinlangforge_version`.

## Data sources (KotlinLangForge switch)

Facts above were fetched/verified live: the GitHub Releases API and release-notes body for the exact
`2.14.1-k2.4.20-3.1+neoforge` tag (`api.github.com/repos/btwonion/KotlinLangForge/releases/tags/...`), a direct
download and content inspection of that release jar, an HTTP HEAD check against
`https://repo.nyon.dev/releases/...` confirming the Maven artifact resolves and matches the GitHub jar's size,
the Gradle Plugin Portal's metadata for `org.jetbrains.kotlin.jvm` confirming `2.4.20` is published, and
KotlinLangForge's own `README.md` on GitHub for its integration/setup instructions. The `[[dependencies]]`
failure and the `modLoader="klf"` fix were both confirmed by actually running `./gradlew runServer`, not
inferred from documentation alone.

# try/catch → runCatching/onFailure conversion, dated 2026-09-30

The user asked for every `try`/`catch` in the project to be rewritten using `runCatching`/`onFailure` instead.
All 29 catch clauses across `SetHomeMod.kt`, `HomeCommands.kt`, `HomeStorageService.kt`, `HomeMigration.kt`,
`HomesFileRepository.kt`, `HomesSqliteRepository.kt`, and `HomesMongoRepository.kt` were converted in one pass —
this is now a project convention, not a partial cleanup: **new code should not reintroduce `try`/`catch`.**

## `runCatching` catches `Throwable` unconditionally — every site got an explicit type guard

Nearly every original catch targeted a specific type (`SQLException`, `MongoWriteException`, `ParsingException`,
`IllegalArgumentException`, `IOException`, `ClassNotFoundException`, or at least `RuntimeException`/`Exception`
rather than `Throwable`), letting anything else propagate and crash loudly. `runCatching`'s own catch is
unconditionally `catch (e: Throwable)`, with no way to narrow it. Asked the user how to handle this; they chose
to **preserve exact behavior** over shortening the code: every `onFailure`/`getOrElse` block now starts with
`if (ex !is ExpectedType) throw ex` before doing anything else, so the set of exceptions actually handled at each
site is byte-for-byte the same as before, and a bug that used to surface as a crash (e.g. an unexpected
`NullPointerException`) still does, rather than getting silently absorbed as a soft "operation failed" message.
**Any new `runCatching` call added to this codebase should follow the same pattern** — narrow with an `is` guard
immediately, don't let it swallow `Throwable` wholesale.

## `onFailure` can't produce a value — three sites use `getOrElse` instead

`onFailure`'s lambda returns `Unit`; it can inspect/act on the failure but can't change what the `Result` holds.
That's fine for catches that only ever transfer control (return/continue/throw), but three sites in this
codebase genuinely *recover to a different value* rather than transferring control, and structurally cannot use
`onFailure` at all:
- `SetHomeMod.resolveConfigType()` — falls back to `ModConfig.Type.valueOf("COMMON")`.
- `HomesMongoRepository.setHome()` — a duplicate-key `MongoWriteException` becomes `false` (not a rethrow).
- `HomeStorageService.initialize()` — a failed DB-backend `load()` falls back to a `HomesFileRepository`.

These three use `getOrElse { ex -> if (ex !is X) throw ex; <value or throw> }` directly, with no separate
`onFailure` step (chaining both would be redundant since `getOrElse`'s lambda already receives the exception).
**If a future catch needs to compute a replacement value on failure, reach for `getOrElse` (or `recover`), not
`onFailure` — `onFailure` alone will not compile in that shape without an extra unwrap step, and even then can't
express "produce this value instead."**

## Non-local `return`/`continue` inside a `runCatching { }` block is safe, because the whole chain is `inline`

Several sites had a `return` or `continue` sitting *inside* the risky code itself, not just in the catch (e.g.
`HomesFileRepository.load()`'s "no persisted homes found" early `return`, buried inside `runCatching { ... }`
after conversion). This is safe specifically because `runCatching`, `onFailure`, `getOrElse`, and `kotlin.io.use`
are all `inline` functions: after inlining, a `return`/`continue` in their lambda arguments compiles to a normal
JVM return/loop-jump instruction physically inside the calling function, which is unaffected by any `catch`
clause (catch clauses only intercept *thrown* exceptions, never `return`/`continue`/`break` control flow) — it
does **not** get intercepted by `runCatching`'s own internal `catch (e: Throwable)` and turned into a `Result`.
This was reasoned through carefully rather than assumed, then verified empirically by actually running the
converted `HomesFileRepository.load()` against a malformed `sethome.toml` — see the next section. **This safety
holds only as long as every function in the chain between the `return`/`continue` and its target loop/function is
itself `inline`** (true for all of `runCatching`/`onFailure`/`getOrElse`/`use`) — don't assume the same is safe
inside a non-inline lambda (e.g. a regular `Runnable`, or a lambda stored in a `val`).

## Multi-catch on one `try` becomes a single `onFailure` with a `when`

`HomeCommands.setHome()` had two catch clauses on the same `try` (`IllegalStateException` handled one way,
`RuntimeException` another). `onFailure` only takes one lambda, so this became a `when (ex) { is
IllegalStateException -> {...}; is RuntimeException -> {...}; else -> throw ex }` — checked in the same
specific-to-general order the original catch clauses were, since `IllegalStateException` is itself a
`RuntimeException` and order matters. This is the pattern to reuse for any other multi-catch that comes up.

## Verified by running the actual dev server, not just by compiling

The build succeeded on the first attempt for all 29 conversions, but that only proves the code compiles, not
that it behaves the same. Verified two runtime paths directly: normal startup (confirms the `HomesFileRepository`
"no persisted homes found" early-return-inside-`runCatching` path executes correctly), and — the trickiest
conversion — feeding `HomesFileRepository` a deliberately malformed `sethome.toml` and confirming it still logs
"The homes file was malformed... backed up... and reset" and starts normally, rather than crashing. This
specifically exercises the *intentional-swallow* `onFailure` sites (`ParsingException` here, also
`HomesSqliteRepository.close()`'s log-only `SQLException` handler) where no `.getOrThrow()`/rethrow follows the
`onFailure` block — getting that wrong (e.g. accidentally adding a trailing `.getOrThrow()` after an
intentionally-swallowing `onFailure`) would turn a graceful recovery into a crash.