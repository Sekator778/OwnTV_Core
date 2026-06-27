package tv.own.owntv.core.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.network.ConnectivityObserver
import java.util.concurrent.TimeUnit

/**
 * Phase 7 — fetches current weather via Open-Meteo (free, no API key) after resolving the device's
 * approximate location from a free IP geolocation service. Results are cached in-memory for 30 minutes
 * so we don't hammer the free APIs on every recomposition.
 */
class WeatherRepository(
    private val http: OkHttpClient,
    private val connectivity: ConnectivityObserver,
) {
    private var cached: WeatherInfo? = null
    private var cacheTime: Long = 0L

    companion object {
        private const val CACHE_MS = 30 * 60 * 1000L // 30 min
    }

    /** Returns cached weather if fresh, otherwise fetches fresh data. null = offline / unavailable. */
    suspend fun get(): WeatherInfo? {
        val now = System.currentTimeMillis()
        if (cached != null && (now - cacheTime) < CACHE_MS) return cached
        if (!connectivity.isOnlineNow()) return cached // return stale if offline
        return withContext(Dispatchers.IO) {
            runCatching { fetchFresh() }
                .onSuccess { cached = it; cacheTime = now }
                .getOrNull() ?: cached
        }
    }

    private fun fetchFresh(): WeatherInfo {
        // 1. Resolve location via free IP API
        val locReq = Request.Builder()
            .url("https://ipapi.co/json/")
            .header("User-Agent", "OwnTV/1.0")
            .build()
        val locJson = http.newCall(locReq).execute().use { it.body?.string() ?: return error("no loc") }
        val loc = JSONObject(locJson)
        val lat = loc.getDouble("latitude")
        val lon = loc.getDouble("longitude")
        val city = loc.optString("city", loc.optString("region", ""))

        // 2. Fetch current weather from Open-Meteo
        val weatherUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&timezone=auto"
        val weatherReq = Request.Builder().url(weatherUrl).build()
        val weatherJson = http.newCall(weatherReq).execute().use { it.body?.string() ?: return error("no weather") }
        val w = JSONObject(weatherJson).getJSONObject("current")
        val temp = w.getDouble("temperature_2m").toFloat()
        val code = w.getInt("weather_code")
        val isDay = w.optInt("is_day", 1) == 1

        return WeatherInfo(temperatureC = temp, city = city, weatherCode = code, isDay = isDay)
    }

    private fun error(msg: String): Nothing = throw IllegalStateException("Weather fetch failed: $msg")
}
