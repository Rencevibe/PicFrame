package io.github.rencevibe.picframe.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original vector icons (D4: redrawn for this project; the Pi PNGs are not used). Outlined,
 * 2 px rounded strokes at 32 px (spec "Icons"), drawn on a 24-unit grid.
 */
object Icons {
    val WEATHER = listOf("clear", "partly-cloudy", "cloudy", "fog", "rain", "snow", "storm", "unknown")
    val UI = listOf(
        "expand", "dashboard", "chevron-left", "chevron-right", "chevron-forward", "bell", "close",
        "minus", "plus", "photo",
    )

    /**
     * Draws [name] with its top-left at ([x], [y]) and [size] px square. [color] tints every stroke;
     * when null, strokes are [Palette.TEXT] and suns stay amber, like the Pi's untinted icons.
     */
    fun draw(ink: Ink, name: String, x: Float, y: Float, size: Int, color: Color? = null) {
        val k = size / 24f
        val stroke = Stroke(width = 2f * size / 32f / k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val main = color ?: Palette.TEXT
        val sun = color ?: Palette.HIGHLIGHT
        ink.scope.translate(x, y) {
            scale(k, k, pivot = Offset.Zero) {
                fun path(build: Path.() -> Unit, c: Color = main) = drawPath(Path().apply(build), c, style = stroke)
                fun poly(vararg p: Float, c: Color = main) = path({
                    moveTo(p[0], p[1])
                    for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                }, c)
                fun cloud(dx: Float = 0f, dy: Float = 0f, s: Float = 1f) = path({
                    fun px(v: Float) = dx + v * s
                    fun py(v: Float) = dy + v * s
                    fun box(cx: Float, cy: Float, r: Float) = Rect(px(cx - r), py(cy - r), px(cx + r), py(cy + r))
                    moveTo(px(7f), py(18f))
                    lineTo(px(17f), py(18f))
                    arcTo(box(17f, 14f, 4f), 90f, -180f, false)
                    arcTo(box(12f, 11f, 5f), -11f, -150f, false)
                    arcTo(box(7f, 14.5f, 3.5f), -95f, -175f, false)
                    close()
                })
                fun sunAt(cx: Float, cy: Float, r: Float, rays: Boolean = true) {
                    drawCircle(sun, r, Offset(cx, cy), style = stroke)
                    if (rays) for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        poly(
                            cx + (r + 2.5f) * cos(a).toFloat(), cy + (r + 2.5f) * sin(a).toFloat(),
                            cx + (r + 4.5f) * cos(a).toFloat(), cy + (r + 4.5f) * sin(a).toFloat(), c = sun,
                        )
                    }
                }
                when (name) {
                    "clear" -> sunAt(12f, 12f, 4.5f)
                    "partly-cloudy" -> {
                        sunAt(8.5f, 8.5f, 3f)
                        cloud(dx = 3f, dy = 3f, s = 0.85f)
                    }
                    "cloudy" -> cloud(dy = 1f)
                    "fog" -> {
                        poly(4f, 9f, 20f, 9f)
                        poly(6f, 13f, 18f, 13f)
                        poly(4f, 17f, 20f, 17f)
                    }
                    "rain" -> {
                        cloud(dy = -3f)
                        poly(9f, 18f, 8f, 21f); poly(13f, 18f, 12f, 21f); poly(17f, 18f, 16f, 21f)
                    }
                    "snow" -> {
                        cloud(dy = -3f)
                        for (cx in listOf(8.5f, 12.5f, 16.5f)) drawCircle(main, 0.9f, Offset(cx, 19.5f))
                    }
                    "storm" -> {
                        cloud(dy = -3f)
                        poly(13f, 15f, 10.5f, 19f, 13.5f, 19f, 11f, 23f)
                    }
                    "unknown" -> {
                        drawCircle(main, 9f, Offset(12f, 12f), style = stroke)
                        path({
                            moveTo(9.5f, 9.5f)
                            arcTo(Rect(9.5f, 7f, 14.5f, 12f), 180f, 225f, false)
                            lineTo(12f, 14f)
                        })
                        drawCircle(main, 1f, Offset(12f, 17f))
                    }
                    "expand" -> {
                        poly(4f, 9f, 4f, 4f, 9f, 4f); poly(15f, 4f, 20f, 4f, 20f, 9f)
                        poly(20f, 15f, 20f, 20f, 15f, 20f); poly(9f, 20f, 4f, 20f, 4f, 15f)
                    }
                    "dashboard" -> for ((ox, oy) in listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f)) {
                        drawRoundRect(main, Offset(ox, oy), androidx.compose.ui.geometry.Size(6f, 6f),
                            androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                    }
                    "chevron-left" -> poly(15f, 6f, 9f, 12f, 15f, 18f)
                    "chevron-right", "chevron-forward" -> poly(9f, 6f, 15f, 12f, 9f, 18f)
                    "close" -> { poly(6f, 6f, 18f, 18f); poly(18f, 6f, 6f, 18f) }
                    "minus" -> poly(5f, 12f, 19f, 12f)
                    "plus" -> { poly(5f, 12f, 19f, 12f); poly(12f, 5f, 12f, 19f) }
                    "bell" -> {
                        path({
                            moveTo(6f, 16.5f)
                            lineTo(6f, 11f)
                            arcTo(Rect(6f, 5f, 18f, 17f), 180f, 180f, false)
                            lineTo(18f, 16.5f)
                            lineTo(19.5f, 18.5f)
                            lineTo(4.5f, 18.5f)
                            close()
                        })
                        poly(10.5f, 21f, 13.5f, 21f)
                    }
                    "photo" -> {
                        drawRoundRect(main, Offset(3f, 5f), androidx.compose.ui.geometry.Size(18f, 14f),
                            androidx.compose.ui.geometry.CornerRadius(2f), style = stroke)
                        drawCircle(main, 1.5f, Offset(8.5f, 9.5f), style = stroke)
                        poly(3.5f, 17f, 9f, 12f, 13f, 15.5f, 16f, 13f, 20.5f, 17f)
                    }
                    else -> drawCircle(main, 9f, Offset(12f, 12f), style = stroke) // unknown name
                }
            }
        }
    }
}
