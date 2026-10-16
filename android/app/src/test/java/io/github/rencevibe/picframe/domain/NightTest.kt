package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Port of tests/test_night.py. */
class NightTest {
    private val la: ZoneId = ZoneId.of("America/Los_Angeles")
    private val night = ZonedDateTime.of(2026, 10, 14, 23, 30, 0, 0, la)
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    @Test fun overnightWindow() {
        val s = t(23, 0)
        val e = t(6, 0)
        assertTrue(isNight(t(23, 30), s, e))
        assertTrue(isNight(t(5, 59), s, e))
        assertFalse(isNight(t(6, 0), s, e))
        assertFalse(isNight(t(12, 0), s, e))
        assertTrue(isNight(t(23, 0), s, e))
    }

    @Test fun sameDayWindow() {
        val s = t(13, 0)
        val e = t(15, 0)
        assertTrue(isNight(t(14, 0), s, e))
        assertFalse(isNight(t(15, 0), s, e))
        assertFalse(isNight(t(12, 59), s, e))
    }

    @Test fun equalOrNullNeverNight() {
        for (x in listOf(t(0, 0), t(6, 0), t(23, 30))) {
            assertFalse(isNight(x, t(6, 0), t(6, 0)))
            assertFalse(isNight(x, null, t(6, 0)))
            assertFalse(isNight(x, t(23, 0), null))
        }
    }

    @Test fun disabledNightModeNeverDark() {
        assertFalse(NightController(Config(nightEnabled = false)).shouldBeDark(night, AlarmState.IDLE))
    }

    @Test fun tapWakesForWakeS() {
        val nc = NightController(Config())
        assertTrue(nc.shouldBeDark(night, AlarmState.IDLE))
        nc.wake(night)
        assertFalse(nc.shouldBeDark(night.plusSeconds(59), AlarmState.IDLE))
        assertTrue(nc.shouldBeDark(night.plusSeconds(60), AlarmState.IDLE))
    }

    @Test fun wakeTapAgainRestartsTimer() {
        val nc = NightController(Config())
        nc.wake(night)
        nc.wake(night.plusSeconds(50))
        assertFalse(nc.shouldBeDark(night.plusSeconds(100), AlarmState.IDLE))
        assertTrue(nc.shouldBeDark(night.plusSeconds(110), AlarmState.IDLE))
    }

    @Test fun ringingAlarmForcesLight() {
        val nc = NightController(Config())
        assertFalse(nc.shouldBeDark(night, AlarmState.RINGING))
        assertTrue(nc.shouldBeDark(night, AlarmState.SNOOZED))
        assertTrue(nc.shouldBeDark(night, AlarmState.IDLE))
    }

    @Test fun settingsChangesApplyLive() {
        val cfg = Config()
        val nc = NightController(cfg)
        cfg.nightStart = t(23, 45)
        assertFalse(nc.shouldBeDark(night, AlarmState.IDLE))
    }

    @Test fun wakeLastsWakeSOfRealTimeAcrossDst() {
        // fall back: 01:59:30 PDT → 60 s later it is 01:00:30 PST (wall clock went backwards)
        // spring forward: 01:59:30 PST → 60 s later it is 03:00:30 PDT (wall clock skipped an hour)
        for (startUtc in listOf(
            ZonedDateTime.of(2026, 11, 1, 8, 59, 30, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2026, 3, 8, 9, 59, 30, 0, ZoneOffset.UTC),
        )) {
            val nc = NightController(Config(nightStart = t(0, 0), nightEnd = t(6, 0)))
            val start = startUtc.withZoneSameInstant(la)
            assertTrue(nc.shouldBeDark(start, AlarmState.IDLE))
            nc.wake(start)
            assertFalse(nc.shouldBeDark(startUtc.plusSeconds(59).withZoneSameInstant(la), AlarmState.IDLE))
            assertTrue(nc.shouldBeDark(startUtc.plusSeconds(60).withZoneSameInstant(la), AlarmState.IDLE))
        }
    }
}
