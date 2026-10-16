package io.github.rencevibe.picframe.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/**
 * Drawing helpers in design pixels (1280×800), the counterpart of the Pi's theme.text / fit /
 * digits. The [measurer] must use Density(1f), so 1.sp is one design pixel.
 */
class Ink(val scope: DrawScope, val measurer: TextMeasurer) {

    fun layout(s: String, face: Face, size: Int, tracking: Float = 0f): TextLayoutResult = measurer.measure(
        s,
        TextStyle(
            fontFamily = face.family,
            fontWeight = face.weight,
            fontSize = size.sp,
            letterSpacing = tracking.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(),
    )

    fun width(s: String, face: Face, size: Int): Float = layout(s, face, size).size.width.toFloat()

    /**
     * Draws [s] with [anchor] = horizontal (l/m/r) + vertical (t/m/s = baseline/b), as theme.text.
     * Returns the drawn rectangle.
     */
    fun text(
        s: String, face: Face, size: Int, color: Color, x: Float, y: Float,
        anchor: String = "lt", tracking: Float = 0f,
    ): Rect {
        if (s.isEmpty()) return Rect(x, y, x, y)
        val l = layout(s, face, size, tracking)
        val w = l.size.width.toFloat()
        val h = l.size.height.toFloat()
        val left = x - when (anchor[0]) { 'm' -> w / 2; 'r' -> w; else -> 0f }
        val top = y - when (anchor[1]) { 'm' -> h / 2; 's' -> l.firstBaseline; 'b' -> h; else -> 0f }
        scope.drawText(l, color, Offset(left, top))
        return Rect(left, top, left + w, top + h)
    }

    /** [s], shortened with an ellipsis so it is no wider than [maxW]. */
    fun fit(s: String, face: Face, size: Int, maxW: Float): String {
        if (width(s, face, size) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && width(t.trimEnd() + "…", face, size) > maxW) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    /** Width [digits] will draw. */
    fun digitsWidth(s: String, face: Face, size: Int): Float {
        val cell = width("0", face, size)
        return s.sumOf { (if (it.isDigit()) cell else width(it.toString(), face, size)).toDouble() }.toFloat()
    }

    /** Clock text with every digit centered in a cell as wide as "0", so it never jiggles.
     *  ([Face] fonts would allow tabular figures; cells keep the Pi's exact metrics.) */
    fun digits(s: String, face: Face, size: Int, color: Color, x: Float, baseline: Float): Float {
        val cell = width("0", face, size)
        var cx = x
        for (ch in s) {
            val w = if (ch.isDigit()) cell else width(ch.toString(), face, size)
            val gw = width(ch.toString(), face, size)
            text(ch.toString(), face, size, color, cx + (w - gw) / 2, baseline, "ls")
            cx += w
        }
        return cx - x
    }

    fun ascent(face: Face, size: Int): Float = layout("0", face, size).firstBaseline

    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, alpha: Float = 1f, stroke: Float = 0f) {
        val style = if (stroke > 0f) Stroke(stroke) else androidx.compose.ui.graphics.drawscope.Fill
        val inset = stroke / 2
        scope.drawRoundRect(
            color, Offset(x + inset, y + inset), Size(w - stroke, h - stroke),
            CornerRadius(maxOf(0f, radius - inset)), style, alpha,
        )
    }

    fun circle(color: Color, cx: Float, cy: Float, r: Float, stroke: Float = 0f) {
        if (stroke > 0f) scope.drawCircle(color, r - stroke / 2, Offset(cx, cy), style = Stroke(stroke))
        else scope.drawCircle(color, r, Offset(cx, cy))
    }

    fun line(color: Color, x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 1f) =
        scope.drawLine(color, Offset(x0, y0), Offset(x1, y1), width)
}
