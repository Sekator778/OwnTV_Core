# OwnTV Core Instructions

## Which repo does what — read this before changing anything

OwnTV is **three repositories**. Almost every "where does this go?" question is answered here. If a
change lands in the wrong repo it either breaks the other app or has to be undone later.

| Repo | Local path | GitHub | What lives there | State |
| --- | --- | --- | --- | --- |
| **Core library** | `E:\MEGA\CODE\AI\OwnTV_Core` | `ahXN00/OwnTV_Core` (public) | Everything shared: database, sync, EPG, backup, profiles, downloads, settings storage, playback engine, **and every user-visible string** | shipping — this repo |
| **TV app** | `E:\MEGA\CODE\AI\OwnTV` | `ahXN00/OwnTV` (public) | Android TV shell only: Compose-for-TV UI, navigation, player HUD | shipping |
| **Mobile app** | `E:\MEGA\CODE\AI\OwnTV_Mobile` | not created yet | Phone/tablet shell: Material 3 UI, navigation, player UI | **not built yet** — see below |

### Where does my change go?

| The change is about… | Repo |
| --- | --- |
| Room entities, DAOs, the database version, a migration, a schema JSON | **core** |
| Playlist sync or parsing — M3U, Xtream, Stalker | **core** |
| EPG fetching, matching, catch-up | **core** |
| Backup, export, import, encrypted passwords | **core** |
| Profiles, downloads, settings *storage* | **core** |
| The playback engine — mpv, ExoPlayer, the fallback ladder, watchdogs | **core** (`:player-core`) |
| **Any user-visible text, in any language** | **core** |
| A TV screen, a TV row, D-pad focus behaviour, the TV player HUD | **TV app** |
| A settings screen's *layout* (its stored value is core's) | **the app that shows it** |
| A phone/tablet screen | **mobile app**, once it exists |

**A workaround in an app is a bug the other app will hit again.** If the TV app needs different
behaviour from core, change core and rebuild the app against it. Never patch around core in an app.

### The mobile app — not built yet

`E:\MEGA\CODE\AI\OwnTV_Mobile` does not exist at the time of writing. It is planned work (Plan 3
creates the repo; Plan 4 builds the app to feature parity). Two things follow for anyone working
here **today**:

- **Core must stay usable from a phone app that does not exist yet.** That is the whole reason for
  invariant 3 below — no `compose-ui`, no `androidx.tv.*`. Adding a TV-shaped dependency to core is
  not caught by any test today; it is caught months later when mobile cannot compile.
- **When the mobile repo is created**, add it to the table above, to *What depends on this*, and to
  the release rule: from then on nothing is released from core until **both** apps have been rebuilt
  against it.

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

## Source of truth

- **The current repository code is always the source of truth.** Latest commits, current files and
  `CHANGELOG.md` override old chat context, old memory, and any tool summary.
- If old memory, previous chat context, CodeGraph output, Serena memory, Headroom snippets or RTK
  summaries conflict with the current files, **the current files win**.
- Never rely only on compressed context or graph summaries for an important decision. Verify against
  the real file before editing.
- Do not assume the current `main` has been published. For migrations, always distinguish the latest
  **published** core version, the current development version, and any PR/integration version.

Before large work, check the repo state:

```bash
git status
git branch --show-current
git log --oneline --decorate -10
```

## High-risk areas — all of them now live here

Inspect first, explain the plan, make small changes, build, then ask for device testing:

- Room database version numbers, schema JSONs, and migrations between public releases
- source sync/import logic; M3U clear-then-insert; Xtream incremental/stable upsert
- favourites / history / progress / resume relinking
- backup / export / import, including encrypted password backup
- profile-specific settings and data
- playback engine fallback between ExoPlayer and mpv, Live TV watchdog/reconnect logic

For these, use more than one tool: CodeGraph for the blast radius, Serena for the exact symbols, a
focused search to cross-check migration numbers / SQL column names / constants, then read the real
file. Never change one of these on the strength of a single tool's summary.

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
If a build fails, fix it before continuing. Do not invent other Gradle tasks or extra flags.

**NEVER run `connected*AndroidTest`.** Gradle uninstalls the app to install the test APK, which wipes
the owner's catalog, playlists, profiles and history. Emulator only, and ask first.

Use the toolchain already available to the shell session. Do not hunt the machine for JDKs, change
`JAVA_HOME`, or edit user-level Gradle properties as a routine build step. If a build reports a
toolchain problem, report the exact error and ask before changing anything outside this repo.

**A build failing roughly once a month is the owner's overclocked RAM, not a code bug.** Look for an
`hs_err_pid*.log` in the repo root; if one exists it is a JVM crash. Say so and ask him to reboot —
do not chase it, and do not attribute it to a commit without a repeat test.

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
AARs.

**CI also runs `tools/i18n/test_i18n_tools.py`, and it is not covered by the four gates above.** It
asserts on the *real* `locales.json`, so any catalogue edit — promoting a locale in particular — can
break it while all four gates stay green. Run it too whenever `locales.json` changes:

```bash
PYTHONUTF8=1 python tools/i18n/test_i18n_tools.py 2>&1 | grep -E "^(OK|FAILED|Ran )"
```

`PYTHONUTF8=1` is required on Windows: without it the local Python defaults to `cp1252` and the run
reports ~20 encoding errors that are not real failures. With it, the suite passes locally exactly as
it does on CI's Linux runners.

## New user-visible text ships translated — never English-only

Every user-visible string in both apps lives here, so this rule lives here too. A change that adds
user-visible text is not finished until that text exists in **every packaged locale**. Deleting a
string means deleting it from the base locale *and* every translation in the same change.

1. **Reuse before adding.** Search `core/src/main/res/values/` first — `common_reset`,
   `common_cancel`, `common_delete` and friends already exist and are already translated.
2. Add the base string to the right `core/src/main/res/values/strings_*.xml`, with a
   `<!-- Translators: ... -->` comment.
3. Translate into **every packaged locale**. The authoritative list is `tools/i18n/locales.json`
   (`tier: 1`, `packaged: true`); do not hardcode a locale list from memory, read that file. Real
   translations, no English copies, no TODOs. For more than two or three locales, script the
   insertion so placement is identical everywhere.
4. **Plurals follow each locale's own CLDR rule**, not English's two forms. The authoritative table
   is `_PLURAL_RULES` in `tools/i18n/validate_strings.py`. Keep the placeholder, wrapped as
   `<xliff:g id="...">%1$d</xliff:g>`.
5. Match each file's own quote and apostrophe conventions — `„…“` de, `« … »` fr, `「…」` ja,
   `«…»` ru, `”…”` sv, `„…”` hu.
6. Translation files carry **no** `<!-- Translators: -->` comments — those belong to the base locale
   only. The header is the XML declaration plus `<resources xmlns:xliff="…">`; `strings_settings.xml`
   additionally needs `xmlns:tools="http://schemas.android.com/tools"`.

**Promoting a locale from catalogue-only to shipped:** a tier-2 locale must have **no** resource
directory — the validator enforces it. When a locale becomes fully translated, set `tier: 1`,
`packaged: true`, `pickerVisible: true` in `tools/i18n/locales.json`, then run
`python tools/i18n/gen_supported_locales.py` to regenerate `SupportedLocales.kt` and the README
tables, before running the four gates.

Log messages, tags and other non-user-visible literals are not translated; they belong in
`tools/i18n/safe_literals.txt`.

**Weblate** hosts the translation project and reads the six `strings_*.xml` component files straight
from this repo's `main`. Push a string change and Weblate picks it up on its next repo poll; a
completed locale committed here arrives at 100% with no further action.

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

Leaving it unset resolves the pinned version from GitHub Packages instead, which needs `gpr.user` /
`gpr.token` (a PAT with `read:packages`) in that same file. That is what CI does. Credentials and
keystore values belong in `~/.gradle/gradle.properties` or CI secrets, **never** in any repo file.

**Core changes are made here, never worked around in an app.** If the TV app needs different
behaviour from core, change core and rebuild the app against it. A workaround in an app is a bug the
mobile app will hit again.

Publishing is by tag: push `core-<version>`, matching `extra["coreVersion"]`, and CI publishes both
modules to GitHub Packages.

### The pin bump is automated — do not do it by hand

`.github/workflows/bump-consumers.yml` runs after every successful publish and opens a pull request
on each consuming app. Never edit an app's `owntvCore` manually just because a version was published;
let the PR do it, so both files below always move together.

The PR changes exactly two things, and there is nothing else to merge because the apps hold no core
code:

1. **`owntvCore`** in the app's `gradle/libs.versions.toml`.
2. **`tools/i18n/locales.json`**, copied from here. This file is *deliberately duplicated* into every
   app: Gradle reads `packaged` from the **app's** copy at configure time to build `localeFilters`,
   so core's copy never reaches it. Left to a human this step is silently skipped, and a newly
   packaged language is stripped out of the APK with every check green.

Adding the mobile app later is one line — uncomment it in the workflow's `consumer` matrix.

**It needs `CONSUMER_BUMP_TOKEN`**, a PAT with `repo` scope on the consumer repositories, stored as
an Actions secret here. The built-in `GITHUB_TOKEN` cannot write to another repository, and pull
requests opened with it do not trigger the target repo's workflows — the PR would sit there with no
checks. If the token expires the bump job fails loudly with that message; the publish itself is
unaffected, and the job can be re-run on its own via `workflow_dispatch` with a version input.

Nothing is merged automatically, by design. The maintainer builds against core's *source* locally, so
he has already been running the change; the PR is what makes CI and the released APK agree with him.

## Workflow rules

- **Stay strictly on the requested point.** No unrelated, opportunistic or "while I am here"
  changes. Inspect and modify only the files the result requires.
- Do not start coding immediately unless the task is very explicit and small. First inspect the
  relevant files, then give a short plan and the list of files likely to change.
- **Work in small phases, and say how each phase will be verified before it starts** — a unit test,
  a build, or "the owner tests on TV". A phase with no check is not a phase. Where the logic is
  unit-testable, prefer writing the failing test first.
- After a meaningful phase, stop and let the owner test before continuing.
- Preserve existing behaviour unless the owner explicitly asks to change it.
- **Write the least code that solves the asked-for problem.** No abstraction for a single call site,
  no configurability nobody asked for, no public API "for later" — every function added has a caller
  in the same change. Remove imports and symbols *your* change made unused; mention pre-existing
  dead code rather than deleting it.

## Tool usage

Use MCP tools and read-only searches without asking permission — Serena, CodeGraph and Headroom are
trusted and every one of their tools is pre-approved. They are helpers, **not** final truth.

1. **CodeGraph** first, for repo-wide understanding: architecture, call paths, dependencies, impact
   and blast radius, "what breaks if this changes".
2. **Serena** second, for symbol-level precision: overview, declarations, references,
   implementations, diagnostics, focused reads and targeted symbol edits. Avoid global rename, safe
   delete and broad symbol replacement unless explicitly asked. Use real Kotlin sources as the test
   file, not a build script.
3. **Focused shell search** third — exact error strings, constants, log tags, migration names, SQL
   table/column names.
4. **Raw file reads** fourth, once the tools have identified the file.
5. **Build commands** after meaningful changes, from the pre-approved list above.

**Headroom** is for context compression and retrieval only. **RTK** compresses noisy shell output.
Neither is a source of truth; check the real file, diff or log when exact content matters.

If CodeGraph results look stale after large structural changes, run `codegraph sync` / `codegraph
index`. If Serena or the Kotlin language server fails, say so plainly, fall back to CodeGraph and
focused searches, keep edits small, build after each, and ask before any high-risk change.

## Testing rules

- **The owner is the main tester**, on real TV hardware or an emulator. Console checks never replace
  that. Do not claim something is fully tested until he confirms it.
- Running builds and unit tests to verify a change is always allowed and pre-approved — do it
  automatically, never ask him to build.
- **Never run device commands yourself.** For ADB/logcat/device work, write the exact Windows
  PowerShell commands, say whether they preserve or delete app data, and ask him to run them and
  share the output.
- For migration tests: `adb install -r` preserves data; never `adb uninstall` after test data exists
  except for a deliberate clean baseline. Capture logcat before and after first launch.
- Release logcat is obfuscated: ask for the app's `mapping.txt` alongside any crash trace.

## PR review and integration

1. Do not merge first. Inspect the PR against the correct baseline, and identify the current branch,
   the PR ref, the merge base, and the public release baseline if upgrade behaviour matters.
2. Flag whether it touches the database/migrations, sync/import, favourites/history/resume, playback
   fallback, profile data, backup/import, or settings storage.
3. For a large PR, produce a review first: summary, confirmed blockers, plausible risks, refuted
   concerns, files involved, merge recommendation.
4. Valuable but risky → separate integration branch, fix blockers there, build, device-test, then
   merge only after the owner approves.
5. Never merge to `main` without explicit approval. Never push. Never commit unless asked.

## Git rules

- Never push, pull, commit or tag unless the owner explicitly asks. He handles git himself.
- Always commit as the identity already set in this repo's `.git/config`. Never set it globally and
  never substitute another name.
- **Never** add Claude/AI co-author trailers, attribution, or any mention of AI in commit messages,
  notes or git metadata.
- If asked for a commit message, give a clear subject and a useful body. Do not volunteer one
  between phases — the owner asks when he wants it.
- Do not modify branches or remotes unless asked.

## Documentation update rules

Update only what the work actually changed, and never invent anything:

- **`CHANGELOG.md`** — for any change to behaviour or shipped content, under a new
  `core-<version>` heading, alongside the `extra["coreVersion"]` bump.
- **`README.md`** — if public-facing usage, setup, the module description or the locale tables
  changed. The locale tables are **generated**: run `gen_supported_locales.py`, do not hand-edit.
- **`CLAUDE.md`** (this file) — if a rule, path, command or repo boundary changed. Adding the
  mobile repo is one of these.
- Never include secrets, API keys, credentials, playlist URLs, or private user data.

## Session end

Only when the owner explicitly says the session is ending. Never ask about it yourself.

1. Ask before running the full `./gradlew build` gate if it has not run, and wait. The other build
   commands are pre-approved; this is the slow one.
2. Update the docs above that the work actually changed. Do **not** touch `AGENT_HANDOVER.md` yet.
3. Provide a commit note — clear subject, useful body, no AI attribution, no co-author trailers.
4. The owner commits and pushes, and gives back the commit hash.
5. **Then** write `AGENT_HANDOVER.md`, so it names the real commit: session date, git state, goal,
   completed work, files changed and why, behaviour after the changes, verification performed and
   whether it passed, remaining TODO/risks, and where the next agent should look first.

## Communication style

Every answer and every finished piece of work ends with a short plain-language explanation — no
class names, no method names, no engine internals in that part. Technical detail goes above it.
Keep it to a few sentences, in everyday words, saying what changed for the *user of the app*.

```
**Simple explanation**
<what is happening, in plain words>

**Example**
<a tiny concrete example, only if it helps>

**What this means for you**
<the practical takeaway>
```
