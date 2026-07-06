package tv.own.owntv.features.home

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow(
    val title: String,
    val settingsDesc: String,
    val implemented: Boolean = true,
) {
    HERO("Keep Watching", "The large preview row at the top"),
    RECENT_CHANNELS("Recently Watched TV", "Live channels you tuned recently"),
    FAVORITE_CHANNELS("Favourite Channels", "Your favourited live channels"),
    CONTINUE_MOVIES("Continue Watching Movies", "Movies with a saved position"),
    CONTINUE_SERIES("Continue Watching Series", "Episodes to resume or play next"),
    GUIDE_SLICE("On Now", "A mini TV Guide for your recent channels"),
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

data class HomeConfig(
    val order: List<HomeRow> = HomeRow.entries.toList(),
    val hidden: Set<HomeRow> = emptySet(),
    val heroIncludeLive: Boolean = true,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
) {
    val visibleOrder: List<HomeRow>
        get() = order.filter { it.implemented && it !in hidden }

    val settingsRows: List<HomeRow>
        get() = order.filter { it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = obj.optJSONArray("hidden")
                .toHomeRows()
                .toSet()
            return HomeConfig(
                order = mergeOrder(storedOrder),
                hidden = hidden,
                heroIncludeLive = readBool(obj, "heroLive", "heroIncludeLive", default = true),
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }
    }
}

fun mergeOrder(stored: List<HomeRow>): List<HomeRow> {
    val result = LinkedHashSet<HomeRow>()
    stored.forEach { result += it }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}
