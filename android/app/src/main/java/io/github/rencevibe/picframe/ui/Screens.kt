package io.github.rencevibe.picframe.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.rencevibe.picframe.domain.Banner
import io.github.rencevibe.picframe.domain.Weather
import java.time.LocalDate

const val W = 1280f
const val H = 800f

/** An image to draw with an opacity (two layers while a fade runs). */
data class Layer(val image: ImageBitmap, val alpha: Float)

data class TodayRow(val time: String, val kind: String, val title: String)

data class MonthGrid(val weekdays: List<String>, val cells: List<Int?>, val today: Int)

private val WEEKDAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/** 6×7 calendar cells (always 6 rows), weekday labels in display order (port of month_grid). */
fun monthGrid(today: LocalDate, weekStart: String): MonthGrid {
    val first = if (weekStart == "sunday") 6 else 0 // Mon=0
    val start = today.withDayOfMonth(1)
    val lead = Math.floorMod(start.dayOfWeek.value - 1 - first, 7)
    val cells = MutableList<Int?>(42) { null }
    for (d in 1..today.lengthOfMonth()) cells[lead + d - 1] = d
    return MonthGrid(WEEKDAYS.drop(first) + WEEKDAYS.take(first), cells, today.dayOfMonth)
}

data class DashboardVm(
    val time: String, val ampm: String?, val date: String,
    val todayItems: List<TodayRow>, val more: Int, val grid: MonthGrid,
    val alarmText: String, val alarmDays: String, val alarmOn: Boolean, val snoozed: Boolean,
    val snoozeUntil: String?, val countdown: String,
    val weather: Weather?, val weatherAsOf: String?, val hasLocation: Boolean,
    val photo: List<Layer>, val folderLabel: String,
    val banner: Banner?, val pressed: String?,
)

private object Dash {
    val LEFT = rectOf(32, 32, 480, 736)
    val RIGHT = rectOf(536, 32, 712, 736)
    val STRIP = rectOf(560, 56, 576, 64)
    val TO_PHOTO = rectOf(1160, 56, 64, 64)
    val PHOTO = rectOf(560, 144, 664, 600)
    val BANNER = rectOf(576, 664, 632, 64)
    val ALARM_ROW = rectOf(56, 672, 432, 72)
    val ALARM_ROW_SNOOZED = rectOf(56, 672, 336, 72)
    val STOP_SNOOZE = rectOf(416, 672, 72, 72)
    const val X0 = 56f
    const val X1 = 488f
}

/** Empty-folder message (plan §5: no rsync hint on Android). */
const val EMPTY_HINT = "Choose a photo folder in Settings"

fun Ink.dashboard(vm: DashboardVm): List<Hit> {
    scope.drawRect(Palette.BG)
    rect(Palette.PANEL, Dash.LEFT.left, Dash.LEFT.top, Dash.LEFT.width, Dash.LEFT.height, 24f)
    rect(Palette.PANEL, Dash.RIGHT.left, Dash.RIGHT.top, Dash.RIGHT.width, Dash.RIGHT.height, 24f)
    val hits = mutableListOf<Hit>()
    dashClock(vm)
    dashToday(vm)
    dashCalendar(vm.grid)
    hits += dashAlarmRow(vm)
    dashWeather(vm)
    button(Dash.TO_PHOTO, "to_photo", vm.pressed, icon = "expand", a11y = "Photo mode")?.let { hits += it }
    dashPhoto(vm)
    vm.banner?.let { hits += banner(it, vm.pressed) }
    return hits
}

private fun Ink.dashClock(vm: DashboardVm) {
    val baseline = 142f
    val w = digits(vm.time, Face.INTER_SEMIBOLD, 96, Palette.TEXT, Dash.X0, baseline)
    vm.ampm?.let { text(it, Face.INTER_MEDIUM, 28, Palette.SECONDARY, Dash.X0 + w + 12, baseline, "ls") }
    fitText(vm.date, Face.UI, 28, Palette.TEXT, Dash.X0, 196f, Dash.X1 - Dash.X0, "ls")
}

private fun Ink.dashToday(vm: DashboardVm) {
    text("TODAY", Face.INTER_SEMIBOLD, 18, Palette.SECONDARY, Dash.X0, 246f, "ls", tracking = 1.5f)
    val items = vm.todayItems.take(3)
    if (items.isEmpty()) text("Nothing else today", Face.UI, 24, Palette.SECONDARY, Dash.X0, 282f, "lm")
    items.forEachIndexed { i, item ->
        val cy = 260f + i * 44 + 22
        if (item.time.isNotEmpty()) text(item.time, Face.INTER_MEDIUM, 22, Palette.SECONDARY, Dash.X0, cy, "lm")
        if (item.kind == "appointment") circle(Palette.ACCENT, 168f, cy, 8f)
        else rect(Palette.HIGHLIGHT, 160f, cy - 8, 16f, 16f, 4f, stroke = 2f)
        fitText(item.title, Face.UI, 24, Palette.TEXT, 188f, cy, Dash.X1 - 188, "lm")
    }
    if (vm.more > 0) text("+${vm.more} more", Face.UI, 20, Palette.SECONDARY, Dash.X0, 404f, "lm")
}

private fun Ink.dashCalendar(grid: MonthGrid) {
    val col = (Dash.X1 - Dash.X0) / 7
    grid.weekdays.forEachIndexed { i, wd ->
        text(wd, Face.INTER_MEDIUM, 18, Palette.SECONDARY, Dash.X0 + col * (i + 0.5f), 454f, "mm")
    }
    grid.cells.forEachIndexed { idx, day ->
        if (day == null) return@forEachIndexed
        val cx = Dash.X0 + col * (idx % 7 + 0.5f)
        val cy = 468f + (idx / 7) * 30 + 15
        if (day == grid.today) {
            circle(Palette.ACCENT, cx, cy, 16f)
            text(day.toString(), Face.INTER_SEMIBOLD, 20, Palette.BG, cx, cy, "mm")
        } else {
            text(day.toString(), Face.INTER, 20, Palette.TEXT, cx, cy, "mm")
        }
    }
}

private fun splitAmPm(s: String): Pair<String, String> {
    val i = s.lastIndexOf(' ')
    val tail = if (i >= 0) s.substring(i + 1) else ""
    return if (tail == "AM" || tail == "PM") s.substring(0, i) to tail else s to ""
}

private fun Ink.dashAlarmRow(vm: DashboardVm): List<Hit> {
    val cy = Dash.ALARM_ROW.center.y
    if (vm.snoozed) {
        val r = Dash.ALARM_ROW_SNOOZED
        val row = button(r, "open_settings", vm.pressed, a11y = "Alarm settings")
        Icons.draw(this, "bell", 68f, cy - 16, 32, Palette.ALARM)
        fitText("Snoozed · rings ${vm.snoozeUntil}", Face.INTER_SEMIBOLD, 22, Palette.TEXT, 108f, cy, r.right - 44 - 108, "lm")
        Icons.draw(this, "chevron-forward", r.right - 42, cy - 16, 32, Palette.SECONDARY)
        val stop = button(Dash.STOP_SNOOZE, "stop_snooze", vm.pressed, icon = "close", a11y = "Cancel snooze")
        return listOfNotNull(row, stop)
    }
    val r = Dash.ALARM_ROW
    val row = button(r, "open_settings", vm.pressed, a11y = "Alarm settings")
    Icons.draw(this, "chevron-forward", r.right - 42, cy - 16, 32, Palette.SECONDARY)
    if (!vm.alarmOn) {
        Icons.draw(this, "bell", 68f, cy - 16, 32, Palette.SECONDARY)
        val t = text("Alarm off", Face.INTER_SEMIBOLD, 24, Palette.SECONDARY, 112f, cy, "lm")
        circle(Palette.SECONDARY, t.right + 16, cy, 6f, stroke = 2f)
        return listOfNotNull(row)
    }
    Icons.draw(this, "bell", 68f, cy - 16, 32, Palette.TEXT)
    val (hhmm, ampm) = splitAmPm(vm.alarmText)
    var t = text(hhmm, Face.INTER_SEMIBOLD, 28, Palette.TEXT, 112f, cy, "lm")
    if (ampm.isNotEmpty()) t = text(ampm, Face.INTER_MEDIUM, 18, Palette.SECONDARY, t.right + 6, cy + 2, "lm")
    var right = r.right - 52
    if (vm.countdown.isNotEmpty()) right = text(vm.countdown, Face.UI, 20, Palette.SECONDARY, right, cy, "rm").left
    val d = fitText(vm.alarmDays, Face.UI, 20, Palette.SECONDARY, t.right + 14, cy, maxOf(0f, right - 16 - 22 - (t.right + 14)), "lm")
    circle(Palette.ACCENT, d.right + 16, cy, 6f)
    return listOfNotNull(row)
}

private fun Ink.dashWeather(vm: DashboardVm) {
    val s = Dash.STRIP
    val cy = s.center.y
    val w = vm.weather
    if (w == null) {
        Icons.draw(this, "unknown", s.left, cy - 24, 48, Palette.SECONDARY)
        val msg = if (vm.hasLocation) "Weather unavailable" else "Set location in Settings"
        text(msg, Face.UI, 22, Palette.SECONDARY, s.left + 64, cy, "lm")
        return
    }
    Icons.draw(this, w.icon, s.left, cy - 24, 48)
    val t = text("${w.temp}°", Face.INTER_SEMIBOLD, 32, Palette.TEXT, s.left + 64, cy, "lm")
    val hl = text("H ${w.high}°  L ${w.low}°", Face.INTER_MEDIUM, 22, Palette.SECONDARY, s.right, cy, "rm")
    val label = w.label + (vm.weatherAsOf?.let { " · as of $it" } ?: "")
    fitText(label, Face.UI, 22, Palette.TEXT, t.right + 14, cy, hl.left - 16 - (t.right + 14), "lm")
}

private fun Ink.drawLayers(layers: List<Layer>, x: Float, y: Float, w: Float, h: Float) {
    for (l in layers) {
        scope.drawImage(
            l.image, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = androidx.compose.ui.unit.IntSize(l.image.width, l.image.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()), alpha = l.alpha,
        )
    }
}

private fun Ink.dashPhoto(vm: DashboardVm) {
    val p = Dash.PHOTO
    if (vm.photo.isNotEmpty()) {
        val clip = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(p, androidx.compose.ui.geometry.CornerRadius(16f)))
        }
        scope.clipPath(clip) { drawLayers(vm.photo, p.left, p.top, p.width, p.height) }
        return
    }
    rect(Palette.BG, p.left, p.top, p.width, p.height, 16f)
    val cx = p.center.x
    Icons.draw(this, "photo", cx - 24, 356f, 48, Palette.SECONDARY)
    text("No photos yet", Face.UI, 28, Palette.TEXT, cx, 440f, "mm")
    fitText(vm.folderLabel, Face.INTER, 20, Palette.SECONDARY, cx, 480f, p.width - 48, "mm")
    text(EMPTY_HINT, Face.UI, 20, Palette.SECONDARY, cx, 512f, "mm")
}

private fun Ink.shadowCard(x: Float, y: Float, w: Float, h: Float, radius: Float, fill: Color, alpha: Float) {
    for (i in 8 downTo 2 step 2) { // soft shadow, as the Pi draws it
        val g = 8f - i + 1f
        rect(Color.Black, x - 8 + g, y - 6 + g + 2, w + 16 - 2 * g, h + 14 - 2 * g, radius + 8, alpha = 14f / 255f)
    }
    rect(fill, x, y, w, h, radius, alpha = alpha)
}

private fun Ink.banner(b: Banner, pressed: String?): List<Hit> {
    val r = Dash.BANNER
    val missed = b.kind == "missed"
    val down = missed && pressed == "clear_banner"
    shadowCard(r.left, r.top, r.width, r.height, 16f, if (down) Palette.PRESSED else Palette.BG, 230f / 255f)
    rect(if (missed) Palette.ALARM else Palette.HIGHLIGHT, r.left, r.top, 6f, r.height, 3f)
    val label = b.text + if (b.more > 0) " · ${b.more} more" else ""
    val room = r.width - 26 - if (missed) 72 else 24
    fitText(label, Face.UI, 22, Palette.TEXT, r.left + 26, r.center.y, room, "lm")
    if (!missed) return emptyList()
    Icons.draw(this, "close", r.right - 56, r.center.y - 24, 48, Palette.SECONDARY)
    return listOf(Hit(r, "clear_banner", "Dismiss: $label"))
}

// --- Photo mode ---

data class PhotoVm(
    val photo: List<Layer>, val time: String, val ampm: String?, val date: String,
    val weather: Weather?, val corner: String, val missed: String?, val folderLabel: String,
    val pressed: String?, val controlsVisible: Boolean, val hideProgress: Float,
)

private const val MARGIN = 32f
private val OPPOSITE = mapOf(
    "top-left" to "bottom-right", "bottom-right" to "top-left",
    "top-right" to "bottom-left", "bottom-left" to "top-right",
)

private fun place(corner: String, w: Float, h: Float) = rectOf(
    if (corner.endsWith("left")) MARGIN else W - MARGIN - w,
    if (corner.startsWith("top")) MARGIN else H - MARGIN - h, w, h,
)

/** Where each corner chip goes (port of photo_mode.chip_rects). */
fun Ink.chipRects(vm: PhotoVm): Map<String, androidx.compose.ui.geometry.Rect> {
    var w = digitsWidth(vm.time, Face.INTER_SEMIBOLD, 64)
    vm.ampm?.let { w += 10 + width(it, Face.INTER_MEDIUM, 24) }
    val clockW = minOf(320f, maxOf(w, width(vm.date, Face.UI, 24)) + 48)
    val out = mutableMapOf("clock" to place(vm.corner, clockW, 128f))
    if (vm.weather != null) out["weather"] = place(OPPOSITE.getValue(vm.corner), 300f, 80f)
    vm.missed?.let {
        val freeTop = listOf("top-left", "top-right").first { c -> c != vm.corner && c != OPPOSITE[vm.corner] }
        val tw = width(missedText(it), Face.UI, 22)
        out["missed"] = place(freeTop, minOf(400f, 20 + 32 + 12 + tw + 16 + 32 + 16), 64f)
    }
    return out
}

private fun Ink.missedText(s: String) = fit(s, Face.UI, 22, 400f - 20 - 32 - 12 - 16 - 32 - 16)

fun Ink.photoMode(vm: PhotoVm): List<Hit> {
    if (vm.photo.isNotEmpty()) {
        scope.drawRect(Palette.BLACK)
        drawLayers(vm.photo, 0f, 0f, W, H)
    } else {
        scope.drawRect(Palette.BG)
        val cx = W / 2
        Icons.draw(this, "photo", cx - 24, 300f, 48, Palette.SECONDARY)
        text("No photos yet", Face.UI, 28, Palette.TEXT, cx, 384f, "mm")
        fitText(vm.folderLabel, Face.INTER, 20, Palette.SECONDARY, cx, 424f, 800f, "mm")
        text(EMPTY_HINT, Face.UI, 20, Palette.SECONDARY, cx, 456f, "mm")
    }
    val chips = chipRects(vm)
    val hits = mutableListOf<Hit>()
    chips.getValue("clock").let { r ->
        shadowCard(r.left, r.top, r.width, r.height, 20f, Palette.BG, 0.8f)
        val baseline = r.top + 20 + 58
        val w = digits(vm.time, Face.INTER_SEMIBOLD, 64, Palette.TEXT, r.left + 24, baseline)
        vm.ampm?.let { text(it, Face.INTER_MEDIUM, 24, Palette.SECONDARY, r.left + 24 + w + 10, baseline, "ls") }
        fitText(vm.date, Face.UI, 24, Palette.SECONDARY, r.left + 24, r.top + 108, r.width - 48, "ls")
    }
    chips["weather"]?.let { r ->
        val w = vm.weather!!
        shadowCard(r.left, r.top, r.width, r.height, 20f, Palette.BG, 0.8f)
        Icons.draw(this, w.icon, r.left + 20, r.center.y - 24, 48)
        text("${w.temp}°", Face.INTER_SEMIBOLD, 32, Palette.TEXT, r.left + 80, r.center.y, "lm")
        text("H ${w.high}° L ${w.low}°", Face.INTER_MEDIUM, 20, Palette.SECONDARY, r.right - 24, r.center.y, "rm")
    }
    chips["missed"]?.let { r ->
        shadowCard(r.left, r.top, r.width, r.height, 20f, Palette.BG, 0.8f)
        if (vm.pressed == "clear_banner") rect(Palette.PRESSED, r.left, r.top, r.width, r.height, 20f)
        Icons.draw(this, "bell", r.left + 20, r.center.y - 16, 32, Palette.ALARM)
        text(missedText(vm.missed!!), Face.UI, 22, Palette.TEXT, r.left + 64, r.center.y, "lm")
        Icons.draw(this, "close", r.right - 48, r.center.y - 16, 32, Palette.SECONDARY)
        hits += Hit(r, "clear_banner", "Dismiss: ${vm.missed}")
    }
    if (vm.controlsVisible) {
        val bar = rectOf(360, 680, 560, 96)
        shadowCard(bar.left, bar.top, bar.width, bar.height, 24f, Palette.BG, 0.8f)
        listOfNotNull(
            button(rectOf(376, 688, 120, 80), "prev", vm.pressed, icon = "chevron-left", a11y = "Previous photo"),
            button(rectOf(512, 688, 120, 80), "next", vm.pressed, icon = "chevron-right", a11y = "Next photo"),
            button(rectOf(648, 688, 256, 80), "to_dashboard", vm.pressed, label = "Dashboard", icon = "dashboard"),
        ).let { hits += it }
        val left = (1f - vm.hideProgress).coerceIn(0f, 1f)
        val full = bar.width - 32
        if (left > 0) rect(Palette.ACCENT, bar.left + 16, bar.bottom - 6, full * left, 4f, 2f)
    }
    return hits
}

// --- Alarm, night clock, dark ---

data class AlarmVm(val time: String, val ampm: String?, val date: String, val snoozeMin: Int, val isTest: Boolean, val pressed: String?)

fun Ink.alarmScreen(vm: AlarmVm, pulse: Float): List<Hit> {
    scope.drawRect(Palette.BG)
    val ring = Palette.blend(Palette.ALARM, Palette.BG, 0.4f + 0.6f * pulse.coerceIn(0f, 1f))
    rect(ring, 0f, 0f, W, H, 18f, stroke = 6f)
    text(if (vm.isTest) "TEST ALARM" else "ALARM", Face.INTER_SEMIBOLD, 24, Palette.ALARM, W / 2, 120f, "mm", tracking = 3f)
    bigClock(vm.time, vm.ampm, Face.INTER_SEMIBOLD, Palette.TEXT, Palette.SECONDARY, 312f)
    text(vm.date, Face.UI, 28, Palette.SECONDARY, W / 2, 362f, "mm")
    return listOfNotNull(
        button(rectOf(400, 440, 480, 144), "snooze", vm.pressed, label = "Snooze ${vm.snoozeMin} min",
            style = Style.ACCENT, face = Face.INTER_SEMIBOLD, size = 36, radius = 24f),
        button(rectOf(520, 640, 240, 120), "dismiss", vm.pressed, label = "Dismiss",
            style = Style.OUTLINE_RAISED, face = Face.INTER_SEMIBOLD, size = 32, radius = 24f),
    )
}

/** 160 px clock centered with its AM/PM (alarm and night clock). */
private fun Ink.bigClock(time: String, ampm: String?, face: Face, color: Color, ampmColor: Color, baseline: Float) {
    val clockW = digitsWidth(time, face, 160)
    val ampmW = ampm?.let { width(it, Face.INTER_MEDIUM, 40) + 16 } ?: 0f
    val x = ((W - clockW - ampmW) / 2).toInt().toFloat()
    digits(time, face, 160, color, x, baseline)
    ampm?.let { text(it, Face.INTER_MEDIUM, 40, ampmColor, x + clockW + 16, baseline, "ls") }
}

data class NightVm(val time: String, val ampm: String?, val alarmLabel: String, val pressed: String?)

fun Ink.nightClock(vm: NightVm): List<Hit> {
    scope.drawRect(Palette.BLACK)
    bigClock(vm.time, vm.ampm, Face.INTER_MEDIUM, Palette.NIGHT, Palette.NIGHT, 392f)
    val r = rectOf(440, 520, 400, 72)
    if (vm.pressed == "open_settings") rect(Palette.NIGHT_PRESSED, r.left, r.top, r.width, r.height, 16f)
    val label = fit(vm.alarmLabel, Face.UI, 24, r.width - 64)
    val lw = width(label, Face.UI, 24)
    val left = r.center.x - ((lw + 40) / 2).toInt()
    text(label, Face.UI, 24, Palette.NIGHT, left, r.center.y, "lm")
    Icons.draw(this, "chevron-forward", left + lw + 8, r.center.y - 16, 32, Palette.NIGHT)
    return listOf(Hit(r, "open_settings", "$label, open settings"))
}

fun Ink.dark(): List<Hit> {
    scope.drawRect(Palette.BLACK)
    return listOf(Hit(rectOf(0, 0, W, H), "", "Show night clock"))
}
