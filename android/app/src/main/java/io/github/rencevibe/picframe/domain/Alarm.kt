package io.github.rencevibe.picframe.domain

import java.time.Duration
import java.time.ZonedDateTime

/** Alarm schedule and ring state (port of frame/alarm.py). Time always comes in as `now`.
 *  Comparisons use instants, so DST changes neither skip nor repeat an alarm; triggers are
 *  local wall times in now's zone. */
enum class AlarmState { IDLE, RINGING, SNOOZED }

const val RING_LIMIT_S = 10 * 60.0
const val TEST_RING_S = 10.0

/** Real seconds from a to b. */
internal fun elapsedS(a: ZonedDateTime, b: ZonedDateTime): Double =
    Duration.between(a.toInstant(), b.toInstant()).toNanos() / 1e9

/** First alarm trigger strictly after instant t, or null if the alarm can't ring. A wall time in
 *  a DST gap resolves to the first valid local instant (ZonedDateTime.of shifts it forward); in a
 *  repeated hour, to the earlier occurrence. */
private fun firstAfter(t: ZonedDateTime, cfg: Config): ZonedDateTime? {
    if (!cfg.alarmEnabled || cfg.alarmDays.isEmpty()) return null
    for (offset in 0L..7L) {
        val day = t.toLocalDate().plusDays(offset)
        if (day.dayOfWeek.value - 1 in cfg.alarmDays) {
            val cand = ZonedDateTime.of(day, cfg.alarmTime, t.zone)
            if (cand.toInstant().isAfter(t.toInstant())) return cand
        }
    }
    return null
}

fun nextTrigger(now: ZonedDateTime, cfg: Config): ZonedDateTime? = firstAfter(now, cfg)

class Alarm(val cfg: Config) { // read live each tick, so unsaved Settings changes apply at once
    var state = AlarmState.IDLE
        private set
    var missedAt: ZonedDateTime? = null
    var triggerAt: ZonedDateTime? = null // trigger that started this ring cycle
        private set
    var snoozeUntil: ZonedDateTime? = null
        private set
    var isTest = false
        private set
    private var ringStarted: ZonedDateTime? = null
    private var lastTick: ZonedDateTime? = null

    private fun ring(now: ZonedDateTime) {
        state = AlarmState.RINGING
        ringStarted = now
    }

    private fun stop() {
        state = AlarmState.IDLE
        snoozeUntil = null
        ringStarted = null
        isTest = false
    }

    fun tick(now: ZonedDateTime): AlarmState {
        val last = lastTick ?: now.minusSeconds(1)
        lastTick = now
        when (state) {
            AlarmState.RINGING -> {
                val limit = if (isTest) TEST_RING_S else RING_LIMIT_S
                if (elapsedS(ringStarted!!, now) >= limit) {
                    if (!isTest) missedAt = triggerAt
                    stop()
                }
            }
            AlarmState.SNOOZED -> if (!now.toInstant().isBefore(snoozeUntil!!.toInstant())) {
                snoozeUntil = null
                ring(now)
            }
            AlarmState.IDLE -> {
                val trig = firstAfter(last, cfg)
                if (trig != null && !trig.toInstant().isAfter(now.toInstant())) {
                    triggerAt = trig
                    if (elapsedS(trig, now) >= RING_LIMIT_S) missedAt = trig // clock jumped past the whole ring
                    else ring(now)
                }
            }
        }
        return state
    }

    fun snooze(now: ZonedDateTime) {
        if (state != AlarmState.RINGING) return
        if (isTest) {
            stop()
            return
        }
        state = AlarmState.SNOOZED
        ringStarted = null
        snoozeUntil = now.plusSeconds(cfg.snoozeMin * 60L) // real time, not wall time
    }

    /** Stops a ring or cancels a pending snooze. */
    @Suppress("UNUSED_PARAMETER")
    fun dismiss(now: ZonedDateTime) {
        if (state != AlarmState.IDLE) stop()
    }

    fun test(now: ZonedDateTime) {
        isTest = true
        ring(now)
    }

    fun countdown(now: ZonedDateTime): String {
        val trig = nextTrigger(now, cfg) ?: return ""
        val nanos = Duration.between(now.toInstant(), trig.toInstant()).toNanos()
        val minutes = Math.ceil(nanos / 60e9).toLong()
        val days = minutes / (24 * 60)
        val rem = minutes % (24 * 60)
        val hours = rem / 60
        val mins = rem % 60
        return when {
            days > 0 -> "in ${days}d ${hours}h"
            hours > 0 -> "in ${hours}h ${mins}m"
            else -> "in ${mins}m"
        }
    }
}
