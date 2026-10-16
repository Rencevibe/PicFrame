package io.github.rencevibe.picframe.domain

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Mode controller (port of frame/controller.py): which screen is up, what a tap does, settings
 * changes, banners. Two time sources: `now` (zoned local time) for the alarm and night schedule,
 * and [clock] (monotonic seconds) for UI timers, so a wall-clock change can't stall them.
 */
enum class Mode { DASHBOARD, PHOTO, SETTINGS, ALARM, DARK, NIGHT_CLOCK }

data class Banner(val text: String, val kind: String, val more: Int)

const val CONTROLS_S = 5.0
const val SETTINGS_IDLE_S = 120.0
const val ALARM_GUARD_S = 1.0
val INTERVAL_STEPS = listOf(10, 15, 30, 60, 120, 300, 600, 1800)
val SNOOZE_RANGE = 1..30
val BRIGHTNESS_RANGE = 10..100
val WAKE_STEPS = listOf(15, 30, 60, 120, 300)
val CACHE_STEPS = listOf(100, 250, 500, 1000, 2000)

/** Settings buttons the UI layer fulfils with a system picker or dialog (see [Controller.takeRequest]). */
val EXTERNAL_REQUESTS = setOf("pick_folder", "edit_location", "pick_sound")

class Controller(
    val cfg: Config,
    val alarm: Alarm,
    val night: NightController,
    private val clock: () -> Double,
    private val save: (Config) -> String? = { null },
    private val log: (String) -> Unit = {},
) {
    var mode = Mode.DASHBOARD
    var dayMode = Mode.DASHBOARD // dashboard or photo: where to go when night ends
    private var controlsUntil: Double? = null
    private var settingsReturn = Mode.DASHBOARD
    private var settingsDeadline: Double? = null
    private var settingsIdlePaused: Double? = null // seconds left while an alarm covers Settings
    var saveError: String? = null
        private set
    var unsaved = false
        private set
    var configBanner: String? = null
    private var alarmReturn = Mode.DASHBOARD
    private var alarmShownAt = 0.0
    private var photoStep = 0
    var settingsPage = "main" // "main" (Alarm + Display cards) or "frame" (Frame card)
        private set
    private var request: String? = null

    // --- queries for the UI ---

    val controlsVisible: Boolean get() = mode == Mode.PHOTO && controlsUntil != null

    /** 0 → 1 as the photo controls approach hiding; 1 when hidden. */
    val hideProgress: Double
        get() {
            val until = controlsUntil
            if (!controlsVisible || until == null) return 1.0
            return (1 - (until - clock()) / CONTROLS_S).coerceIn(0.0, 1.0)
        }

    fun settingsIdleLeft(): Double? = settingsIdlePaused ?: settingsDeadline?.let { it - clock() }

    /** A pending [EXTERNAL_REQUESTS] id for the UI to open, cleared by this call. */
    fun takeRequest(): String? = request.also { request = null }

    /** The UI calls this when a picker or dialog it opened closes, so Settings doesn't idle-close
     *  the moment the frame is visible again. */
    fun touchSettings() {
        if (mode == Mode.SETTINGS && settingsIdlePaused == null) settingsDeadline = clock() + SETTINGS_IDLE_S
    }

    /** -1 / +1 when Prev / Next was tapped since the last call, else 0. */
    fun takePhotoStep(): Int = photoStep.also { photoStep = 0 }

    /** Highest-priority banner plus how many others wait. */
    fun banner(now: ZonedDateTime): Banner? {
        val items = mutableListOf<Pair<String, String>>()
        alarm.missedAt?.let { items += "missed" to "Missed alarm ${fmtClock(it.toLocalTime(), cfg.clock24h)}" }
        if (now.year < 2026) items += "warning" to "Time not set"
        if (unsaved) items += "warning" to "Settings not saved"
        configBanner?.let { items += "warning" to it }
        val (kind, text) = items.firstOrNull() ?: return null
        return Banner(text, kind, items.size - 1)
    }

    private fun nightNow(now: ZonedDateTime): Boolean {
        val on = cfg.nightEnabled
        return isNight(now.toLocalTime(), if (on) cfg.nightStart else null, if (on) cfg.nightEnd else null)
    }

    // --- time passes ---

    fun onTick(now: ZonedDateTime) {
        val t = clock()
        val state = alarm.tick(now)
        if (state == AlarmState.RINGING && mode != Mode.ALARM) {
            enterAlarm(t)
        } else if (mode == Mode.ALARM && state != AlarmState.RINGING) { // 10-min stop or test ring over
            leaveAlarm(now)
        }

        val until = controlsUntil
        if (mode == Mode.PHOTO && until != null && t >= until) controlsUntil = null
        val deadline = settingsDeadline
        if (mode == Mode.SETTINGS && deadline != null && t >= deadline) closeSettings(now, idle = true)

        if ((mode == Mode.DASHBOARD || mode == Mode.PHOTO) && night.shouldBeDark(now, state)) {
            dayMode = mode
            mode = Mode.DARK
            controlsUntil = null
        } else if (mode == Mode.NIGHT_CLOCK && night.shouldBeDark(now, state)) {
            mode = Mode.DARK
        }
        if ((mode == Mode.DARK || mode == Mode.NIGHT_CLOCK) && !nightNow(now)) mode = dayMode
    }

    private fun enterAlarm(t: Double) {
        alarmReturn = mode
        val deadline = settingsDeadline
        if (mode == Mode.SETTINGS && deadline != null) {
            settingsIdlePaused = maxOf(0.0, deadline - t)
            settingsDeadline = null
        }
        mode = Mode.ALARM
        alarmShownAt = t
        controlsUntil = null
    }

    private fun leaveAlarm(now: ZonedDateTime) {
        var back = alarmReturn
        if (back == Mode.SETTINGS) {
            settingsIdlePaused = null
            settingsDeadline = clock() + SETTINGS_IDLE_S
        } else if (back == Mode.DARK || back == Mode.NIGHT_CLOCK) {
            back = if (nightNow(now)) Mode.DARK else dayMode
        }
        mode = back
    }

    // --- taps ---

    /** [downAt] is the monotonic time the finger went down (defaults to now). */
    fun onTap(buttonId: String?, now: ZonedDateTime, downAt: Double? = null) {
        val t = clock()
        val down = downAt ?: t
        when (mode) {
            Mode.ALARM -> {
                // Touch guard: ignore any finger that went down before the alarm showed or during
                // its first second, even if it lifts later
                if (down < alarmShownAt + ALARM_GUARD_S) return
                when (buttonId) {
                    "snooze" -> alarm.snooze(now)
                    "dismiss" -> alarm.dismiss(now)
                    else -> return
                }
                leaveAlarm(now)
            }
            Mode.DARK -> {
                night.wake(now) // the waking tap is consumed
                mode = Mode.NIGHT_CLOCK
            }
            Mode.NIGHT_CLOCK -> {
                if (buttonId == "open_settings") openSettings(Mode.DARK) else night.wake(now)
            }
            Mode.DASHBOARD -> when (buttonId) {
                "to_photo" -> {
                    mode = Mode.PHOTO
                    dayMode = Mode.PHOTO
                    controlsUntil = null
                }
                "open_settings" -> openSettings(Mode.DASHBOARD)
                "stop_snooze" -> alarm.dismiss(now)
                "clear_banner" -> alarm.missedAt = null
            }
            Mode.PHOTO -> {
                if (buttonId == "to_dashboard") {
                    mode = Mode.DASHBOARD
                    dayMode = Mode.DASHBOARD
                    controlsUntil = null
                    return
                }
                if (buttonId == "clear_banner") alarm.missedAt = null // and, like any tap, show the bar
                if (buttonId == "prev" || buttonId == "next") photoStep = if (buttonId == "prev") -1 else 1
                controlsUntil = t + CONTROLS_S // any other tap shows / keeps the bar
            }
            Mode.SETTINGS -> {
                settingsDeadline = t + SETTINGS_IDLE_S
                when (buttonId) {
                    "done" -> closeSettings(now)
                    "keep_unsaved" -> keepUnsaved(now)
                    "test_alarm" -> {
                        alarm.test(now)
                        enterAlarm(t)
                    }
                    "page_frame" -> settingsPage = "frame"
                    "page_main" -> settingsPage = "main"
                    null -> Unit
                    else -> if (buttonId in EXTERNAL_REQUESTS) request = buttonId else applySetting(buttonId)
                }
            }
        }
    }

    // --- settings ---

    private fun openSettings(cameFrom: Mode) {
        settingsReturn = cameFrom
        mode = Mode.SETTINGS
        settingsPage = "main"
        saveError = null
        settingsDeadline = clock() + SETTINGS_IDLE_S
        settingsIdlePaused = null
    }

    private fun exitSettings(now: ZonedDateTime) {
        var back = settingsReturn
        if (back == Mode.DARK) back = if (nightNow(now)) Mode.DARK else dayMode
        mode = back
        settingsDeadline = null
        settingsIdlePaused = null
    }

    private fun closeSettings(now: ZonedDateTime, idle: Boolean = false) {
        val err = save(cfg)
        if (err != null) {
            log("settings: save failed: $err")
            saveError = err
            if (idle) keepUnsaved(now)
            return
        }
        saveError = null
        unsaved = false
        exitSettings(now)
    }

    private fun keepUnsaved(now: ZonedDateTime) {
        saveError = null
        unsaved = true
        exitSettings(now)
    }

    /** Changes cfg in place (the alarm and night schedule read it live) and returns it. */
    fun applySetting(buttonId: String): Config {
        val c = cfg
        val plus = buttonId.endsWith("plus")
        when {
            buttonId == "alarm_toggle" -> c.alarmEnabled = !c.alarmEnabled
            buttonId == "alarm_hour_minus" || buttonId == "alarm_hour_plus" ->
                c.alarmTime = shiftTime(c.alarmTime, if (plus) 60 else -60)
            buttonId == "alarm_min_minus" || buttonId == "alarm_min_plus" -> {
                val m = Math.floorMod(c.alarmTime.minute + if (plus) 5 else -5, 60)
                c.alarmTime = LocalTime.of(c.alarmTime.hour, m) // wraps within the hour
            }
            buttonId.startsWith("day_") -> {
                val d = buttonId.substring(4).toInt()
                if (d in c.alarmDays) {
                    if (c.alarmDays.size > 1) c.alarmDays = c.alarmDays - d // never an empty repeat set
                } else {
                    c.alarmDays = c.alarmDays + d
                }
            }
            buttonId == "snooze_minus" || buttonId == "snooze_plus" ->
                c.snoozeMin = (c.snoozeMin + if (plus) 1 else -1).coerceIn(SNOOZE_RANGE)
            buttonId == "interval_minus" || buttonId == "interval_plus" -> c.intervalS = step(INTERVAL_STEPS, c.intervalS, plus)
            buttonId == "fade_toggle" -> c.fadeMs = if (c.fadeMs > 0) 0 else 1000
            buttonId == "clock_12" || buttonId == "clock_24" -> c.clock24h = buttonId == "clock_24"
            buttonId == "night_toggle" -> c.nightEnabled = !c.nightEnabled
            (buttonId.startsWith("night_start_") || buttonId.startsWith("night_end_")) && c.nightEnabled -> {
                val delta = if (plus) 30 else -30
                if (buttonId.startsWith("night_start_")) c.nightStart = shiftTime(c.nightStart, delta)
                else c.nightEnd = shiftTime(c.nightEnd, delta)
            }
            buttonId == "brightness_minus" || buttonId == "brightness_plus" ->
                c.brightness = (c.brightness + if (plus) 10 else -10).coerceIn(BRIGHTNESS_RANGE)
            buttonId == "units_f" || buttonId == "units_c" -> c.units = if (buttonId == "units_c") "celsius" else "fahrenheit"
            buttonId == "week_sun" || buttonId == "week_mon" -> c.weekStart = if (buttonId == "week_mon") "monday" else "sunday"
            buttonId == "corner_minus" || buttonId == "corner_plus" -> {
                val i = CORNERS.indexOf(c.overlayCorner).coerceAtLeast(0)
                c.overlayCorner = CORNERS[Math.floorMod(i + if (plus) 1 else -1, CORNERS.size)]
            }
            buttonId == "wake_minus" || buttonId == "wake_plus" -> c.wakeS = step(WAKE_STEPS, c.wakeS, plus)
            buttonId == "cache_minus" || buttonId == "cache_plus" -> c.cacheMb = step(CACHE_STEPS, c.cacheMb, plus)
            buttonId == "sound_default" -> c.alarmSound = null
            else -> log("settings: ignored $buttonId")
        }
        return c
    }

    /** Next step up or down from [value] (which may be off-step), staying put at the ends. */
    private fun step(steps: List<Int>, value: Int, up: Boolean): Int =
        if (up) steps.firstOrNull { it > value } ?: value else steps.lastOrNull { it < value } ?: value
}
