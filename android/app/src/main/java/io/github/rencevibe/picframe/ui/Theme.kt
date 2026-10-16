package io.github.rencevibe.picframe.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.rencevibe.picframe.R
import kotlin.math.pow

/** Design tokens from the spec "Colors" table (port of frame/ui/theme.py). */
object Palette {
    val BG = Color(0xFF0E1726)
    val PANEL = Color(0xFF172538)
    val RAISED = Color(0xFF22364B)
    val PRESSED = Color(0xFF2D4862)
    val DIVIDER = Color(0xFF2B4054)
    val TEXT = Color(0xFFF4F7FB)
    val SECONDARY = Color(0xFFB7C6D6)
    val ACCENT = Color(0xFF43D9C3)
    val ACCENT_PRESSED = Color(0xFF61E5D2)
    val HIGHLIGHT = Color(0xFFF2B66D)
    val ALARM = Color(0xFFFF766F)
    val NIGHT = Color(0xFF929DAA)
    val NIGHT_PRESSED = Color(26, 30, 36) // pressed feedback on the night clock that stays dark
    val BLACK = Color.Black
    val WHITE = Color.White

    /** fg at [alpha] over bg, as an opaque color. */
    fun blend(fg: Color, bg: Color, alpha: Float) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    val CHIP_ON_WHITE = blend(BG, WHITE, 0.8f) // photo chips: bg at 80% over the brightest photo
    val BANNER_ON_WHITE = blend(BG, WHITE, 0.9f)
    val BANNER_PRESSED_ON_WHITE = blend(PRESSED, WHITE, 0.9f)

    /** Every text pair the screens draw, including pressed and disabled states (spec "Contrast"). */
    val TEXT_PAIRS = listOf(
        Triple(TEXT, BG, "text on bg"), Triple(TEXT, PANEL, "text on panel"), Triple(TEXT, RAISED, "text on raised"),
        Triple(TEXT, PRESSED, "text on pressed"), Triple(SECONDARY, BG, "secondary on bg"),
        Triple(SECONDARY, PANEL, "secondary on panel"), Triple(SECONDARY, RAISED, "secondary on raised / disabled"),
        Triple(SECONDARY, PRESSED, "secondary on pressed"), Triple(BG, ACCENT, "bg on accent"),
        Triple(BG, ACCENT_PRESSED, "bg on pressed accent"), Triple(ALARM, BG, "ALARM caption"),
        Triple(NIGHT, BLACK, "night clock"), Triple(NIGHT, NIGHT_PRESSED, "night clock pressed"),
        Triple(TEXT, CHIP_ON_WHITE, "text on photo chip"), Triple(SECONDARY, CHIP_ON_WHITE, "secondary on photo chip"),
        Triple(TEXT, BANNER_ON_WHITE, "banner text over photo"),
        Triple(TEXT, BANNER_PRESSED_ON_WHITE, "pressed banner text over photo"),
    )

    /** Icons, markers, outlines and stripes (≥ 3:1). */
    val ICON_PAIRS = listOf(
        Triple(TEXT, RAISED, "icon on raised"), Triple(TEXT, PRESSED, "icon on pressed"),
        Triple(SECONDARY, RAISED, "toggle-off knob"), Triple(SECONDARY, PANEL, "disabled icon / day-chip outline"),
        Triple(ACCENT, PANEL, "appointment marker / on dot"), Triple(HIGHLIGHT, PANEL, "to-do marker"),
        Triple(ALARM, RAISED, "snoozed bell"), Triple(ALARM, PRESSED, "snoozed bell pressed"),
        Triple(ALARM, BANNER_ON_WHITE, "missed-alarm stripe"), Triple(HIGHLIGHT, BANNER_ON_WHITE, "warning stripe"),
        Triple(BG, ACCENT, "toggle-on knob"), Triple(SECONDARY, PRESSED, "close icon on pressed fill"),
        Triple(ALARM, CHIP_ON_WHITE, "missed-alarm bell on photo chip"),
        Triple(SECONDARY, CHIP_ON_WHITE, "close icon on photo chip"),
        Triple(SECONDARY, BANNER_ON_WHITE, "banner close icon over photo"),
        Triple(SECONDARY, BANNER_PRESSED_ON_WHITE, "pressed banner close icon over photo"),
    )

    private fun lin(c: Float): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** WCAG 2.1 contrast ratio. */
    fun contrast(fg: Color, bg: Color): Double {
        fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        val (a, b) = listOf(lum(fg), lum(bg)).sortedDescending()
        return (a + 0.05) / (b + 0.05)
    }
}

/** Fonts (spec "Type scale"): Inter for clock, numbers and headings; Atkinson Hyperlegible for UI text. */
enum class Face(val family: FontFamily, val weight: FontWeight) {
    INTER(Fonts.inter, FontWeight.Normal),
    INTER_MEDIUM(Fonts.inter, FontWeight.Medium),
    INTER_SEMIBOLD(Fonts.inter, FontWeight.SemiBold),
    UI(Fonts.ui, FontWeight.Normal),
    UI_BOLD(Fonts.ui, FontWeight.Bold),
}

object Fonts {
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
    val ui = FontFamily(
        Font(R.font.atkinsonhyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinsonhyperlegible_bold, FontWeight.Bold),
    )
}
