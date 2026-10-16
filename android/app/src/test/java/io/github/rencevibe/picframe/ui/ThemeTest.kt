package io.github.rencevibe.picframe.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Port of the theme checks in tests/test_ui_render.py: contrast pairs, bundled fonts, digit cells. */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    private val measurer = TextMeasurer(
        createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr, 64,
    )

    private fun ink(block: Ink.() -> Unit) {
        val img = ImageBitmap(64, 64)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(64f, 64f)) { Ink(this, measurer).block() }
    }

    @Test fun themeContrast() {
        for ((fg, bg, name) in Palette.TEXT_PAIRS) assertTrue("$name: ${Palette.contrast(fg, bg)}", Palette.contrast(fg, bg) >= 4.5)
        for ((fg, bg, name) in Palette.ICON_PAIRS) assertTrue("$name: ${Palette.contrast(fg, bg)}", Palette.contrast(fg, bg) >= 3.0)
        assertEquals(21.0, Palette.contrast(Palette.WHITE, Palette.BLACK), 0.01)
    }

    @Test fun bundledFontsLoadAndDiffer() = ink {
        // Same string, different faces: different widths means each bundled font actually loaded
        val widths = Face.entries.map { width("Wednesday 0123", it, 40) }
        assertEquals(widths.size, widths.toSet().size)
        assertNotEquals(width("0", Face.INTER, 40), width("0", Face.UI, 40))
    }

    @Test fun digitCellsKeepTheClockStill() = ink {
        // "11:11" and "00:00" occupy the same width (spec: fixed-width digit cells)
        assertEquals(digitsWidth("00:00", Face.INTER_SEMIBOLD, 96), digitsWidth("11:11", Face.INTER_SEMIBOLD, 96), 0.01f)
    }

    @Test fun fitAddsAnEllipsis() = ink {
        val long = "Pick up dry cleaning before six"
        val s = fit(long, Face.UI, 24, 300f)
        assertTrue(s.endsWith("…") && width(s, Face.UI, 24) <= 300f)
        assertEquals("Short", fit("Short", Face.UI, 24, 300f))
    }

    @Test fun everyIconDraws() = ink {
        for (name in Icons.WEATHER + Icons.UI) Icons.draw(this, name, 0f, 0f, 48)
    }

    @Test fun touchTargetsAreAtLeast64() {
        assertEquals(64f, hitArea(rectOf(0, 0, 32, 32)).width, 0f)
        assertEquals(104f, hitArea(rectOf(0, 0, 88, 48), 104f).width, 0f)
    }
}
