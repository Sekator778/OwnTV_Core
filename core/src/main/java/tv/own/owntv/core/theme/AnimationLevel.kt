package tv.own.owntv.core.theme

/**
 * How much UI motion to render. A performance/comfort control: lower-end Android TV boxes can feel
 * laggy when moving quickly between menus, so the user can tone the animations down (or off).
 *
 * The display label for each level is a rendering concern and lives with the theme in the app module.
 */
enum class AnimationLevel {
    // On = normal motion; Off = instant (no transitions). The fixed grid (v4.0.0) removed the old reason for
    // a middle "Reduced" tier, so this is now a simple On/Off reduce-motion toggle. (Legacy "REDUCED" values
    // fall back to On via the settings store's safe parse.)
    FULL, OFF;

    /** Scale an animation duration to this level (OFF collapses to 0 → an instant snap). */
    fun scale(durationMs: Int): Int = when (this) {
        FULL -> durationMs
        OFF -> 0
    }
}
