package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

/** Port of tests/test_controller.py. */
class ControllerTest {
    private val la: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Controller with a fake monotonic clock that advances together with `now`. */
    private inner class Rig(start: ZonedDateTime, saveResult: String? = null, configure: Config.() -> Unit = {}) {
        val cfg = Config(alarmDays = (0..6).toSet()).apply(configure)
        val alarm = Alarm(cfg)
        val night = NightController(cfg)
        var t = 1000.0
        var now: ZonedDateTime = start
        val saved = mutableListOf<Config>()
        val c = Controller(cfg, alarm, night, { t }, save = { saved += it; saveResult })

        init {
            c.onTick(now)
        }

        fun advance(seconds: Double, step: Double = 1.0) {
            var left = seconds
            while (left > 1e-9) {
                val d = minOf(step, left)
                t += d
                now = now.plusNanos((d * 1e9).roundToLong())
                c.onTick(now)
                left -= d
            }
        }

        fun tap(button: String? = null, downAgo: Double = 0.1) = c.onTap(button, now, downAt = t - downAgo)
    }

    private fun day(h: Int, m: Int = 0, s: Int = 0) = ZonedDateTime.of(2026, 10, 14, h, m, s, 0, la)

    @Test fun photoTapShowsControlsThenHidesAfter5s() {
        val r = Rig(day(12))
        r.tap("to_photo")
        assertEquals(Mode.PHOTO, r.c.mode)
        assertFalse(r.c.controlsVisible)
        r.tap(null)
        assertTrue(r.c.controlsVisible)
        assertEquals(0.0, r.c.hideProgress, 1e-9)
        r.advance(4.0)
        assertTrue(r.c.controlsVisible)
        assertEquals(0.8, r.c.hideProgress, 1e-9)
        r.tap("next") // any tap on the bar restarts the 5 s
        assertEquals(1, r.c.takePhotoStep())
        assertEquals(0, r.c.takePhotoStep())
        r.advance(4.5)
        assertTrue(r.c.controlsVisible)
        r.advance(0.5)
        assertFalse(r.c.controlsVisible)
        assertEquals(Mode.PHOTO, r.c.mode)
    }

    @Test fun onlyModeButtonsSwitchMode() {
        val r = Rig(day(12))
        r.tap(null)
        assertEquals(Mode.DASHBOARD, r.c.mode)
        r.tap("to_photo")
        r.tap(null)
        r.tap("prev")
        assertEquals(Mode.PHOTO, r.c.mode)
        assertEquals(-1, r.c.takePhotoStep())
        r.tap("to_dashboard")
        assertEquals(Mode.DASHBOARD, r.c.mode)
    }

    private fun alarmPreempts(startMode: Mode) {
        val r = if (startMode == Mode.SETTINGS) {
            Rig(day(6, 29, 50)) { nightEnd = LocalTime.of(6, 0) }.also { it.tap("open_settings") }
        } else {
            Rig(day(4, 59, 50)) { alarmTime = LocalTime.of(5, 0) }.also {
                assertEquals(Mode.DARK, it.c.mode)
                if (startMode == Mode.NIGHT_CLOCK) it.tap(null)
            }
        }
        assertEquals(startMode, r.c.mode)
        r.advance(10.0)
        assertEquals(Mode.ALARM, r.c.mode) // on the same tick the alarm fired
        r.advance(2.0)
        r.tap("dismiss")
        assertEquals(if (startMode == Mode.SETTINGS) Mode.SETTINGS else Mode.DARK, r.c.mode)
    }

    @Test fun alarmPreemptsSettings() = alarmPreempts(Mode.SETTINGS)

    @Test fun alarmPreemptsDark() = alarmPreempts(Mode.DARK)

    @Test fun alarmPreemptsNightClock() = alarmPreempts(Mode.NIGHT_CLOCK)

    @Test fun settingsIdlePausedDuringAlarm() {
        val r = Rig(day(6, 28, 10))
        r.tap("open_settings")
        r.tap("snooze_plus") // unsaved change
        r.advance(110.0)
        assertEquals(Mode.ALARM, r.c.mode)
        r.advance(300.0)
        r.tap("dismiss")
        assertEquals(Mode.SETTINGS, r.c.mode)
        assertEquals(120.0, r.c.settingsIdleLeft()!!, 1e-9)
        assertEquals(10, r.cfg.snoozeMin)
        assertEquals(emptyList<Config>(), r.saved)
    }

    @Test fun alarmUsesLiveUnsavedSettings() {
        val r = Rig(day(12, 29))
        r.tap("open_settings")
        repeat(6) { r.tap("alarm_hour_plus") } // 6:30 → 12:30, not saved yet
        assertEquals(LocalTime.of(12, 30), r.cfg.alarmTime)
        assertEquals(emptyList<Config>(), r.saved)
        r.advance(59.0)
        assertEquals(Mode.SETTINGS, r.c.mode)
        r.advance(1.0)
        assertEquals(Mode.ALARM, r.c.mode)
    }

    @Test fun alarmIgnoresEarlyTouch() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        assertEquals(Mode.ALARM, r.c.mode)
        r.advance(1.5)
        r.c.onTap("dismiss", r.now, downAt = r.t - 2.0) // finger went down before the alarm appeared
        assertEquals(AlarmState.RINGING, r.alarm.state)
        val r2 = Rig(day(6, 29, 59))
        r2.advance(1.0)
        r2.advance(0.5, step = 0.5)
        r2.tap("snooze", downAgo = 0.1) // within the first second
        assertEquals(AlarmState.RINGING, r2.alarm.state)
        r2.advance(1.0)
        r2.tap("snooze", downAgo = 0.1)
        assertEquals(AlarmState.SNOOZED, r2.alarm.state)
        assertEquals(Mode.DASHBOARD, r2.c.mode)
    }

    @Test fun wakeTapIsConsumed() {
        val r = Rig(day(23, 30))
        assertEquals(Mode.DARK, r.c.mode)
        r.tap("open_settings") // lands where the button will be: only wakes
        assertEquals(Mode.NIGHT_CLOCK, r.c.mode)
        r.advance(50.0)
        r.tap(null) // elsewhere: restarts wake_s, no mode change
        r.advance(50.0)
        assertEquals(Mode.NIGHT_CLOCK, r.c.mode)
        r.advance(11.0)
        assertEquals(Mode.DARK, r.c.mode)
        r.tap(null)
        r.tap("open_settings")
        assertEquals(Mode.SETTINGS, r.c.mode)
        r.tap("done")
        assertEquals(Mode.DARK, r.c.mode) // still night
    }

    @Test fun tapInDarkShowsNightClock() {
        val r = Rig(day(23, 30))
        r.tap(null)
        assertEquals(Mode.NIGHT_CLOCK, r.c.mode)
        r.advance(59.0)
        assertEquals(Mode.NIGHT_CLOCK, r.c.mode)
        r.advance(1.0)
        assertEquals(Mode.DARK, r.c.mode)
    }

    @Test fun nightEndsReturnsToDayMode() {
        val r = Rig(day(5, 59, 50)) { alarmEnabled = false }
        r.c.mode = Mode.DARK
        r.c.dayMode = Mode.PHOTO
        r.advance(10.0)
        assertEquals(Mode.PHOTO, r.c.mode)
    }

    @Test fun saveFailureKeepsSettingsOpen() {
        val r = Rig(day(12), saveResult = "Read-only file system")
        r.tap("open_settings")
        r.tap("snooze_plus")
        r.tap("done")
        assertEquals(Mode.SETTINGS, r.c.mode)
        assertEquals("Read-only file system", r.c.saveError)
        r.tap("keep_unsaved")
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertNull(r.c.saveError)
        assertEquals(10, r.cfg.snoozeMin)
        assertEquals("Settings not saved", r.c.banner(r.now)!!.text)
        r.tap("open_settings")
        r.advance(120.0) // idle close with a failing save takes the same path
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertEquals("Settings not saved", r.c.banner(r.now)!!.text)
    }

    @Test fun settingsChangeReturnsNewConfig() {
        val r = Rig(day(12)) { alarmTime = LocalTime.of(23, 30) }
        val c = r.c
        assertEquals(LocalTime.of(23, 35), c.applySetting("alarm_min_plus").alarmTime)
        c.applySetting("alarm_min_minus")
        assertEquals(LocalTime.of(0, 30), c.applySetting("alarm_hour_plus").alarmTime)
        r.cfg.alarmTime = LocalTime.of(6, 55)
        assertEquals(LocalTime.of(6, 0), c.applySetting("alarm_min_plus").alarmTime) // wraps within the hour
        r.cfg.alarmDays = setOf(2)
        assertEquals(setOf(2), c.applySetting("day_2").alarmDays) // never empties the repeat days
        assertEquals(setOf(2, 5), c.applySetting("day_5").alarmDays)
        assertEquals(60, c.applySetting("interval_plus").intervalS)
        assertEquals(30, c.applySetting("interval_minus").intervalS)
        r.cfg.intervalS = 45 // off-step value from storage
        assertEquals(60, c.applySetting("interval_plus").intervalS)
        assertEquals(0, c.applySetting("fade_toggle").fadeMs)
        assertEquals(1000, c.applySetting("fade_toggle").fadeMs)
        assertTrue(c.applySetting("clock_24").clock24h)
        assertEquals(LocalTime.of(23, 30), c.applySetting("night_start_plus").nightStart)
        assertEquals(LocalTime.of(5, 30), c.applySetting("night_end_minus").nightEnd)
        r.cfg.nightStart = LocalTime.of(23, 30)
        assertEquals(LocalTime.of(0, 0), c.applySetting("night_start_plus").nightStart)
        assertFalse(c.applySetting("night_toggle").nightEnabled)
        r.cfg.snoozeMin = 30
        assertEquals(30, c.applySetting("snooze_plus").snoozeMin)
        assertFalse(c.applySetting("alarm_toggle").alarmEnabled)
    }

    @Test fun stopSnoozeReturnsIdle() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        r.advance(1.5)
        r.tap("snooze")
        assertEquals(AlarmState.SNOOZED, r.alarm.state)
        assertEquals(Mode.DASHBOARD, r.c.mode)
        r.tap("stop_snooze")
        assertEquals(AlarmState.IDLE, r.alarm.state)
        r.advance(15 * 60.0, step = 5.0)
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertEquals(AlarmState.IDLE, r.alarm.state)
    }

    @Test fun settingsIdle120sClosesAndSaves() {
        val r = Rig(day(12))
        r.tap("to_photo")
        r.tap("to_dashboard")
        r.tap("open_settings")
        r.tap("snooze_plus")
        r.advance(119.0)
        assertEquals(Mode.SETTINGS, r.c.mode)
        r.advance(1.0)
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertEquals(listOf(r.cfg), r.saved)
    }

    @Test fun testAlarmReturnsToSettings() {
        val r = Rig(day(12))
        r.tap("open_settings")
        r.tap("test_alarm")
        assertEquals(Mode.ALARM, r.c.mode)
        assertTrue(r.alarm.isTest)
        r.advance(10.0)
        assertEquals(Mode.SETTINGS, r.c.mode)
        assertNull(r.alarm.missedAt)
    }

    @Test fun missedAlarmBannerAndClear() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        r.advance(600.0, step = 5.0)
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertEquals(Banner("Missed alarm 6:30 AM", "missed", 0), r.c.banner(r.now))
        r.c.configBanner = "config.toml has errors — using defaults"
        assertEquals(1, r.c.banner(r.now)!!.more)
        r.tap("clear_banner")
        assertEquals(Banner("config.toml has errors — using defaults", "warning", 0), r.c.banner(r.now))
    }

    @Test fun timeNotSetBanner() {
        val r = Rig(ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, la))
        assertEquals("Time not set", r.c.banner(r.now)!!.text)
    }

    @Test fun formattingHelpers() {
        assertEquals("Mon–Fri", daysLabel(setOf(0, 1, 2, 3, 4)))
        assertEquals("Every day", daysLabel((0..6).toSet()))
        assertEquals("Weekends", daysLabel(setOf(5, 6)))
        assertEquals("Mon, Wed, Fri", daysLabel(setOf(0, 2, 4)))
        assertEquals("Tue–Thu", daysLabel(setOf(1, 2, 3)))
        assertEquals("6:30 AM", fmtClock(LocalTime.of(6, 30), false))
        assertEquals("00:05", fmtClock(LocalTime.of(0, 5), true))
        assertEquals("12:00 PM", fmtClock(LocalTime.of(12, 0), false))
        // Kotlin-only: the other formatters ported from the Pi UI
        assertEquals("12 AM", fmtHour(0, false))
        assertEquals("18", fmtHour(18, true))
        assertEquals("30 s", fmtInterval(30))
        assertEquals("2 min", fmtInterval(120))
        assertEquals("1 min 30 s", fmtInterval(90))
    }

    @Test fun pressStartedInGuardIsIgnoredEvenIfReleasedLater() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        assertEquals(Mode.ALARM, r.c.mode)
        r.advance(0.5, step = 0.5)
        val down = r.t // finger goes down 0.5 s after the alarm appeared …
        r.advance(1.0)
        r.c.onTap("dismiss", r.now, downAt = down) // … and lifts 1.5 s after
        assertEquals(AlarmState.RINGING, r.alarm.state)
        assertEquals(Mode.ALARM, r.c.mode)
    }

    @Test fun clearingPhotoChipAlsoShowsControls() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        r.advance(600.0, step = 5.0) // unanswered → missed
        r.tap("to_photo")
        assertFalse(r.c.controlsVisible)
        r.tap("clear_banner")
        assertNull(r.alarm.missedAt)
        assertTrue(r.c.controlsVisible)
    }

    // Kotlin-only edge cases

    @Test fun brightnessStepsAndClamps() {
        val r = Rig(day(12)) { brightness = 95 }
        assertEquals(100, r.c.applySetting("brightness_plus").brightness)
        r.cfg.brightness = 10
        assertEquals(10, r.c.applySetting("brightness_minus").brightness)
        r.cfg.nightEnabled = false
        val before = r.cfg.nightStart
        assertEquals(before, r.c.applySetting("night_start_plus").nightStart) // disabled stepper ignores taps
    }

    @Test fun alarmTouchGuardDoesNotBlockLaterTaps() {
        val r = Rig(day(6, 29, 59))
        r.advance(1.0)
        r.advance(1.5)
        r.tap("elsewhere")
        assertEquals(Mode.ALARM, r.c.mode) // only Snooze and Dismiss do anything
        r.tap("dismiss")
        assertEquals(Mode.DASHBOARD, r.c.mode)
        assertEquals(AlarmState.IDLE, r.alarm.state)
    }
}
