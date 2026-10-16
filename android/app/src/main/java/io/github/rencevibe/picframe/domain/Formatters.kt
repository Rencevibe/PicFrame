package io.github.rencevibe.picframe.domain

import java.time.LocalTime

val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun two(n: Int) = n.toString().padStart(2, '0')

/** "6:30 AM" or "06:30" (port of controller.fmt_clock). */
fun fmtClock(t: LocalTime, clock24h: Boolean): String =
    if (clock24h) "${two(t.hour)}:${two(t.minute)}"
    else "${if (t.hour % 12 == 0) 12 else t.hour % 12}:${two(t.minute)} ${if (t.hour < 12) "AM" else "PM"}"

/** "6 AM" or "06" (port of controller.fmt_hour). */
fun fmtHour(h: Int, clock24h: Boolean): String =
    if (clock24h) two(h) else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"

/** {0..4} → "Mon–Fri"; runs of 3+ days use a dash, otherwise a comma list (port of days_label). */
fun daysLabel(days: Set<Int>): String {
    val d = days.sorted()
    return when {
        d.size == 7 -> "Every day"
        d == listOf(5, 6) -> "Weekends"
        d.size >= 3 && d == (d.first()..d.last()).toList() -> "${DAY_NAMES[d.first()]}–${DAY_NAMES[d.last()]}"
        else -> d.joinToString(", ") { DAY_NAMES[it] }
    }
}

/** "30 s", "2 min", "1 min 30 s" (port of settings_screen.fmt_interval). */
fun fmtInterval(s: Int): String = when {
    s < 60 -> "$s s"
    s % 60 == 0 -> "${s / 60} min"
    else -> "${s / 60} min ${s % 60} s"
}

/** Wall-clock time shifted by [minutes], wrapping around midnight. */
fun shiftTime(t: LocalTime, minutes: Int): LocalTime {
    val total = Math.floorMod(t.hour * 60 + t.minute + minutes, 24 * 60)
    return LocalTime.of(total / 60, total % 60)
}
