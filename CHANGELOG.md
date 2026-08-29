# OwnTV Core — Changelog

Core is versioned independently of the apps. A core version number never lines up with an OwnTV TV
app `v4.x` release, and the two must not be confused. Tags here are prefixed `core-`.

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
