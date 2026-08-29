package tv.own.owntv.core.theme

/**
 * Material You-style accent presets. OwnTV can't rely on true wallpaper-based dynamic color (a phone
 * feature that isn't dependable on Android TV), so instead the user picks an accent and the M3 color
 * scheme is seeded from it.
 *
 * This is only the user's *choice* — the name is what gets persisted. The tonal `primary` /
 * `primaryContainer` roles each preset seeds, and its display label, are rendering concerns and live
 * with the theme in the app module.
 */
enum class AccentColor { TEAL, BLUE, VIOLET, GREEN, AMBER }
