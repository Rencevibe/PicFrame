package io.github.rencevibe.picframe.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

const val MIN_HIT = 64f

/** A touch target in design pixels: [rect] is hit-tested, [label] is its accessibility name. */
data class Hit(val rect: Rect, val action: String, val label: String)

/** At least 64×64, grown around the center (spec "Touch rules"). */
fun hitArea(r: Rect, minW: Float = MIN_HIT, minH: Float = MIN_HIT): Rect {
    val dx = maxOf(0f, minW - r.width) / 2
    val dy = maxOf(0f, minH - r.height) / 2
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

fun rectOf(x: Number, y: Number, w: Number, h: Number) =
    Rect(x.toFloat(), y.toFloat(), x.toFloat() + w.toFloat(), y.toFloat() + h.toFloat())

enum class Style { RAISED, ACCENT, OUTLINE, OUTLINE_RAISED, PLAIN }

/** Port of widgets.Button. Draws and returns its [Hit] (null when disabled). */
fun Ink.button(
    r: Rect, action: String, pressed: String?, label: String? = null, icon: String? = null,
    style: Style = Style.RAISED, enabled: Boolean = true, face: Face = Face.UI, size: Int = 24,
    radius: Float = 16f, a11y: String? = null,
): Hit? {
    val down = pressed == action
    val (fill, fg) = when {
        !enabled -> null to Palette.SECONDARY
        style == Style.ACCENT -> (if (down) Palette.ACCENT_PRESSED else Palette.ACCENT) to Palette.BG
        style == Style.RAISED || style == Style.OUTLINE_RAISED -> (if (down) Palette.PRESSED else Palette.RAISED) to Palette.TEXT
        else -> (if (down) Palette.PRESSED else null) to Palette.TEXT
    }
    if (fill != null) rect(fill, r.left, r.top, r.width, r.height, radius)
    if (enabled && (style == Style.OUTLINE || style == Style.OUTLINE_RAISED)) {
        rect(Palette.TEXT, r.left, r.top, r.width, r.height, radius, stroke = 2f)
    }
    val cx = r.center.x
    val cy = r.center.y
    when {
        icon != null && label != null -> {
            val total = 32 + 12 + width(label, face, size)
            val x = cx - total / 2
            Icons.draw(this, icon, x, cy - 16, 32, fg)
            text(label, face, size, fg, x + 44, cy, "lm")
        }
        icon != null -> {
            val s = if (minOf(r.width, r.height) >= 64) 48 else 32
            Icons.draw(this, icon, cx - s / 2, cy - s / 2, s, fg)
        }
        label != null -> text(label, face, size, fg, cx, cy, "mm")
    }
    return if (enabled) Hit(hitArea(r), action, a11y ?: label ?: action) else null
}

/** Pill 88×48 (knob right = on) inside a 104×64 hit area. */
fun Ink.toggle(r: Rect, on: Boolean, action: String, pressed: String?, a11y: String): Hit {
    val down = pressed == action
    val h = r.height
    val (fill, knob, kx) = if (on) {
        Triple(if (down) Palette.ACCENT_PRESSED else Palette.ACCENT, Palette.BG, r.right - h / 2)
    } else {
        Triple(if (down) Palette.PRESSED else Palette.RAISED, Palette.SECONDARY, r.left + h / 2)
    }
    rect(fill, r.left, r.top, r.width, h, h / 2)
    circle(knob, kx, r.center.y, h / 2 - 6)
    return Hit(hitArea(r, r.width + 16), action, "$a11y ${if (on) "on" else "off"}")
}

/** [−] value [+]: 64×64 buttons around the value (272×64 overall). */
fun Ink.stepper(
    r: Rect, value: String, minus: String, plus: String, pressed: String?, a11y: String,
    minusEnabled: Boolean = true, plusEnabled: Boolean = true, valueEnabled: Boolean = true,
): List<Hit> {
    val out = listOfNotNull(
        button(rectOf(r.left, r.top, 64, 64), minus, pressed, icon = "minus", enabled = minusEnabled, a11y = "$a11y decrease"),
        button(rectOf(r.right - 64, r.top, 64, 64), plus, pressed, icon = "plus", enabled = plusEnabled, a11y = "$a11y increase"),
    )
    text(value, Face.INTER_SEMIBOLD, 28, if (valueEnabled) Palette.TEXT else Palette.SECONDARY, r.center.x, r.center.y, "mm")
    return out
}

/** Side-by-side options; the selected one in accent. */
fun Ink.segmented(r: Rect, options: List<Pair<String, String>>, selected: Int, pressed: String?): List<Hit> {
    rect(Palette.RAISED, r.left, r.top, r.width, r.height, 16f)
    val w = r.width / options.size
    return options.mapIndexed { i, (label, action) ->
        val part = rectOf(r.left + i * w, r.top, w, r.height)
        val down = pressed == action
        if (i == selected) {
            rect(if (down) Palette.ACCENT_PRESSED else Palette.ACCENT, part.left, part.top, part.width, part.height, 16f)
            text(label, Face.INTER_SEMIBOLD, 22, Palette.BG, part.center.x, part.center.y, "mm")
        } else {
            if (down) rect(Palette.PRESSED, part.left, part.top, part.width, part.height, 16f)
            text(label, Face.INTER_MEDIUM, 22, Palette.SECONDARY, part.center.x, part.center.y, "mm")
        }
        Hit(part, action, label + if (i == selected) " selected" else "")
    }
}

/** 64×64 round day toggle: on = accent fill, off = secondary outline. */
fun Ink.dayChip(r: Rect, label: String, on: Boolean, action: String, pressed: String?): Hit {
    val c = r.center
    val rad = r.width / 2
    if (on) {
        circle(if (pressed == action) Palette.ACCENT_PRESSED else Palette.ACCENT, c.x, c.y, rad)
        text(label, Face.UI_BOLD, 20, Palette.BG, c.x, c.y, "mm")
    } else {
        if (pressed == action) circle(Palette.PRESSED, c.x, c.y, rad)
        circle(Palette.SECONDARY, c.x, c.y, rad - 1, stroke = 2f)
        text(label, Face.UI, 20, Palette.SECONDARY, c.x, c.y, "mm")
    }
    return Hit(hitArea(r), action, "$label ${if (on) "on" else "off"}")
}

/** Text helpers shared by screens. */
internal fun Ink.fitText(s: String, face: Face, size: Int, color: Color, x: Float, y: Float, maxW: Float, anchor: String = "lt") =
    text(fit(s, face, size, maxW), face, size, color, x, y, anchor)
