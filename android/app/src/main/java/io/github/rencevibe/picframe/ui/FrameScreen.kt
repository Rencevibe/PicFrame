package io.github.rencevibe.picframe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rencevibe.picframe.domain.validateConfig
import kotlin.math.roundToInt

/** Draws the 1280×800 design canvas scaled uniformly to the window, letterboxed (plan §5). */
@Composable
fun FrameScreen(vm: FrameViewModel) {
    val resolver = LocalFontFamilyResolver.current
    // Density(1f): 1.sp is one design pixel; the canvas scale then maps to physical pixels
    val measurer = remember(resolver) { TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, 512) }
    BoxWithConstraints(Modifier.fillMaxSize().background(if (vm.isBlack) Palette.BLACK else Palette.BG)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val scale = minOf(wPx / W, hPx / H)
        val ox = (wPx - W * scale) / 2
        val oy = (hPx - H * scale) / 2
        SideEffect { vm.slides.pixelScale = scale }
        fun toDesign(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)

        Canvas(
            Modifier.fillMaxSize().pointerInput(scale, ox, oy) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    vm.onDown(toDesign(first.position))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                        if (change.pressed) {
                            vm.onMove(toDesign(change.position))
                        } else {
                            vm.onUp(toDesign(change.position))
                            break
                        }
                    }
                }
            },
        ) {
            vm.frame // redraw every tick
            translate(ox, oy) {
                scale(scale, scale, pivot = Offset.Zero) {
                    clipRect(0f, 0f, W, H) { vm.hits = vm.render(Ink(this, measurer)) }
                }
            }
        }

        // Accessibility: one node per touch target, at its place on screen
        val density = LocalDensity.current
        for (hit in vm.a11yHits) {
            val r = hit.rect
            Box(
                Modifier
                    .offset { IntOffset((ox + r.left * scale).roundToInt(), (oy + r.top * scale).roundToInt()) }
                    .size(with(density) { (r.width * scale).toDp() }, with(density) { (r.height * scale).toDp() })
                    .semantics {
                        contentDescription = hit.label
                        role = Role.Button
                        onClick { vm.a11yTap(hit.action); true }
                    },
            )
        }
    }
}

/** Latitude/longitude entry for weather (plan §3: typed, rounded to 2 decimals, no permission). */
@Composable
fun LocationDialog(lat: Double?, lon: Double?, onDone: (Double?, Double?, Boolean) -> Unit) {
    var latText by remember { mutableStateOf(lat?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "") }
    var lonText by remember { mutableStateOf(lon?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val style = TextStyle(color = Palette.TEXT, fontSize = 22.sp, fontFamily = Fonts.ui)
    Dialog(onDismissRequest = { onDone(null, null, false) }) {
        Column(
            Modifier.background(Palette.PANEL, RoundedCornerShape(24.dp)).padding(24.dp).width(360.dp),
        ) {
            BasicText("Location for weather", style = style.copy(fontSize = 26.sp, fontFamily = Fonts.inter))
            Spacer(Modifier.height(16.dp))
            for ((label, value, set) in listOf(
                Triple("Latitude (−90 to 90)", latText, { s: String -> latText = s }),
                Triple("Longitude (−180 to 180)", lonText, { s: String -> lonText = s }),
            )) {
                BasicText(label, style = style.copy(color = Palette.SECONDARY, fontSize = 18.sp))
                BasicTextField(
                    value, set, singleLine = true, textStyle = style, cursorBrush = SolidColor(Palette.ACCENT),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.background(Palette.RAISED, RoundedCornerShape(12.dp)).padding(12.dp).width(312.dp)
                        .semantics { contentDescription = label },
                )
                Spacer(Modifier.height(12.dp))
            }
            error?.let { BasicText(it, style = style.copy(color = Palette.HIGHLIGHT, fontSize = 18.sp)) }
            Row {
                DialogButton("Clear") { onDone(null, null, true) }
                Spacer(Modifier.width(12.dp))
                DialogButton("Cancel") { onDone(null, null, false) }
                Spacer(Modifier.width(12.dp))
                DialogButton("Save", accent = true) {
                    val la = latText.trim().toDoubleOrNull()
                    val lo = lonText.trim().toDoubleOrNull()
                    val r = validateConfig(mapOf("lat" to la, "lon" to lo))
                    if (la == null || lo == null || r.config.lat == null) {
                        error = "Enter both numbers within range."
                    } else {
                        onDone(Math.round(la * 100) / 100.0, Math.round(lo * 100) / 100.0, true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (accent) Palette.BG else Palette.TEXT, fontSize = 20.sp, fontFamily = Fonts.ui),
        modifier = Modifier
            .background(if (accent) Palette.ACCENT else Palette.RAISED, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
