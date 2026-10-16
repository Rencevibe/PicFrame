package io.github.rencevibe.picframe.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.rencevibe.picframe.domain.Banner
import io.github.rencevibe.picframe.domain.Config
import io.github.rencevibe.picframe.domain.Weather
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders every screen with the mockups' data and compares it with docs/design/ (plan §7, M3).
 *
 * Tolerance, fixed before the first comparison: both images are averaged into 8×8-pixel blocks
 * (160×100); per block, the mean absolute RGB difference (0–255). A screen passes when the mean
 * over all blocks is ≤ [MAX_MEAN] and at most [MAX_BAD_SHARE] of blocks differ by more than
 * [BAD_BLOCK]. This tolerates font and icon redraws (Atkinson vs the mockups' Inter, original
 * icons per D4) but not misplaced or missing panels, text blocks or controls. Renders are written
 * to build/screenshots/ for visual sign-off (R7).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val measurer = TextMeasurer(createFontFamilyResolver(ctx), Density(1f), LayoutDirection.Ltr, 512)
    private val design = File("../../docs/design")
    private val out = File("build/screenshots").apply { mkdirs() }

    private val la = ZoneId.of("America/Los_Angeles")
    private val now = ZonedDateTime.of(2026, 10, 14, 12, 41, 0, 0, la)
    private val weather = Weather(64, 2, "partly-cloudy", "Partly cloudy", 72, 55, now)

    private fun mockup(name: String): Bitmap = BitmapFactory.decodeFile(File(design, "$name.png").path)
        ?: error("missing mockup docs/design/$name.png")

    private fun render(draw: Ink.() -> Unit): Bitmap {
        val img = ImageBitmap(1280, 800)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(1280f, 800f)) { Ink(this, measurer).draw() }
        return img.asAndroidBitmap()
    }

    private fun dashPhoto(): List<Layer> {
        val m = mockup("dashboard-snoozed") // the only dashboard mockup with an uncovered photo
        return listOf(Layer(Bitmap.createBitmap(m, 560, 144, 664, 600).asImageBitmap(), 1f))
    }

    private fun dashboardVm(banner: Banner? = null, snoozed: Boolean = false, photo: List<Layer> = dashPhoto()) = DashboardVm(
        time = "12:41", ampm = "PM", date = "Wednesday, October 14",
        todayItems = listOf(
            TodayRow("1:30 PM", "appointment", "Dentist — Dr. Okafor"),
            TodayRow("4:00 PM", "todo", "Pick up dry cleaning before six"),
            TodayRow("", "todo", "Water the plants"),
        ),
        more = 2, grid = monthGrid(LocalDate.of(2026, 10, 14), "sunday"),
        alarmText = "6:30 AM", alarmDays = "Mon–Fri", alarmOn = true, snoozed = snoozed, snoozeUntil = "6:39",
        countdown = "in 17h 49m", weather = weather, weatherAsOf = null, hasLocation = true,
        photo = photo, folderLabel = "Pictures/Frame", banner = banner, pressed = null,
    )

    data class Diff(val mean: Double, val badShare: Double)

    private fun compare(name: String, actual: Bitmap): Diff {
        FileOutputStream(File(out, "$name.png")).use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val expected = mockup(name)
        val bw = 160
        val bh = 100
        var total = 0.0
        var bad = 0
        for (by in 0 until bh) for (bx in 0 until bw) {
            var d = 0.0
            for (c in 0 until 3) {
                d += abs(block(actual, bx, by, c) - block(expected, bx, by, c))
            }
            d /= 3
            total += d
            if (d > BAD_BLOCK) bad++
        }
        val diff = Diff(total / (bw * bh), bad.toDouble() / (bw * bh))
        results[name] = diff
        println("SCREENSHOT $name mean=%.2f bad=%.2f%%".format(diff.mean, diff.badShare * 100))
        return diff
    }

    private fun block(b: Bitmap, bx: Int, by: Int, channel: Int): Double {
        var sum = 0
        for (y in by * 8 until by * 8 + 8) for (x in bx * 8 until bx * 8 + 8) {
            val p = b.getPixel(x, y)
            sum += when (channel) { 0 -> (p shr 16) and 0xff; 1 -> (p shr 8) and 0xff; else -> p and 0xff }
        }
        return sum / 64.0
    }

    private fun check(name: String, actual: Bitmap) {
        val d = compare(name, actual)
        assertTrue("$name: mean block diff ${d.mean} > $MAX_MEAN", d.mean <= MAX_MEAN)
        assertTrue("$name: ${d.badShare * 100}% of blocks differ by > $BAD_BLOCK", d.badShare <= MAX_BAD_SHARE)
    }

    @Test fun dashboard() = check("dashboard", render { dashboard(dashboardVm(banner = Banner("Missed alarm 6:30 AM", "missed", 0))) })

    @Test fun dashboardSnoozed() = check("dashboard-snoozed", render { dashboard(dashboardVm(snoozed = true)) })

    @Test fun dashboardEmpty() = check("dashboard-empty", render { dashboard(dashboardVm(photo = emptyList())) })

    @Test fun photo() {
        val bg = listOf(Layer(mockup("photo").asImageBitmap(), 1f))
        check("photo", render {
            photoMode(PhotoVm(bg, "12:41", "PM", "Wed, October 14", weather, "bottom-left", null, "", null, true, 0.4f))
        })
    }

    @Test fun alarm() = check("alarm", render {
        alarmScreen(AlarmVm("6:30", "AM", "Wednesday, October 14", 9, false, null), 1f)
    })

    @Test fun nightClock() = check("night-clock", render { nightClock(NightVm("2:14", "AM", "Alarm 6:30 AM · Mon–Fri", null)) })

    @Test fun settings() = check("settings", render { settings(SettingsVm(Config(), "main", null, null, "Pictures/Frame", "Device default")) })

    @Test fun settingsSaveError() = check("settings-save-error", render {
        settings(SettingsVm(Config(), "main", "SD card is read-only", null, "Pictures/Frame", "Device default"))
    })

    /** No mockup exists for these (G9 Frame page, first-run setup): rendered for the designer's review only. */
    @Test fun screensWithoutMockups() {
        val frame = render { settings(SettingsVm(Config(lat = 47.61, lon = -122.33, alarmSound = "x"), "frame", null, null, "Pictures/Frame", "Morning Glory")) }
        val setup = render { setup(SetupVm(null)) }
        for ((name, b) in listOf("settings-frame" to frame, "setup" to setup)) {
            FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Touch-target size on common screens with the uniform 1280×800 scale (input to D2). Calculated, not measured. */
    @Test fun layoutReport() {
        data class Device(val name: String, val w: Int, val h: Int, val inches: Double)
        val devices = listOf(
            Device("10.1\" tablet 1280×800", 1280, 800, 10.1),
            Device("10.5\" tablet 1920×1200", 1920, 1200, 10.5),
            Device("11\" tablet 2560×1600", 2560, 1600, 11.0),
            Device("8\" tablet 1280×800", 1280, 800, 8.0),
            Device("6.7\" phone 2400×1080", 2400, 1080, 6.7),
            Device("6.1\" phone 2340×1080", 2340, 1080, 6.1),
        )
        val sb = StringBuilder("| Screen | Scale | 64-unit target (mm) | ≥ 7.6 mm (48 dp)? | Side bars (px) | Top/bottom bars (px) |\n|---|---|---|---|---|---|\n")
        for (d in devices) {
            val scale = minOf(d.w / W.toDouble(), d.h / H.toDouble())
            val ppi = sqrt(d.w.toDouble() * d.w + d.h.toDouble() * d.h) / d.inches
            val mm = 64 * scale / ppi * 25.4
            val side = ((d.w - W * scale) / 2).toInt()
            val top = ((d.h - H * scale) / 2).toInt()
            sb.append("| ${d.name} | %.2f | %.1f | %s | %d | %d |\n".format(scale, mm, if (mm >= 7.6) "yes" else "**no**", side, top))
        }
        File("build/reports").mkdirs()
        File("build/reports/layout-report.md").writeText(sb.toString())
        println("LAYOUT\n$sb")
    }

    companion object {
        const val MAX_MEAN = 8.0
        const val BAD_BLOCK = 48.0
        const val MAX_BAD_SHARE = 0.03
        val results = linkedMapOf<String, Diff>()
    }
}
