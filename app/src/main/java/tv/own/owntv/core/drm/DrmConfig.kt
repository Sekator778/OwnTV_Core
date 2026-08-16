package tv.own.owntv.core.drm

import org.json.JSONObject
import tv.own.owntv.core.network.StreamHeaders

/**
 * The DRM a stream needs, as an M3U playlist declares it (#115).
 *
 * Kodi's `#KODIPROP:inputstream.adaptive.*` convention is what playlists in the wild use, and it is
 * how self-hosted proxies (JioTV-Go and friends) publish protected channels:
 *
 * ```
 * #KODIPROP:inputstream.adaptive.manifest_type=mpd
 * #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
 * #KODIPROP:inputstream.adaptive.license_key=https://host/live/key/144
 * https://host/live/mpd/143
 * ```
 *
 * Stored on [tv.own.owntv.core.database.entity.ChannelEntity.drmConfig] (and the movie/episode twins)
 * as a small JSON object — unlike [StreamHeaders] this is structured rather than a header list, and it
 * has to survive round-tripping a URL that may itself contain colons and newline-hostile characters.
 *
 * **Nothing here costs a licence.** On Android the Widevine CDM is part of the OS; the app only has to
 * tell ExoPlayer which scheme to use and where the licence server is. What the *device* provides is a
 * security level (L1 hardware / L3 software), which decides whether the provider will serve HD.
 */
data class DrmConfig(
    val scheme: Scheme,
    /** Licence server URL. Always an `http(s)` URL — see [fromKodiProps]. */
    val licenseUrl: String,
    /** Extra headers for the LICENCE request, which is a separate call from the manifest's. */
    val headers: Map<String, String> = emptyMap(),
) {
    enum class Scheme(val key: String) {
        WIDEVINE("widevine"),
        CLEARKEY("clearkey"),
    }

    companion object {

        /** JSON object → string, or null when there is nothing to store. */
        fun encode(config: DrmConfig?): String? {
            val c = config ?: return null
            return JSONObject().apply {
                put(KEY_SCHEME, c.scheme.key)
                put(KEY_LICENSE, c.licenseUrl)
                if (c.headers.isNotEmpty()) put(KEY_HEADERS, JSONObject(c.headers))
            }.toString()
        }

        /**
         * Stored string → config. Total: a malformed or unknown-scheme blob reads as "no DRM" rather
         * than throwing, because a row we cannot interpret must degrade to the pre-DRM behaviour, not
         * break the whole playlist load.
         */
        fun decode(serialized: String?): DrmConfig? {
            val text = serialized?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                val o = JSONObject(text)
                val scheme = Scheme.entries.firstOrNull { it.key == o.optString(KEY_SCHEME) } ?: return null
                val license = o.optString(KEY_LICENSE).takeIf { it.isNotBlank() } ?: return null
                val headers = o.optJSONObject(KEY_HEADERS)?.let { h ->
                    val out = LinkedHashMap<String, String>(4)
                    h.keys().forEach { k ->
                        val name = StreamHeaders.canonicalName(k) ?: return@forEach
                        h.optString(k).takeIf { it.isNotBlank() }?.let { out[name] = it }
                    }
                    out
                } ?: emptyMap()
                DrmConfig(scheme, license, headers)
            }.getOrNull()
        }

        /**
         * The `inputstream.adaptive.*` properties of ONE playlist entry → a config, or null when the
         * entry declares no DRM or declares one we cannot honour. Keys arrive lowercased.
         *
         * Deliberately conservative — a config we cannot fully honour is dropped rather than stored
         * half-right, because a broken licence request fails in a way the user cannot diagnose, while
         * a missing config fails exactly as it does today:
         *
         *  - Only Widevine and ClearKey. PlayReady is untestable here and nobody has asked.
         *  - Only an `http(s)` licence URL. Kodi also allows an inline ClearKey key (`kid:key` or a
         *    JWK blob), which needs a local licence callback rather than a licence server — a
         *    different mechanism, not a variation of this one.
         *  - Only the plain passthrough request/response templates. Kodi's `license_key` may carry
         *    `url|headers|postDataTemplate|responseTemplate`; anything but an empty or `R{SSM}`/`B`
         *    template means the portal expects a request body we do not build.
         */
        fun fromKodiProps(props: Map<String, String>): DrmConfig? {
            val type = props[PROP_LICENSE_TYPE]?.lowercase().orEmpty()
            val scheme = when {
                type.contains("widevine") -> Scheme.WIDEVINE
                type.contains("clearkey") -> Scheme.CLEARKEY
                else -> return null
            }
            val raw = props[PROP_LICENSE_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // `|` splits the Kodi licence spec, but an unsplit value is just the URL.
            val parts = raw.split('|')
            val url = parts[0].trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return null
            }
            if (parts.size > 2 && parts.drop(2).any { !isPassthroughTemplate(it) }) return null
            val headers = parts.getOrNull(1)?.let { parseLicenseHeaders(it) } ?: emptyMap()
            return DrmConfig(scheme, url, headers)
        }

        /** `Key=Value&Key=Value`, percent-encoded — the same encoding as `…stream_headers`. */
        private fun parseLicenseHeaders(raw: String): Map<String, String> {
            val text = raw.trim()
            if (text.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>(4)
            text.split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val name = StreamHeaders.canonicalName(pair.substring(0, eq)) ?: return@forEach
                val value = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                    .getOrDefault(pair.substring(eq + 1))
                    .trim()
                if (value.isNotEmpty()) out[name] = value
            }
            return out
        }

        /** An empty template, or one of the two "send it unchanged" markers Kodi documents. */
        private fun isPassthroughTemplate(template: String): Boolean {
            val t = template.trim()
            return t.isEmpty() || t.equals("R{SSM}", ignoreCase = true) || t.equals("B", ignoreCase = true) ||
                t.equals("R", ignoreCase = true) || t.equals("b{SSM}", ignoreCase = true)
        }

        /** True for a KODIPROP key this class consumes — the parser only accumulates these. */
        fun isDrmProp(key: String): Boolean = key in DRM_PROPS

        const val PROP_LICENSE_TYPE = "license_type"
        const val PROP_LICENSE_KEY = "license_key"

        private val DRM_PROPS = setOf(PROP_LICENSE_TYPE, PROP_LICENSE_KEY)

        private const val KEY_SCHEME = "scheme"
        private const val KEY_LICENSE = "license"
        private const val KEY_HEADERS = "headers"
    }
}

/**
 * The Media3 licence configuration for this item. `DefaultMediaSourceFactory` reads it off the
 * `MediaItem` and builds the `DrmSessionManager` itself — the CDM comes from the OS, so there is
 * nothing to register and nothing to license.
 *
 * [multiSession] must be true for live: a live stream rotates its content key, and a single session
 * plays for a few minutes and then dies. It stays false for a film, whose key never changes.
 */
fun DrmConfig.toMediaDrmConfiguration(multiSession: Boolean): androidx.media3.common.MediaItem.DrmConfiguration {
    val uuid = when (scheme) {
        DrmConfig.Scheme.WIDEVINE -> androidx.media3.common.C.WIDEVINE_UUID
        DrmConfig.Scheme.CLEARKEY -> androidx.media3.common.C.CLEARKEY_UUID
    }
    return androidx.media3.common.MediaItem.DrmConfiguration.Builder(uuid)
        .setLicenseUri(licenseUrl)
        .setLicenseRequestHeaders(headers)
        .setMultiSession(multiSession)
        .build()
}
