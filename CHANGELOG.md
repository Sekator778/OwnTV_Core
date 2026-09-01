# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version number never lines up with an OwnTV TV
app `v4.x` release, and the two must not be confused. Tags here are prefixed `core-`.

## core-1.0.7 — 2026-09-01

### 🎨 The colour values live here now

- **New `core/theme/Palette.kt`** — the accent presets, the neutral ladders and the custom-accent
  derivation (`parseAccentHex`, `accentRolesFromSeed`) moved out of the TV app, so both apps read one
  set of hex codes instead of drifting copies. They are plain ARGB longs, not Compose `Color`: core
  carries the Compose runtime only and must not gain `compose-ui`, so consumers wrap them at the
  edge. The TV app does exactly that, with every public symbol and every rendered value unchanged.

### 🧭 The main-menu sections live here now

- **New `core/nav/MainSection.kt`** — the sections a user can navigate to, and `dynamicVisible()`,
  the rule that hides a section when no source has that kind of content.
- **New `core/nav/NavVisibility.kt`, registered in `DataModule`** — the whole computation, not just
  the rule: the static hidden-sections setting, the content-capability flow over the channel, movie
  and series counts, and the combination of the two. A consumer asks for a set of visible sections
  rather than assembling one. This deleted a second, independent copy of the capability flow that had
  grown inside the TV app's settings screen; both call sites now go through the one implementation.
- The TV app is rebuilt on it with no behaviour change — same flows, same defaults, same
  `distinctUntilChanged` — and its tests and release build are green.

### 🌍 Strings

- **Three new strings, translated into all 24 packaged locales:** `common_nav_library` and
  `common_nav_more` for the mobile app's bottom bar, and `common_cast` for its cast button.
  `content_media_cast` was deliberately not reused — it means the cast of a film.

## core-1.0.6 — 2026-09-01

### 📱 A non-TV app can consume core

Building the mobile app's harness against core surfaced four things that only ever worked because the
TV app was the only caller. All four are additive — the TV app's behaviour is unchanged, its release
build and core's unit tests are green, and it has been device-tested.

- **`player-core` exposes libmpv as `api`, not `implementation`.** `OwnTVPlayer`'s supertype is
  `MPVLib.EventObserver`, so a consumer could not compile against the published artifact without
  libmpv on its compile classpath. The TV app never noticed because it declares libmpv itself. Both
  apps are now pinned to one libmpv version, which is what we want anyway.
- **New `CoreBuildInfo.tvHome`, defaulting to `true`, gates `SettingsRepository.androidTvHomeEnabled`.**
  Core does no TV detection at all, so on a phone the sync worker published Watch Next entries to a
  content provider that is not there — silent only because the call site wraps it in `runCatching`.
  This is a host fact, not a device check: the question is whether the app belongs on a TV home
  screen, not whether the hardware is a TV. Every publish path and both TV-app readers already go
  through that one flow.
- **`SourceRepository.sync()` takes `onProgress` last.** Kotlin binds a trailing lambda to the final
  parameter, so `sync(source) { … }` aimed the progress callback at `forcePrune` and failed with
  "'Boolean' was expected". All four existing callers already passed it by name, so nothing moved.

### 🤖 Release plumbing

- **`ahXN00/OwnTV_Mobile` joins the pin-bump consumer matrix**, so it gets the same "Pin core x.y.z"
  pull request the TV app gets on every release. It is private until the app's first release, so
  `CONSUMER_BUMP_TOKEN` must grant access to it explicitly.

## core-1.0.5 — 2026-08-31

### 🧪 A playlist can be tested

- **New `SourceTester`**, a read-only probe that answers "is this playlist usable?" for all three
  source types and returns one of `Ok` / `AuthFailed` / `Expired` / `Unreachable`. Xtream reads the
  account API, M3U fetches the first kilobyte and checks it really starts with `#EXTM3U`, Stalker
  performs a portal handshake. Nothing is written to the database, so it is safe to run against a
  playlist that has not been saved yet.
- **`XtreamClient.XtAccountDetails` now also carries `activeConnections`, `status`, `authOk` and
  `trial`**, each parsed whether the panel sends it as a number or as a string. `active_cons` is the
  figure behind "2 of 3 connections in use"; `status` is passed through verbatim because panels invent
  their own words for it.
- **New `fetchAccountStatus()` throws where `fetchAccountDetails()` returns null.** A test has to tell
  "the host never answered" apart from "the host said no", and a null cannot carry that difference.
  `fetchAccountDetails()` is now a thin non-throwing wrapper around it, so existing callers are
  unchanged.
- Ten new strings for the result popup, in the base locale and all 24 translations.

### 🔄 Playlist auto-refresh takes a custom number of days

- **The fixed 24-hour, 48-hour and 7-day intervals are replaced by a single Manual mode carrying a day
  count from 1 to 99.** `PlaylistAutoRefresh` keeps `OFF`, `STARTUP`, `HOURS_6` and `HOURS_12` and gains
  `MANUAL`; the new `PlaylistRefresh` value type pairs a mode with `manualDays`.
- **Existing choices are translated on read, not migrated.** `PlaylistRefresh.parse()` maps the stored
  `HOURS_24`, `HOURS_48` and `DAYS_7` names to 1, 2 and 7 days, so nothing has to be rewritten in
  settings storage and a backup taken on an older build keeps working forever.
- **The stored form is unchanged in shape** — `MODE` or `MODE:days`, e.g. `MANUAL:14` — so backup
  export/import and the companion payload need no new field.
- **The companion web form** offers 1, 2, 7, 14 and 30-day presets in place of the old 24h/48h entries;
  the exact figure is dialled in on the television.
- New `settings_sources_refresh_manual`, a `settings_sources_refresh_days` plural with the correct CLDR
  quantities per language, and the day-picker title and hint — base locale plus all 24 translations. The
  two Stalker-only inline test strings are removed, replaced by the shared result popup.

## core-1.0.4 — 2026-08-30

**No library changes.** Same code as `core-1.0.3`; documentation only.

- **The README now describes the release pipeline**, and carries a status badge for it. The version
  in the "consuming core from an app" snippet was still showing `1.0.1`.

## core-1.0.3 — 2026-08-30

**No library changes.** Same code as `core-1.0.2`; this version exists to exercise the new release
pipeline end to end.

- **Every core version now gets a GitHub Release**, with its notes taken from this file. Previously
  a version existed only as a tag and a package, which was hard to read and impossible to link to.
- **The release is what tells the apps to move.** The publish workflow runs the tests, pushes both
  artifacts to GitHub Packages, and only then publishes the release — so a release can exist only
  for a version that actually built and shipped, and it is the release that opens the pin-bump pull
  request on each app. A tag whose tests fail now stops there.

## core-1.0.2 — 2026-08-30

- **Hungarian is now a fully translated, packaged language.** All 2132 strings across the six
  resource files are translated, and Hungarian is selectable in the app's language picker.

## core-1.0.1 — 2026-08-29

- The About screen's copyright line now reads **© 2026 OwnTV** instead of naming the author.
  Updated in the base locale and all 23 translations.

## core-1.0.0 — 2026-08-29

First release as a standalone library. No behaviour changed: this is the same code the OwnTV TV app
shipped in its `:core` and `:player-core` modules, extracted into its own repository with its
history intact.

- **`:core`** — Room database and 33 shipped schemas (v2–35), playlist sync and parsing for M3U /
  Xtream / Stalker, EPG, backup and restore, profiles, downloads, settings storage, launcher
  integration, and all 149 string resource files across 24 packaged locales.
- **`:player-core`** — the playback engine: libmpv, the Media3/ExoPlayer handoff, the fallback
  ladder, watchdogs and stream diagnostics.
- Both modules build and test standalone, with no app in the build graph — 309 unit tests in
  `:core`, 118 in `:player-core`.
- Published as `tv.own.owntv:core` and `tv.own.owntv:player-core`, always on the same version.
- The i18n toolkit and its four validators moved here with the strings.
