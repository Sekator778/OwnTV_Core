package tv.own.owntv.core.theme

import androidx.compose.runtime.Stable

/**
 * Glass effect — the persisted shape of the user's translucent-surface settings.
 *
 * Each surface that can go glassy is tagged with a [GlassSurface]. When the feature is enabled
 * a panel whose surface is in [GlassConfig.scope] renders with a translucent fill and directional
 * edge light. The rendering itself lives with the theme in the app module.
 */
enum class GlassSurface {
    /** The large rounded content panels (Home/Movies/Series/Live/etc. root containers). */
    PANELS,

    /** The left navigation rail (sidebar). */
    SIDEBAR,

    /** The right detail column (preview pane / channel detail). */
    PREVIEW,

    /** Centered popup dialogs and sheets (accent picker, settings sheets, chooser dialogs). */
    DIALOGS,

    /** The top bar: active section + search pill + clock + playlist chip. */
    TOPBAR,

    /** Poster cards and list items. */
    CARDS,

    /** The docked (non-fullscreen) mini-player bar. */
    MINI_PLAYER,
}

/** User-facing material tuning. CUSTOM resolves to the separately persisted alpha/frost values. */
enum class GlassPreset(val alpha: Float?, val blurStrength: Float?) {
    ULTRA_CLEAR(alpha = 0.24f, blurStrength = 0.35f),
    CLEAR(alpha = 0.38f, blurStrength = 0.62f),
    BALANCED(alpha = 0.56f, blurStrength = 0.78f),
    TINTED(alpha = 0.74f, blurStrength = 0.88f),
    OPAQUE(alpha = 0.92f, blurStrength = 1.00f),
    CUSTOM(alpha = null, blurStrength = null);

    fun resolveAlpha(custom: Float): Float = (alpha ?: custom).coerceIn(0f, 1f)
    fun resolveBlur(custom: Float): Float = (blurStrength ?: custom).coerceIn(0f, 1f)

    companion object {
        /** Migration-safe: recognize old defaults/preset values; preserve every other old value as Custom. */
        fun fromStored(name: String?, customAlpha: Float, customBlur: Float): GlassPreset {
            name?.let { stored -> entries.firstOrNull { it.name == stored }?.let { return it } }
            return entries.firstOrNull {
                it != CUSTOM && kotlin.math.abs((it.alpha ?: 0f) - customAlpha) < 0.001f &&
                    kotlin.math.abs((it.blurStrength ?: 0f) - customBlur) < 0.001f
            } ?: CUSTOM
        }
    }
}

/**
 * Resolved glass state.
 *
 * @param scope which surfaces are glassy. Empty = feature off (panels stay solid).
 * @param alpha panel fill alpha when glassed, in 0..1. Default 0.56 (Balanced).
 *   Pure 0 means fully transparent (image shows through unobstructed), 1 = opaque (no glass effect).
 * @param blurStrength how much real backdrop blur ("frost") to apply, in 0..1. 0 = Tier-1
 *   translucency only (sharp background reads through); 1 = fully frosted. Default 0.78. Only has an
 *   effect on API 31+ ([supportsBackdropBlur]) and when a background image is present. The strength
 *   is the draw alpha of the single shared blurred-backdrop slice — O(1) to change, no re-blur.
 */
@Stable
data class GlassConfig(
    val scope: Set<GlassSurface> = emptySet(),
    val alpha: Float = DEFAULT_GLASS_ALPHA,
    val blurStrength: Float = DEFAULT_BLUR_STRENGTH,
    val preset: GlassPreset = GlassPreset.BALANCED,
    /** User light control; 0.55 is the compatibility baseline and therefore renders at 1x. */
    val highlightStrength: Float = DEFAULT_HIGHLIGHT_STRENGTH,
    /** Escape hatch: false keeps the adaptive text-legibility floor enabled. */
    val allowFullTransparency: Boolean = false,
    /** Enables focus-travel light, parallax and depth transforms. */
    val depthEffects: Boolean = true,
    /** Runtime-only environment flag supplied by MainActivity; it is not persisted in the bitmask. */
    val hasBackdrop: Boolean = false,
) {
    /** Glass is "on" only when at least one surface is scoped. */
    val enabled: Boolean get() = scope.isNotEmpty()

    /** True when [surface] should render as glass. */
    fun isGlassy(surface: GlassSurface): Boolean = enabled && surface in scope

    /** Bitmask encode/decode — used by SettingsRepository persistence. */
    fun toBitmask(): Int {
        var bits = 0
        for (s in scope) bits = bits or (1 shl s.ordinal)
        return bits
    }

    companion object {
        const val DEFAULT_GLASS_ALPHA: Float = 0.56f
        const val DEFAULT_BLUR_STRENGTH: Float = 0.78f
        const val DEFAULT_HIGHLIGHT_STRENGTH: Float = 0.55f

        fun fromBitmask(
            bits: Int,
            alpha: Float = DEFAULT_GLASS_ALPHA,
            blurStrength: Float = DEFAULT_BLUR_STRENGTH,
            preset: GlassPreset = GlassPreset.CUSTOM,
            highlightStrength: Float = DEFAULT_HIGHLIGHT_STRENGTH,
            allowFullTransparency: Boolean = false,
            depthEffects: Boolean = true,
        ): GlassConfig {
            val scope = GlassSurface.entries.filter { (bits shr it.ordinal) and 1 == 1 }.toSet()
            return GlassConfig(
                scope = scope,
                alpha = preset.resolveAlpha(alpha),
                blurStrength = preset.resolveBlur(blurStrength),
                preset = preset,
                highlightStrength = highlightStrength.coerceIn(0f, 1f),
                allowFullTransparency = allowFullTransparency,
                depthEffects = depthEffects,
            )
        }
    }
}

