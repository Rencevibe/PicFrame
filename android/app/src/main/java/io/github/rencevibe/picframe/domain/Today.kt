package io.github.rencevibe.picframe.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Today list from todo.toml (port of frame/today.py). Reading the file is the data layer's job
 *  (M3/M4); this parses its text. */
val KINDS = listOf("appointment", "todo")

data class TodayItem(
    val title: String,
    val kind: String, // "appointment" | "todo"
    val at: LocalTime?,
    val day: LocalDate?,
    val done: Boolean = false,
)

private val HHMM = Regex("\\d{2}:\\d{2}")

/** Naive local "HH:MM" only: offsets or seconds would break comparisons with now. */
private fun hhmm(v: Any?): LocalTime {
    require(v is String && HHMM.matches(v)) { "time must be \"HH:MM\"" }
    return try {
        LocalTime.of(v.substring(0, 2).toInt(), v.substring(3).toInt())
    } catch (e: java.time.DateTimeException) {
        throw IllegalArgumentException(e.message)
    }
}

private fun parseItem(raw: Any?): TodayItem {
    require(raw is Map<*, *>) { "not a table" }
    val title = raw["title"]
    val kind = raw["kind"]
    require(title is String && title.isNotBlank()) { "missing title" }
    require(kind is String && kind in KINDS) { "kind must be one of ${KINDS.joinToString(", ")}" }
    val at = if ("time" in raw) hhmm(raw["time"]) else null
    val day = if ("date" in raw) {
        val d = raw["date"]
        require(d is String) { "date must be \"YYYY-MM-DD\"" }
        try {
            LocalDate.parse(d)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("date must be \"YYYY-MM-DD\"")
        }
    } else null
    val done = raw["done"] ?: false
    require(done is Boolean) { "done must be true or false" }
    return TodayItem(title.trim(), kind, at, day, done)
}

/** Reads `[[item]]` entries. Null text (missing file) or invalid TOML gives []; a bad entry is
 *  skipped and reported through [log]. */
fun parseTodayItems(text: String?, name: String = "todo.toml", log: (String) -> Unit = {}): List<TodayItem> {
    if (text == null) return emptyList()
    val entries = try {
        TomlSubset.parse(text)["item"]
    } catch (e: TomlError) {
        log("today: $name unreadable (${e.message})")
        return emptyList()
    }
    val items = mutableListOf<TodayItem>()
    (entries as? List<*>)?.forEachIndexed { index, raw ->
        try {
            items += parseItem(raw)
        } catch (e: IllegalArgumentException) {
            log("today: $name item ${index + 1} skipped (${e.message})")
        }
    }
    return items
}

/** Today's open items: timed ones still ahead (ascending), then untimed in file order.
 *  Returns the first [limit] and how many more there are. */
fun visibleToday(items: List<TodayItem>, now: LocalDateTime, limit: Int = 3): Pair<List<TodayItem>, Int> {
    val minute = now.toLocalTime().withSecond(0).withNano(0)
    val today = items.filter { !it.done && (it.day == null || it.day == now.toLocalDate()) }
    val timed = today.filter { it.at != null && it.at >= minute }.sortedBy { it.at }
    val untimed = today.filter { it.at == null }
    val eligible = timed + untimed
    return eligible.take(limit) to maxOf(0, eligible.size - limit)
}

/** Uses now's local wall time. */
fun visibleToday(items: List<TodayItem>, now: ZonedDateTime, limit: Int = 3) =
    visibleToday(items, now.toLocalDateTime(), limit)
