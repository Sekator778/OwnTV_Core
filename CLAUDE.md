# OwnTV Core Instructions

## What this repo is

The shared engine behind the OwnTV apps. Two Android library modules, published together:

- **`:core`** — Room database and migrations, playlist sync and parsing (M3U / Xtream / Stalker),
  EPG, backup and restore, profiles, downloads, settings storage, and **every user-visible string**.
- **`:player-core`** — the playback engine: libmpv plus the Media3/ExoPlayer handoff, the fallback
  ladder, watchdogs and stream diagnostics. Depends on `:core`.

## What depends on this

- **OwnTV for Android TV** (`E:\MEGA\CODE\AI\OwnTV`) — shipping.
- **OwnTV for mobile** (`E:\MEGA\CODE\AI\OwnTV_Mobile`) — not built yet.

**A change here affects two shipping apps.** Nothing is released from here until the TV app has been
rebuilt against it, and eventually the mobile app too. There is no such thing as a change that only
affects one app.

## Invariants — these are not preferences

1. **Core owns the database.** The Room version, entities, migrations and the schema JSONs under
   `core/schemas/` change *only here*. Neither app adds a migration, and neither app carries a
   schema directory. CI fails if a schema JSON changes.
2. **Core never imports from an app.** The dependency arrow is one-way: app → `:player-core` →
   `:core`. If core needs something from the host app, it takes a hook that the app assigns — see
   `CoreBuildInfo`, `CrashRecorder.diagnostics`, `LiveSessionLimit.report` and
   `SubtitleFontAssets.resourceOf`, all assigned in the app's `onCreate` **before Koin starts**.
3. **Core stays UI-framework-neutral.** `compose-runtime` is allowed and is the only Compose
   artifact permitted. `compose-ui`, `compose-foundation`, `androidx.tv.*`, navigation, coil and
   material are not — adding any of them makes core unusable from the mobile app.
4. **Nothing moves in without a consumer here.** If a file's only caller is in an app, it belongs in
   that app.
5. **Both modules ship on one version.** Set in `extra["coreVersion"]` in the root build file.

## High-risk areas — all of them now live here

Inspect first, explain the plan, make small changes, build, then ask for device testing:

- Room database version numbers, schema JSONs, and migrations between public releases
- source sync/import logic; M3U clear-then-insert; Xtream incremental/stable upsert
- favourites / history / progress / resume relinking
- backup / export / import, including encrypted password backup
- profile-specific settings and data
- playback engine fallback between ExoPlayer and mpv, Live TV watchdog/reconnect logic

**Defensive playback code stays.** The fallback ladder, stall watchdogs, surface-reset dance and
reconnect logic exist because the "impossible" happens routinely on real TV silicon. Simplify the
shape of that code, never its coverage.

## Build commands — use ONLY these

```bash
# Compile-check both modules
./gradlew :core:assembleRelease :player-core:assembleRelease 2>&1 | tail -20

# Unit tests. 309 in :core, 118 in :player-core.
./gradlew :core:testDebugUnitTest :player-core:testDebugUnitTest 2>&1 | tail -25

# Compile the instrumentation APKs. Installs nothing — safe.
./gradlew :core:assembleDebugAndroidTest :player-core:assembleDebugAndroidTest 2>&1 | tail -10

# Everything, including lint. The full gate.
./gradlew build 2>&1 | tail -20
```

Builds are pre-approved: run them automatically after a meaningful change, never ask the owner to.
If a build fails, fix it before continuing.

**NEVER run `connected*AndroidTest`.** Gradle uninstalls the app to install the test APK, which wipes
the owner's catalog, playlists, profiles and history. Emulator only, and ask first.

`local.properties` is developer-local and never committed:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

## Verification always runs the i18n gate too

All four must pass before any string change is finished:

```bash
python tools/i18n/validate_strings.py            # must print "i18n validation OK"
python tools/i18n/check_hardcoded_strings.py verify --bootstrap
python tools/i18n/gen_supported_locales.py check
python tools/i18n/check_text_overflow.py
```

If `check_hardcoded_strings` reports STALE CLASSIFICATION after code was deleted, run
`python tools/i18n/check_hardcoded_strings.py prune-safe` and re-verify.

`check_pseudo_locales.py` is an **app-repo** check — it inspects a built APK, and this repo produces
AARs. On Windows, `test_i18n_tools.py` reports ~20 errors that are a `cp1252` default-encoding
artifact of the local Python, not real failures; CI runs it on Linux where they pass.

## New user-visible text ships translated — never English-only

Every user-visible string in both apps lives here, so this rule lives here too. A change that adds
user-visible text is not finished until that text exists in **every packaged locale**. Deleting a
string means deleting it from the base locale *and* every translation in the same change.

1. **Reuse before adding.** Search `core/src/main/res/values/` first — `common_reset`,
   `common_cancel`, `common_delete` and friends already exist and are already translated.
2. Add the base string to the right `core/src/main/res/values/strings_*.xml`, with a
   `<!-- Translators: ... -->` comment.
3. Translate into all 23 other locales: `ar bn cs da de es es-rUS fr hi it ja ko ml nb nl pl pt
   pt-rPT ru sv tr zh-rCN zh-rTW`. Real translations, no English copies, no TODOs. For more than two
   or three locales, script the insertion so placement is identical everywhere.
4. **Plurals follow each locale's own CLDR rule**, not English's two forms. The authoritative table
   is `_PLURAL_RULES` in `tools/i18n/validate_strings.py`. Keep the placeholder, wrapped as
   `<xliff:g id="...">%1$d</xliff:g>`.
5. Match each file's own quote and apostrophe conventions — `„…“` de, `« … »` fr, `「…」` ja,
   `«…»` ru, `”…”` sv.

Log messages, tags and other non-user-visible literals are not translated; they belong in
`tools/i18n/safe_literals.txt`.

## Every new module needs THREE registrations

Not one. Plan 1 caught `:player-core` missing the second, which would have let an untranslated
string ship:

1. `SRC_ROOTS` in `tools/i18n/check_hardcoded_strings.py` (and `SOURCE_ROOTS` in
   `check_number_locale.py`)
2. a `kotlinSources.from(...)` line on `verifyI18nLiterals` in `core/build.gradle.kts`
3. its own line in the build commands above and in `.github/workflows/build.yml`

## Working with the apps

Local development uses a Gradle composite build, so a core edit reaches the app with no publish
step. In `~/.gradle/gradle.properties` — **never** in any repo:

```properties
owntv.corePath=E:/MEGA/CODE/AI/OwnTV_Core
```

**Core changes are made here, never worked around in an app.** If the TV app needs different
behaviour from core, change core and rebuild the app against it. A workaround in an app is a bug the
mobile app will hit again.

Publishing is by tag: push `core-<version>`, matching `extra["coreVersion"]`, and CI publishes both
modules to GitHub Packages.

## Git rules

- Never push, pull, commit or tag unless the owner explicitly asks. He handles git himself.
- Always commit as the identity already set in this repo's `.git/config`. Never set it globally and
  never substitute another name.
- **Never** add Claude/AI co-author trailers, attribution, or any mention of AI in commit messages,
  notes or git metadata.

## Communication style

Every answer and every finished piece of work ends with a short plain-language explanation — no
class names, no method names, no engine internals in that part. Technical detail goes above it.

```
**Simple explanation**
<what is happening, in plain words>

**What this means for you**
<the practical takeaway>
```
