package io.github.rencevibe.picframe.domain

import java.time.LocalTime

/**
 * Settings, ported from frame/config.py. Mutable on purpose: the alarm, night schedule and
 * controller read it live, so unsaved Settings changes apply at once.
 *
 * Android differences (plan §3, decisions D3 and G4):
 * - `photo_dir` holds the picked folder's URI (null until one is picked); folder access is
 *   checked by the photo source, not here.
 * - `todo_path` is gone: todo.toml is read from the picked folder.
 * - `screen_power` and `brightness_cmd` are Pi-only; brightness is window brightness, always available.
 * - `alarm_sound` is new (D4): the chosen system alarm tone, or null for the device default.
 */
data class Config(
    var photoDir: String? = null,
    var intervalS: Int = 30,
    var fadeMs: Int = 1000,
    var lat: Double? = null,
    var lon: Double? = null,
    var units: String = "fahrenheit",
    var clock24h: Boolean = false,
    var weekStart: String = "sunday",
    var overlayCorner: String = "bottom-left",
    var nightEnabled: Boolean = true,
    var nightStart: LocalTime = LocalTime.of(23, 0),
    var nightEnd: LocalTime = LocalTime.of(6, 0),
    var wakeS: Int = 60,
    var alarmEnabled: Boolean = true,
    var alarmTime: LocalTime = LocalTime.of(6, 30),
    var alarmDays: Set<Int> = setOf(0, 1, 2, 3, 4), // Mon=0
    var snoozeMin: Int = 9,
    var cacheMb: Int = 500,
    var brightness: Int = 100,
    var alarmSound: String? = null, // content URI of a system alarm tone; null = device default (D4)
)

data class ConfigResult(val config: Config, val warnings: List<String>, val critical: Boolean)

val UNITS = listOf("fahrenheit", "celsius")
val WEEK_STARTS = listOf("sunday", "monday")
val CORNERS = listOf("top-left", "top-right", "bottom-left", "bottom-right")
val CRITICAL_KEYS = setOf("photo_dir", "alarm_enabled", "alarm_time", "alarm_days", "lat", "lon")

private class Field(
    val parse: (Any?) -> Any,
    val get: (Config) -> Any?,
    val set: (Config, Any) -> Unit,
)

private fun intIn(lo: Int, hi: Int): (Any?) -> Any = { v ->
    val n = when (v) {
        is Int -> v.toLong()
        is Long -> v
        else -> null
    }
    require(n != null && n in lo.toLong()..hi.toLong()) { "must be a whole number $lo–$hi" }
    n.toInt()
}

private val bool: (Any?) -> Any = { v -> require(v is Boolean) { "must be true or false" }; v }

private fun choice(options: List<String>): (Any?) -> Any = { v ->
    require(v is String && v in options) { "must be one of " + options.joinToString(", ") }
    v
}

private val path: (Any?) -> Any = { v ->
    require(v is String && v.isNotBlank() && '\u0000' !in v) { "must be a non-empty path without NUL characters" }
    v
}

private fun coord(limit: Double): (Any?) -> Any = { v ->
    val d = (v as? Number)?.toDouble()
    require(d != null && d >= -limit && d <= limit) { "must be a number between -${fmtNum(limit)} and ${fmtNum(limit)}" }
    d
}

private val hhmm: (Any?) -> Any = { v ->
    val parts = (v as? String)?.split(":")
    val t = try {
        if (parts == null || parts.size != 2) null
        else LocalTime.of(parts[0].trim().toInt(), parts[1].trim().toInt())
    } catch (e: RuntimeException) { // NumberFormatException, DateTimeException
        null
    }
    requireNotNull(t) { "must be \"HH:MM\" (24 h)" }
}

private val days: (Any?) -> Any = { v ->
    val items = (v as? Collection<*>)?.toList()
    require(
        items != null && items.isNotEmpty() &&
            items.all { (it is Int && it in 0..6) || (it is Long && it in 0L..6L) },
    ) { "must be a non-empty list of days 0–6 (Mon=0)" }
    items.map { (it as Number).toInt() }.toSet()
}

private val FIELDS: Map<String, Field> = linkedMapOf(
    "photo_dir" to Field(path, { it.photoDir }, { c, v -> c.photoDir = v as String }),
    "interval_s" to Field(intIn(5, 3600), { it.intervalS }, { c, v -> c.intervalS = v as Int }),
    "fade_ms" to Field(intIn(0, 5000), { it.fadeMs }, { c, v -> c.fadeMs = v as Int }),
    "lat" to Field(coord(90.0), { it.lat }, { c, v -> c.lat = v as Double }),
    "lon" to Field(coord(180.0), { it.lon }, { c, v -> c.lon = v as Double }),
    "units" to Field(choice(UNITS), { it.units }, { c, v -> c.units = v as String }),
    "clock_24h" to Field(bool, { it.clock24h }, { c, v -> c.clock24h = v as Boolean }),
    "week_start" to Field(choice(WEEK_STARTS), { it.weekStart }, { c, v -> c.weekStart = v as String }),
    "overlay_corner" to Field(choice(CORNERS), { it.overlayCorner }, { c, v -> c.overlayCorner = v as String }),
    "night_enabled" to Field(bool, { it.nightEnabled }, { c, v -> c.nightEnabled = v as Boolean }),
    "night_start" to Field(hhmm, { it.nightStart }, { c, v -> c.nightStart = v as LocalTime }),
    "night_end" to Field(hhmm, { it.nightEnd }, { c, v -> c.nightEnd = v as LocalTime }),
    "wake_s" to Field(intIn(5, 3600), { it.wakeS }, { c, v -> c.wakeS = v as Int }),
    "alarm_enabled" to Field(bool, { it.alarmEnabled }, { c, v -> c.alarmEnabled = v as Boolean }),
    "alarm_time" to Field(hhmm, { it.alarmTime }, { c, v -> c.alarmTime = v as LocalTime }),
    "alarm_days" to Field(days, { it.alarmDays }, { c, v -> c.alarmDays = (v as Set<*>).map { it as Int }.toSet() }),
    "snooze_min" to Field(intIn(1, 30), { it.snoozeMin }, { c, v -> c.snoozeMin = v as Int }),
    "cache_mb" to Field(intIn(50, 10000), { it.cacheMb }, { c, v -> c.cacheMb = v as Int }),
    "brightness" to Field(intIn(10, 100), { it.brightness }, { c, v -> c.brightness = v as Int }),
    "alarm_sound" to Field(path, { it.alarmSound }, { c, v -> c.alarmSound = v as String }),
)

private fun fmtNum(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

private fun show(v: Any?): String = when (v) {
    is LocalTime -> fmtClock(v, true)
    is Set<*> -> v.map { it as Int }.sorted().toString()
    null -> "none"
    else -> v.toString()
}

private fun repr(v: Any?): String = if (v is String) "'$v'" else v.toString()

/**
 * Builds a Config from raw stored values (port of `_validate`). Each rejected value is
 * replaced by its default and reported; `critical` is set when a key in [CRITICAL_KEYS] was
 * rejected or only one of lat/lon is set. Never throws.
 */
fun validateConfig(raw: Map<String, Any?>): ConfigResult {
    val cfg = Config()
    val warnings = mutableListOf<String>()
    var critical = false
    for ((key, value) in raw) {
        val field = FIELDS[key]
        if (field == null) {
            warnings += "$key: unknown setting, ignored"
            continue
        }
        try {
            field.set(cfg, field.parse(value))
        } catch (e: IllegalArgumentException) {
            warnings += "$key: ${repr(value)} ${e.message}; using ${show(field.get(cfg))}"
            if (key in CRITICAL_KEYS) critical = true
        }
    }
    if ((cfg.lat == null) != (cfg.lon == null)) {
        val missing = if (cfg.lon == null) "lon" else "lat"
        warnings += "$missing: missing; set both lat and lon to show weather"
        cfg.lat = null
        cfg.lon = null
        critical = true
    }
    return ConfigResult(cfg, warnings, critical)
}

/** Raw values for storage (port of `_to_toml`); `validateConfig(cfg.toRaw()).config == cfg`. */
fun Config.toRaw(): Map<String, Any?> = FIELDS.mapNotNull { (key, field) ->
    when (val v = field.get(this)) {
        null -> null
        is LocalTime -> key to fmtClock(v, true)
        is Set<*> -> key to v.map { it as Int }.sorted()
        else -> key to v
    }
}.toMap()
