package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Port of tests/test_alarm.py. */
class AlarmTest {
    private val la: ZoneId = ZoneId.of("America/Los_Angeles")
    private val wed = ZonedDateTime.of(2026, 10, 14, 12, 41, 0, 0, la) // a Wednesday
    private val thu = wed.plusDays(1)

    private fun at(h: Int, m: Int, s: Int = 0, day: ZonedDateTime = wed) =
        ZonedDateTime.of(day.toLocalDate(), LocalTime.of(h, m, s), la)

    private fun date(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, la)

    /** Tick in real (UTC) time steps; return the local times of each transition to ringing. */
    private fun run(alarm: Alarm, start: ZonedDateTime, minutes: Long, stepS: Long = 30): List<ZonedDateTime> {
        val rings = mutableListOf<ZonedDateTime>()
        var t = start.toInstant()
        val end = t.plusSeconds(minutes * 60)
        var prev = alarm.state
        while (!t.isAfter(end)) {
            val local = t.atZone(la)
            var state = alarm.tick(local)
            if (state == AlarmState.RINGING && prev != AlarmState.RINGING) {
                rings += local
                alarm.dismiss(local)
                state = alarm.state
            }
            prev = state
            t = t.plusSeconds(stepS)
        }
        return rings
    }

    @Test fun nextTriggerSkipsDisabledDays() {
        assertEquals(date(2026, 10, 15, 6, 30), nextTrigger(wed, Config()))
        assertEquals(date(2026, 10, 19, 6, 30), nextTrigger(date(2026, 10, 16, 22), Config()))
    }

    @Test fun firesOnce() {
        val alarm = Alarm(Config())
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 29, 50, thu)))
        assertEquals(AlarmState.RINGING, alarm.tick(at(6, 30, 0, thu)))
        assertEquals(AlarmState.RINGING, alarm.tick(at(6, 30, 30, thu)))
        alarm.dismiss(at(6, 30, 40, thu))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 30, 50, thu)))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 45, 0, thu)))
    }

    @Test fun snoozeReringsAfter9Min() {
        val alarm = Alarm(Config())
        alarm.tick(at(6, 29, 59, thu))
        assertEquals(AlarmState.RINGING, alarm.tick(at(6, 30, 0, thu)))
        alarm.snooze(at(6, 31, 0, thu))
        assertEquals(AlarmState.SNOOZED, alarm.state)
        assertEquals(at(6, 40, 0, thu), alarm.snoozeUntil)
        assertEquals(AlarmState.SNOOZED, alarm.tick(at(6, 39, 59, thu)))
        assertEquals(AlarmState.RINGING, alarm.tick(at(6, 40, 0, thu)))
    }

    @Test fun dismissWhileSnoozedCancelsSnooze() {
        val alarm = Alarm(Config())
        alarm.tick(at(6, 29, 59, thu))
        alarm.tick(at(6, 30, 0, thu))
        alarm.snooze(at(6, 30, 5, thu))
        alarm.dismiss(at(6, 31, 0, thu))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 45, 0, thu)))
        assertNull(alarm.missedAt)
    }

    @Test fun unanswered10MinSetsMissed() {
        val alarm = Alarm(Config())
        alarm.tick(at(6, 29, 59, thu))
        alarm.tick(at(6, 30, 0, thu))
        assertEquals(AlarmState.RINGING, alarm.tick(at(6, 39, 59, thu)))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 40, 0, thu)))
        assertEquals(at(6, 30, 0, thu), alarm.missedAt)
    }

    @Test fun unansweredSnoozeRingReportsOriginalTime() {
        val alarm = Alarm(Config())
        alarm.tick(at(6, 29, 59, thu))
        alarm.tick(at(6, 30, 0, thu))
        alarm.snooze(at(6, 30, 0, thu))
        alarm.tick(at(6, 39, 0, thu))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 49, 0, thu)))
        assertEquals(at(6, 30, 0, thu), alarm.missedAt)
    }

    @Test fun dstSpringForwardAndFallBack() {
        for (day in listOf(date(2026, 3, 8), date(2026, 11, 1))) {
            val rings = run(Alarm(Config(alarmDays = (0..6).toSet())), day, minutes = 12 * 60)
            assertEquals(listOf(6 to 30), rings.map { it.hour to it.minute })
        }
    }

    @Test fun dstGapAndRepeatedHourRingOnce() {
        // 02:30 doesn't exist on 2026-03-08; 01:30 happens twice on 2026-11-01
        for ((day, t) in listOf(date(2026, 3, 8) to LocalTime.of(2, 30), date(2026, 11, 1) to LocalTime.of(1, 30))) {
            val rings = run(Alarm(Config(alarmDays = (0..6).toSet(), alarmTime = t)), day, minutes = 6 * 60)
            assertEquals(1, rings.size)
        }
    }

    @Test fun dstGapNextTriggerIsNormalizedToFirstValidLocalTime() {
        val trigger = nextTrigger(date(2026, 3, 8), Config(alarmDays = (0..6).toSet(), alarmTime = LocalTime.of(2, 30)))
        assertEquals(date(2026, 3, 8, 3, 30), trigger)
    }

    @Test fun changedAlarmTimeDoesNotRingForPastTime() {
        val alarm = Alarm(Config(alarmDays = (0..6).toSet()))
        alarm.tick(at(12, 0))
        alarm.cfg.alarmTime = LocalTime.of(7, 0) // already passed today
        assertEquals(AlarmState.IDLE, alarm.tick(at(12, 0, 1)))
    }

    @Test fun testRings10sWithoutMissedFlag() {
        val alarm = Alarm(Config(alarmEnabled = false))
        alarm.test(at(12, 0))
        assertEquals(AlarmState.RINGING, alarm.state)
        assertEquals(AlarmState.RINGING, alarm.tick(at(12, 0, 9)))
        assertEquals(AlarmState.IDLE, alarm.tick(at(12, 0, 10)))
        assertNull(alarm.missedAt)
    }

    @Test fun countdownText() {
        val alarm = Alarm(Config())
        assertEquals("in 17h 49m", alarm.countdown(wed))
        assertEquals("in 30m", alarm.countdown(at(6, 0, 0, thu)))
        assertEquals("in 2d 8h", alarm.countdown(date(2026, 10, 16, 22)))
    }

    @Test fun disabledNeverRings() {
        val off = Config(alarmEnabled = false)
        assertNull(nextTrigger(wed, off))
        val alarm = Alarm(off)
        assertEquals(emptyList<ZonedDateTime>(), run(alarm, wed, minutes = 3 * 24 * 60, stepS = 60))
        assertEquals("", alarm.countdown(wed))
    }

    // Kotlin-only edge cases

    @Test fun clockJumpPastWholeRingMarksMissedWithoutRinging() {
        val alarm = Alarm(Config())
        alarm.tick(at(6, 0, 0, thu))
        assertEquals(AlarmState.IDLE, alarm.tick(at(6, 45, 0, thu))) // slept through 6:30–6:40
        assertEquals(at(6, 30, 0, thu), alarm.missedAt)
    }

    @Test fun snoozeDuringTestRingStopsIt() {
        val alarm = Alarm(Config())
        alarm.test(at(12, 0))
        alarm.snooze(at(12, 0, 2))
        assertEquals(AlarmState.IDLE, alarm.state)
        assertEquals(false, alarm.isTest)
    }
}
