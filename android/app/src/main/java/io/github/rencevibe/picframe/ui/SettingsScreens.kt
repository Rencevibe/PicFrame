package io.github.rencevibe.picframe.ui

import io.github.rencevibe.picframe.domain.BRIGHTNESS_RANGE
import io.github.rencevibe.picframe.domain.CACHE_STEPS
import io.github.rencevibe.picframe.domain.Config
import io.github.rencevibe.picframe.domain.INTERVAL_STEPS
import io.github.rencevibe.picframe.domain.SNOOZE_RANGE
import io.github.rencevibe.picframe.domain.WAKE_STEPS
import io.github.rencevibe.picframe.domain.fmtClock
import io.github.rencevibe.picframe.domain.fmtHour
import io.github.rencevibe.picframe.domain.fmtInterval

/** What the Settings screens show besides the config itself. */
data class SettingsVm(
    val cfg: Config,
    val page: String, // "main" | "frame"
    val saveError: String?,
    val pressed: String?,
    val folderLabel: String, // picked folder's name, or "No folder chosen"
    val soundLabel: String, // alarm tone title, or "Device default"
)

private const val CARD_W = 596f
private const val CARD_H = 648f
private const val CARD_Y = 120f
private const val ALARM_X = 32f
private const val DISPLAY_X = 652f
private const val ROW_Y = CARD_Y + 64
private const val ROW_H = 80f
private const val REPEAT_H = 128f
private const val STEPPER_W = 272f
private const val SEGMENT_W = 192f
private const val TEST_W = 200f
private const val DAYS_W = 544f
private val DAY_LABELS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/** Settings, Alarm + Display cards (port of settings_screen.render) or the Frame card (D3/G9). */
fun Ink.settings(vm: SettingsVm): List<Hit> {
    scope.drawRect(Palette.BG)
    val out = mutableListOf<Hit>()
    header(vm, out)
    if (vm.page == "frame") {
        card(ALARM_X, "Photos & weather")
        card(DISPLAY_X, "Frame")
        frameCards(vm, out)
    } else {
        card(ALARM_X, "Alarm")
        card(DISPLAY_X, "Display")
        alarmCard(vm, out)
        displayCard(vm, out)
    }
    return out
}

private fun Ink.header(vm: SettingsVm, out: MutableList<Hit>) {
    text("Settings", Face.INTER_SEMIBOLD, 32, Palette.TEXT, 32f, 74f, "ls")
    val err = vm.saveError
    if (err != null) {
        val strip = rectOf(200, 32, 696, 64)
        rect(Palette.PANEL, strip.left, strip.top, strip.width, strip.height, 16f)
        rect(Palette.HIGHLIGHT, strip.left, strip.top, 6f, strip.height, 3f)
        fitText("Couldn't save settings: $err.", Face.UI, 20, Palette.TEXT, strip.left + 24, strip.top + 20, strip.width - 48, "lm")
        text("Your previous settings are still saved.", Face.UI, 18, Palette.SECONDARY, strip.left + 24, strip.top + 46, "lm")
        button(rectOf(912, 32, 160, 64), "keep_unsaved", vm.pressed, label = "Keep for now", size = 20)?.let { out += it }
    } else {
        text("Saved when you tap Done", Face.UI, 18, Palette.SECONDARY, 32f, 100f, "ls")
        // Page switch: "Frame" holds the settings the Pi kept in config.toml (G9)
        val (label, action) = if (vm.page == "frame") "‹ Alarm & display" to "page_main" else "More settings ›" to "page_frame"
        button(rectOf(812, 32, 260, 64), action, vm.pressed, label = label, size = 22)?.let { out += it }
    }
    button(
        rectOf(1088, 32, 160, 64), "done", vm.pressed, label = if (err != null) "Retry" else "Done",
        style = Style.ACCENT, face = Face.INTER_SEMIBOLD, size = 24,
    )?.let { out += it }
}

private fun Ink.card(x: Float, title: String) {
    rect(Palette.PANEL, x, CARD_Y, CARD_W, CARD_H, 24f)
    text(title, Face.INTER_SEMIBOLD, 28, Palette.TEXT, x + 24, CARD_Y + 52, "ls")
}

/** Draws labels and dividers; returns (control right edge, control top) per row. */
private fun Ink.rows(x: Float, labels: List<String>): List<Pair<Float, Float>> {
    var y = ROW_Y
    return labels.mapIndexed { i, label ->
        val repeat = label == "Repeat"
        val h = if (repeat) REPEAT_H else ROW_H
        if (i > 0) line(Palette.DIVIDER, x + 24, y, x + CARD_W - 25, y)
        text(label, Face.UI, 24, Palette.TEXT, x + 24, if (repeat) y + 28 else y + h / 2, "lm")
        val pos = (x + CARD_W - 24) to (if (repeat) y + 56 else y + 8)
        y += h
        pos
    }
}

private fun Ink.stepperAt(
    pos: Pair<Float, Float>, value: String, minus: String, plus: String, vm: SettingsVm, a11y: String,
    loOk: Boolean = true, hiOk: Boolean = true, enabled: Boolean = true,
) = stepper(
    rectOf(pos.first - STEPPER_W, pos.second, STEPPER_W, 64), value, minus, plus, vm.pressed, a11y,
    minusEnabled = enabled && loOk, plusEnabled = enabled && hiOk, valueEnabled = enabled,
)

private fun Ink.toggleAt(pos: Pair<Float, Float>, on: Boolean, action: String, vm: SettingsVm, a11y: String) =
    toggle(rectOf(pos.first - 96, pos.second + 8, 88, 48), on, action, vm.pressed, a11y)

private fun Ink.alarmCard(vm: SettingsVm, out: MutableList<Hit>) {
    val c = vm.cfg
    val r = rows(ALARM_X, listOf("Alarm", "Hour", "Minute", "Repeat", "Snooze", "Test alarm"))
    out += toggleAt(r[0], c.alarmEnabled, "alarm_toggle", vm, "Alarm")
    out += stepperAt(r[1], fmtHour(c.alarmTime.hour, c.clock24h), "alarm_hour_minus", "alarm_hour_plus", vm, "Alarm hour")
    out += stepperAt(r[2], ":" + c.alarmTime.minute.toString().padStart(2, '0'), "alarm_min_minus", "alarm_min_plus", vm, "Alarm minute")
    val order = if (c.weekStart == "monday") (0..6).toList() else listOf(6, 0, 1, 2, 3, 4, 5)
    order.forEachIndexed { i, day ->
        out += dayChip(rectOf(r[3].first - DAYS_W + i * 80, r[3].second, 64, 64), DAY_LABELS[day], day in c.alarmDays, "day_$day", vm.pressed)
    }
    out += stepperAt(r[4], "${c.snoozeMin} min", "snooze_minus", "snooze_plus", vm, "Snooze",
        loOk = c.snoozeMin > SNOOZE_RANGE.first, hiOk = c.snoozeMin < SNOOZE_RANGE.last)
    button(rectOf(r[5].first - TEST_W, r[5].second, TEST_W, 64), "test_alarm", vm.pressed, label = "Play 10 s",
        icon = "bell", style = Style.OUTLINE, size = 22, a11y = "Test alarm, play 10 seconds")?.let { out += it }
}

private fun Ink.displayCard(vm: SettingsVm, out: MutableList<Hit>) {
    val c = vm.cfg
    val r = rows(DISPLAY_X, listOf("Photo interval", "Fade", "Clock", "Night mode", "Night starts", "Night ends", "Brightness"))
    out += stepperAt(r[0], fmtInterval(c.intervalS), "interval_minus", "interval_plus", vm, "Photo interval",
        loOk = c.intervalS > INTERVAL_STEPS.first(), hiOk = c.intervalS < INTERVAL_STEPS.last())
    out += toggleAt(r[1], c.fadeMs > 0, "fade_toggle", vm, "Fade")
    out += segmented(rectOf(r[2].first - SEGMENT_W, r[2].second, SEGMENT_W, 64),
        listOf("12 h" to "clock_12", "24 h" to "clock_24"), if (c.clock24h) 1 else 0, vm.pressed)
    out += toggleAt(r[3], c.nightEnabled, "night_toggle", vm, "Night mode")
    out += stepperAt(r[4], fmtClock(c.nightStart, c.clock24h), "night_start_minus", "night_start_plus", vm, "Night starts", enabled = c.nightEnabled)
    out += stepperAt(r[5], fmtClock(c.nightEnd, c.clock24h), "night_end_minus", "night_end_plus", vm, "Night ends", enabled = c.nightEnabled)
    out += stepperAt(r[6], "${c.brightness} %", "brightness_minus", "brightness_plus", vm, "Brightness",
        loOk = c.brightness > BRIGHTNESS_RANGE.first, hiOk = c.brightness < BRIGHTNESS_RANGE.last)
}

private val CORNER_LABELS = mapOf(
    "top-left" to "Top left", "top-right" to "Top right", "bottom-left" to "Bottom left", "bottom-right" to "Bottom right",
)

/** Settings that only existed in config.toml on the Pi (G9), plus the alarm tone (D4). */
private fun Ink.frameCards(vm: SettingsVm, out: MutableList<Hit>) {
    val c = vm.cfg
    val left = rows(ALARM_X, listOf("Photo folder", "Location", "Units", "Alarm sound", ""))
    rowButton(left[0], vm.folderLabel, "pick_folder", vm, "Choose photo folder")?.let { out += it }
    val loc = if (c.lat != null && c.lon != null) "%.2f, %.2f".format(java.util.Locale.ROOT, c.lat, c.lon) else "Not set"
    rowButton(left[1], loc, "edit_location", vm, "Set location")?.let { out += it }
    out += segmented(rectOf(left[2].first - SEGMENT_W, left[2].second, SEGMENT_W, 64),
        listOf("°F" to "units_f", "°C" to "units_c"), if (c.units == "celsius") 1 else 0, vm.pressed)
    rowButton(left[3], vm.soundLabel, "pick_sound", vm, "Choose alarm sound")?.let { out += it }
    if (c.alarmSound != null) {
        button(rectOf(left[4].first - 272, left[4].second, 272, 64), "sound_default", vm.pressed,
            label = "Use device default", size = 22)?.let { out += it }
    }

    val right = rows(DISPLAY_X, listOf("Week starts", "Clock corner", "Night clock shows", "Photo cache"))
    out += segmented(rectOf(right[0].first - SEGMENT_W, right[0].second, SEGMENT_W, 64),
        listOf("Sun" to "week_sun", "Mon" to "week_mon"), if (c.weekStart == "monday") 1 else 0, vm.pressed)
    out += stepperAt(right[1], CORNER_LABELS[c.overlayCorner] ?: c.overlayCorner, "corner_minus", "corner_plus", vm, "Clock corner")
    out += stepperAt(right[2], fmtInterval(c.wakeS), "wake_minus", "wake_plus", vm, "Night clock duration",
        loOk = c.wakeS > WAKE_STEPS.first(), hiOk = c.wakeS < WAKE_STEPS.last())
    out += stepperAt(right[3], "${c.cacheMb} MB", "cache_minus", "cache_plus", vm, "Photo cache size",
        loOk = c.cacheMb > CACHE_STEPS.first(), hiOk = c.cacheMb < CACHE_STEPS.last())
}

/** A raised value button at the right of a row, with a chevron: opens a picker or dialog. */
private fun Ink.rowButton(pos: Pair<Float, Float>, value: String, action: String, vm: SettingsVm, a11y: String): Hit? {
    val r = rectOf(pos.first - 300, pos.second, 300, 64)
    val hit = button(r, action, vm.pressed, a11y = "$a11y, currently $value")
    fitText(value, Face.UI, 22, Palette.TEXT, r.left + 20, r.center.y, r.width - 72, "lm")
    Icons.draw(this, "chevron-forward", r.right - 44, r.center.y - 16, 32, Palette.SECONDARY)
    return hit
}

// --- First-run setup (M4) ---

data class SetupVm(val pressed: String?)

/** First run: pick the photo folder, or skip (plan §5). Alarm permission steps arrive with the
 *  alarm path, after M1. */
fun Ink.setup(vm: SetupVm): List<Hit> {
    scope.drawRect(Palette.BG)
    val panel = rectOf(240, 160, 800, 480)
    rect(Palette.PANEL, panel.left, panel.top, panel.width, panel.height, 24f)
    text("Welcome to PicFrame", Face.INTER_SEMIBOLD, 40, Palette.TEXT, W / 2, 240f, "mm")
    text("Choose the folder your photos are in.", Face.UI, 24, Palette.TEXT, W / 2, 320f, "mm")
    text("PicFrame also reads todo.toml from that folder.", Face.UI, 20, Palette.SECONDARY, W / 2, 360f, "mm")
    text("Add photos to it from any app or a computer.", Face.UI, 20, Palette.SECONDARY, W / 2, 392f, "mm")
    return listOfNotNull(
        button(rectOf(400, 460, 480, 80), "setup_pick_folder", vm.pressed, label = "Choose photo folder",
            style = Style.ACCENT, face = Face.INTER_SEMIBOLD, size = 28, radius = 20f),
        button(rectOf(520, 556, 240, 64), "setup_skip", vm.pressed, label = "Skip for now", size = 22),
    )
}
