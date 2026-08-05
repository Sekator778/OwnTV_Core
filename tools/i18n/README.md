# OwnTV translation and language contributor guide

`tools/i18n/locales.json` is OwnTV's authoritative language catalogue. It deliberately separates
three identifiers that look similar but serve different owners:

- `languageTag`: runtime BCP-47 (`he`, `id`, `sr`)
- `resourceQualifier` / `resourceDirectory`: Android (`iw` / `values-iw`, `in` / `values-in`,
  Cyrillic Serbian `sr` / `values-sr`)
- `weblateCode`: current Weblate language definition (`he`, `id`, `sr`)

Current Weblate language definitions are verified against
[WeblateOrg/language-data](https://github.com/WeblateOrg/language-data). In particular, default/Spain
Spanish is `es` (there is no `es_ES` definition), while Latin America is `es_419`.

<!-- canonical-weblate:start -->
**Canonical project overview:** <https://hosted.weblate.org/projects/owntv/>

The app, README, and checked-in QR asset all use this value from `community.json`; replace that single source and regenerate when the project route changes.
<!-- canonical-weblate:end -->

## Supported, selectable, and catalogue-only

English (`values/`, canonical en-US wording) is the complete source. The small `values-en-rGB`
regional spelling override is packaged but hidden and is not a community translation target.
Existing reviewed community translations are packaged and selectable.

The 19 additions (`bg`, `hr`, `et`, `fa`, `fi`, `el`, `he`, `hu`, `id`, `lv`, `lt`, `ms`, `ro`,
`sr`, `sk`, `sl`, `th`, `uk`, `vi`) are **catalogue-only**. They intentionally have no Android
resource directories or seed files, compute as 0%, are discoverable for contributor-created Hosted
Weblate languages, and are neither packaged nor shown in the app picker. Do not seed or
machine-translate them.

## Readiness and promotion

The sole threshold owner is `translationReadinessThresholdPercent` in `community.json`; it is exactly
70%. The generator emits the same named Kotlin constant. A community locale below 70% must remain
unpackaged and unselectable. At 70% or above it becomes *eligible* for explicit maintainer promotion;
coverage does not silently change release packaging.

Promotion is intentionally manual, not generated:

1. Let Weblate create/sync all six Android resource files and run validation.
2. Run the coverage report and confirm at least 70%.
3. In the locale's single `locales.json` entry, change `tier` to `1`, `packaged` to `true`, and
   `pickerVisible` to `true` in one reviewed change.
4. Regenerate `SupportedLocales.kt`, README content, and QR; validate resources and build the app.
5. Review English fallback on missing keys and perform script/RTL/plural/focus smoke tests.

Gradle reads only `packaged` qualifiers from the catalogue. Generated Kotlin applies the threshold
again to picker rows as defense in depth. Coverage is calculated once by Python from Android resource
keys; it is not reimplemented in Gradle or Kotlin. The picker shows a badge only for visible community
locales below 100%; 100% badges, source English, system default, and hidden entries show no badge.

## Six resource components

Hosted Weblate covers the six source files independently so contributors can work across the whole UI:

- `strings.xml`
- `strings_content.xml`
- `strings_player.xml`
- `strings_settings.xml`
- `strings_setup.xml`
- `strings_features.xml`

Use the project/language overview, not one component or preselected language. The canonical endpoint,
README link, app constant, and reproducible QR are generated from `tools/i18n/community.json`.

> **URL status:** the captain selected the loopback project overview above as the canonical value
> without independent production-route verification. It is intentionally marked `localTestOnly` in
> `community.json`; changing the route requires editing that one value and regenerating all artifacts.

## Contributor and review workflow

1. Open the project overview and choose any available language.
2. Translate the relevant strings across all six components. Preserve placeholders and review
   translator comments/context.
3. Use Weblate quality checks and peer review; maintainers review the synchronized GitHub PR.
4. Missing translated keys safely fall back to English. Present malformed, empty, stale, or
   placeholder-breaking values override fallback and fail validation.

Captain's intended production access policy is non-default: anonymous visitors may directly add or
update translations. Weblate normally limits anonymous users to suggestions. Enabling direct
anonymous writes increases spam/moderation work and makes quality checks, Weblate review, GitHub PR
review, audit history, and rollback especially important; it is an explicit production-admin choice,
not a repository secret or a reason to block code implementation. Never commit Hosted Weblate admin
credentials, API tokens, webhook secrets, or operational secrets.

## Validation

```sh
python3 tools/i18n/test_i18n_tools.py
python3 tools/i18n/validate_strings.py --report text
python3 tools/i18n/gen_supported_locales.py check
python3 tools/i18n/check_hardcoded_strings.py verify --bootstrap
python3 tools/i18n/check_number_locale.py
python3 tools/i18n/check_text_overflow.py
./gradlew :app:testStandardDebugUnitTest :app:processStandardDebugResources :app:lintStandardDebug
```

Never hand-edit generated `app/src/main/java/tv/own/owntv/core/i18n/SupportedLocales.kt`; run
`python3 tools/i18n/gen_supported_locales.py`. That command also reproducibly regenerates the checked-in
README QR with the existing ZXing core dependency and checks payload consistency.
