package io.github.rencevibe.picframe.domain

import java.time.LocalTime
import java.time.ZonedDateTime

/** True inside [start, end); the window may cross midnight. Equal or null = never night
 *  (port of frame/night.py). */
fun isNight(now: LocalTime, start: LocalTime?, end: LocalTime?): Boolean {
    if (start == null || end == null || start == end) return false
    if (start < end) return now >= start && now < end
    return now >= start || now < end
}

internal fun epochS(t: ZonedDateTime): Double = t.toEpochSecond() + t.nano / 1e9

class NightController(val cfg: Config) { // read live, so Settings changes apply at once
    private var awakeUntil: Double? = null

    /** A tap keeps the screen lit for wake_s seconds of real time (restarts on each tap). */
    fun wake(now: ZonedDateTime) {
        awakeUntil = epochS(now) + cfg.wakeS
    }

    fun shouldBeDark(now: ZonedDateTime, alarmState: AlarmState): Boolean {
        if (alarmState == AlarmState.RINGING) return false
        val until = awakeUntil
        if (until != null && epochS(now) < until) return false
        val on = cfg.nightEnabled
        return isNight(now.toLocalTime(), if (on) cfg.nightStart else null, if (on) cfg.nightEnd else null)
    }
}
