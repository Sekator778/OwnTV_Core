package tv.own.owntv.core.metadata

import org.json.JSONArray
import org.json.JSONObject

/** One credited actor: the name shown, plus the TMDB profile photo path (null when TMDB has no photo). */
data class CastMember(val name: String, val profilePath: String? = null)

/**
 * Serialization for the cached `castJson` column.
 *
 * The column used to hold a plain array of names, `["Tom Hanks","Robin Wright"]`. It now holds objects
 * carrying the profile photo path too, `[{"n":"Tom Hanks","p":"/abc.jpg"}]`.
 *
 * **This is a content change, not a schema change** — `castJson` is already a TEXT column, so there is
 * no Room migration and no database version bump.
 *
 * [parse] deliberately reads BOTH shapes. Existing installs have name-only rows cached for up to the
 * positive TTL, and without the old-format branch every one of those users would see an empty cast list
 * until their cache expired. Old rows simply come back with no photo.
 *
 * Capturing the photo path costs no extra network call: `credits` is already part of the
 * `append_to_response` on the details request, so the profile path is in a payload the app already
 * downloads — it was previously parsed and thrown away.
 */
object MetadataCast {

    private const val KEY_NAME = "n"
    private const val KEY_PROFILE = "p"

    fun parse(json: String?): List<CastMember> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<CastMember>(arr.length())
            for (i in 0 until arr.length()) {
                // New format: an object. Old format: a bare name string.
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val name = obj.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: continue
                    val path = obj.optString(KEY_PROFILE).takeIf { it.isNotBlank() && it != "null" }
                    out += CastMember(name, path)
                } else {
                    val name = arr.optString(i).takeIf { it.isNotBlank() } ?: continue
                    out += CastMember(name)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun serialize(cast: List<CastMember>): String = JSONArray().apply {
        cast.forEach { member ->
            put(
                JSONObject()
                    .put(KEY_NAME, member.name)
                    .apply { member.profilePath?.let { put(KEY_PROFILE, it) } },
            )
        }
    }.toString()

    /**
     * True when [json] is a cast list written by the OLD name-only format.
     *
     * Those rows can never show photos, and with the long positive TTL they would sit in the cache for
     * months, so the repository treats them as stale and re-fetches that title the next time it is
     * opened. Lazy on purpose: it spreads the refresh across normal browsing instead of firing a
     * download wave the moment the app updates.
     *
     * An empty or absent list is NOT legacy — there is simply no cast, and re-fetching forever would
     * be a loop.
     */
    fun isLegacyFormat(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching {
            val arr = JSONArray(json)
            if (arr.length() == 0) return false
            // New format is an array of objects; old format an array of bare strings.
            arr.optJSONObject(0) == null
        }.getOrDefault(false)
    }

    /** Just the names, for the compact one-line cast rows in the browse side panes. */
    fun names(json: String?): List<String> = parse(json).map { it.name }
}
