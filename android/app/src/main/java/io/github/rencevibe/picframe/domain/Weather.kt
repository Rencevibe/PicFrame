package io.github.rencevibe.picframe.domain

import java.net.URLEncoder
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Open-Meteo weather (port of frame/weather.py): the frame's only network call. The HTTP call
 *  itself is injected as [WeatherService.fetch]; the data layer supplies it (M4). */
const val WEATHER_API = "https://api.open-meteo.com/v1/forecast"
const val WEATHER_TIMEOUT_S = 10
const val WEATHER_REFRESH_S = 30 * 60.0
const val WEATHER_STALE_S = 3 * 60 * 60.0

data class Weather(
    val temp: Int,
    val code: Int,
    val icon: String,
    val label: String,
    val high: Int,
    val low: Int,
    val fetched: ZonedDateTime,
)

/** (first code, last code, icon, label): WMO weather interpretation codes. */
private data class Code(val first: Int, val last: Int, val icon: String, val label: String)

private val CODES = listOf(
    Code(0, 0, "clear", "Clear"),
    Code(1, 1, "partly-cloudy", "Mostly clear"),
    Code(2, 2, "partly-cloudy", "Partly cloudy"),
    Code(3, 3, "cloudy", "Cloudy"),
    Code(45, 45, "fog", "Fog"),
    Code(48, 48, "fog", "Freezing fog"),
    Code(51, 55, "rain", "Drizzle"),
    Code(56, 57, "rain", "Freezing drizzle"),
    Code(61, 65, "rain", "Rain"),
    Code(66, 67, "rain", "Freezing rain"),
    Code(71, 77, "snow", "Snow"),
    Code(80, 82, "rain", "Showers"),
    Code(85, 86, "snow", "Snow showers"),
    Code(95, 99, "storm", "Thunderstorm"),
)

private fun lookup(code: Int): Pair<String, String> =
    CODES.firstOrNull { code in it.first..it.last }?.let { it.icon to it.label } ?: ("unknown" to "Unknown")

fun iconFor(code: Int): String = lookup(code).first

/** 63.5 → 64 and 64.5 → 65 (half up, as the Pi version does). */
private fun roundHalfUp(x: Double): Int = Math.floor(x + 0.5).toInt()

fun buildWeatherUrl(lat: Double, lon: Double, units: String): String {
    val params = listOf(
        "latitude" to lat.toString(),
        "longitude" to lon.toString(),
        "current" to "temperature_2m,weather_code",
        "daily" to "temperature_2m_max,temperature_2m_min",
        "temperature_unit" to units,
        "timezone" to "auto",
        "forecast_days" to "1",
    )
    return WEATHER_API + "?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}

private fun number(v: Any?): Double = (v as? Number)?.toDouble() ?: throw IllegalArgumentException("not a number: $v")

private fun obj(v: Any?): Map<*, *> = v as? Map<*, *> ?: throw IllegalArgumentException("not an object")

/** Parses a /v1/forecast response body. Throws IllegalArgumentException on a malformed response. */
fun parseWeather(json: String, fetched: ZonedDateTime): Weather {
    val root = obj(MiniJson.parse(json))
    val current = obj(root["current"])
    val daily = obj(root["daily"])
    val code = number(current["weather_code"]).toInt()
    val (icon, label) = lookup(code)
    val highs = daily["temperature_2m_max"] as? List<*> ?: throw IllegalArgumentException("no daily max")
    val lows = daily["temperature_2m_min"] as? List<*> ?: throw IllegalArgumentException("no daily min")
    if (highs.isEmpty() || lows.isEmpty()) throw IllegalArgumentException("empty daily values")
    return Weather(
        temp = roundHalfUp(number(current["temperature_2m"])),
        code = code,
        icon = icon,
        label = label,
        high = roundHalfUp(number(highs[0])),
        low = roundHalfUp(number(lows[0])),
        fetched = fetched,
    )
}

private fun threadSpawn(job: () -> Unit) {
    Thread(job, "weather").apply { isDaemon = true }.start()
}

/**
 * Refreshes at most every 30 min of monotonic time (so clock changes can't stall or bunch
 * refreshes), on a background job, keeping the last good result.
 *
 * @param fetch HTTP GET returning the body; called with the URL and a timeout in seconds.
 * @param spawn runs a job in the background.
 * @param clock monotonic seconds.
 */
class WeatherService(
    private val cfg: Config,
    private val fetch: (url: String, timeoutS: Int) -> String,
    var spawn: (() -> Unit) -> Unit = ::threadSpawn,
    private val clock: () -> Double,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var last: Weather? = null
    @Volatile private var lastAttempt: Double? = null
    private val busy = AtomicBoolean(false)

    internal val isBusy: Boolean get() = busy.get()

    /** Starts a background fetch if 30 min have passed since the last attempt. Safe to call every
     *  frame; never blocks the caller. */
    fun update(now: ZonedDateTime) {
        val lat = cfg.lat
        val lon = cfg.lon
        if (lat == null || lon == null || busy.get()) return
        val previous = lastAttempt
        if (previous != null && clock() - previous < WEATHER_REFRESH_S) return
        lastAttempt = clock()
        busy.set(true)
        val url = buildWeatherUrl(lat, lon, cfg.units)
        val job: () -> Unit = {
            try {
                last = parseWeather(fetch(url, WEATHER_TIMEOUT_S), now)
            } catch (e: Exception) { // network, HTTP, JSON or shape errors: keep the last good result
                log("weather: update failed ($e); keeping last result")
            } finally {
                busy.set(false)
            }
        }
        try {
            spawn(job)
        } catch (e: Exception) { // e.g. can't start a thread: retry at the next interval
            busy.set(false)
            log("weather: could not start update ($e)")
        }
    }

    fun current(now: ZonedDateTime): Weather? {
        val w = last ?: return null
        return if (epochS(now) - epochS(w.fetched) > WEATHER_STALE_S) null else w
    }
}
