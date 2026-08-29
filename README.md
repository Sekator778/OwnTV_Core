# OwnTV Core

The shared engine behind the OwnTV apps. Everything that is not a screen lives here: the Room
database and its migrations, playlist sync and parsing for M3U / Xtream / Stalker, EPG, backup and
restore, profiles, downloads, settings storage, and the playback engine.

Two modules, published together under one version:

| Module | What it is |
|---|---|
| `:core` | Data, sync, parsers, Room, backup, EPG, and every user-visible string. |
| `:player-core` | The playback engine — libmpv plus the Media3/ExoPlayer handoff, the fallback ladder, watchdogs and stream diagnostics. Depends on `:core`. |

Neither module renders anything. They use the Compose **runtime** for state, but no `compose-ui`,
no `compose-foundation`, no `androidx.tv.*` and no navigation — that is what lets the same engine
back the Android TV app and the mobile app.

## Who depends on this

- **OwnTV for Android TV** — the shipping app.
- **OwnTV for mobile** — in progress.

A change here affects both. Rebuild the TV app against any core change before releasing it.

## Build

Requires JDK 21 and an Android SDK. Put the SDK path in `local.properties`:

```properties
sdk.dir=C:/Path/To/Android/Sdk
```

Then:

```bash
./gradlew :core:assembleRelease :player-core:assembleRelease   # build both AARs
./gradlew :core:testDebugUnitTest :player-core:testDebugUnitTest
./gradlew build                                                 # everything, including lint
```

Instrumentation tests need a device or emulator. They are never run against a real TV that holds
real data — installing a test APK wipes it.

```bash
./gradlew :core:assembleDebugAndroidTest        # compiles the test APK, installs nothing
```

## Translations

<!-- i18n-contribution:start -->
## Help translate OwnTV

If your language is already available, contribute interface translations across OwnTV's six Android resource components on [Hosted Weblate](https://hosted.weblate.org/projects/owntv/). If it is not listed, [open a language request ticket](https://github.com/ahXN00/OwnTV/issues/new?template=feature_request.yml&title=%5BLanguage%5D%20Add%20) first. A maintainer will review the request, register the locale, and prepare its base translation files on Hosted Weblate. Once the language appears on Hosted Weblate, you can start translating it there. See the [language contributor guide](tools/i18n/README.md) for identifiers, validation, and promotion policy.
<!-- i18n-contribution:end -->

Validators, all four of which must pass before any string change is finished:

```bash
python tools/i18n/validate_strings.py
python tools/i18n/check_hardcoded_strings.py verify --bootstrap
python tools/i18n/gen_supported_locales.py check
python tools/i18n/check_text_overflow.py
```

## Consuming core from an app

Core publishes as `tv.own.owntv:core` and `tv.own.owntv:player-core`, both on the same version.

```kotlin
implementation("tv.own.owntv:core:1.0.0")
implementation("tv.own.owntv:player-core:1.0.0")
```

### Local development against core's source

Set this in `~/.gradle/gradle.properties` — **never** in an app repo:

```properties
owntv.corePath=E:/MEGA/CODE/AI/OwnTV_Core
```

The app's `settings.gradle.kts` picks it up and includes this repo as a composite build, so a core
edit shows up in the next app build with no publish step. Leave the property unset and the app
resolves the published artifact instead, which is what CI does.

### Hooks an app must supply

Core deliberately knows nothing about the app hosting it. Four hooks must be assigned in the
application's `onCreate`, **before Koin starts** — `CrashRecorder` reads the version before the
container exists:

| Hook | Supplies |
|---|---|
| `CoreBuildInfo` | versionName, versionCode, edgeKey, devTools, debug, diagnosticBuild |
| `CrashRecorder.diagnostics` | the live diagnostics log |
| `LiveSessionLimit.report` | per-provider stream quirks |
| `SubtitleFontAssets.resourceOf` | the app's bundled subtitle fonts |

An app also needs `android.nonTransitiveRClass=false` in its `gradle.properties` if it references
core's strings as a bare `R.string.*`.

## Versioning

Core versions are independent of the TV app's `v4.x` releases and must never be confused with them.
Tags are prefixed: `core-1.0.0`. Both modules always ship on the same version — two artifacts that
must be used together should not have numbers that can disagree.

## License

See [LICENSE](LICENSE).
