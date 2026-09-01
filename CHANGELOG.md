# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version number never lines up with an OwnTV TV
app `v4.x` release, and the two must not be confused. Tags here are prefixed `core-`.

## core-1.0.5 — unreleased

### ⚡ EPG auto-match finishes on TV hardware

- **`EpgMatcher.bestEpgMatchBulk` scans a whole catalogue across all cores**, mirroring the existing
  `rankForPickerParallel`. Auto-match grows as channels × candidates — 1,786 channels against a
  1,907-channel guide is ~3.4M scorings — and the single-threaded loop ran for over half an hour of
  CPU time on a 2020 Android TV without finishing, leaving the guide behind its "channel ids don't
  match" banner the whole time. Rows are independent, so results and their order are unchanged.
- **`Prepared` now carries precomputed digit runs**, and `bestEpgMatchPrepared` computes the
  target's once per channel instead of once per comparison. The digit-mismatch guard re-ran the same
  regex on both sides of every pair, which dominated the scan's allocation. This speeds up the
  single-threaded path too, so the picker benefits without any caller change.
- Measured on a 1,786 × 1,907 catalogue: sequential 3,336 ms → 1,570 ms from the precomputation
  alone, and 373 ms with the parallel scan — about 9× end to end, with identical results.

### 🌍 EPG auto-match works outside the Latin alphabet

- **`EpgMatcher.normalizeForEpg` no longer throws away non-Latin names.** Its cleanup class was
  `[^a-z0-9 ]`, so a Cyrillic, Greek or CJK channel name reduced to an empty string, and
  `bestEpgMatch` returns null on an empty target — auto-match could never pair those channels with a
  guide entry, leaving the guide stuck behind "channel ids don't match your channels' EPG ids". The
  class now keeps letters and digits of any script.
- **Names are NFKC-normalised first**, so decorative compatibility spellings still fold away: `ᴴᴰ`
  becomes `HD` and is dropped as noise, and halfwidth katakana returns to its normal form. Composing
  rather than decomposing matters — NFKD would leave combining marks that the cleanup class turns
  into spaces, splitting `Чайка` into two tokens and degrading `ﾊﾟ` to `ハ`.
- **The channel-number guard reads digits of any script.** `DIGIT_RUN` was `\d+`, which is ASCII-only
  in Java, so once non-Latin digits survived normalisation `قناة ٢` and `قناة ٣` scored high enough to
  auto-apply onto each other. It now matches `\p{N}+` and compares digits by numeric value, so `MTV ٢`
  and `MTV 2` are recognised as the same channel while `٢` and `٣` stay apart.

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
