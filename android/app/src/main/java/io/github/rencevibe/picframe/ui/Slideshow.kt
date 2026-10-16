package io.github.rencevibe.picframe.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.rencevibe.picframe.data.DocEntry
import io.github.rencevibe.picframe.data.ImageCache
import io.github.rencevibe.picframe.data.PhotoSource
import io.github.rencevibe.picframe.domain.CacheMode
import io.github.rencevibe.picframe.domain.PxSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

/**
 * The current photo as dashboard (fill) and fullscreen (fit) images (port of app.Slideshow).
 * The next photo is prepared on [io] 5 s before the switch; only current + next are in memory.
 * [update] and the getters run on the UI thread.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
class Slideshow(
    private val source: PhotoSource,
    private val cache: ImageCache,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Double,
) {
    class Slide(val entry: DocEntry, val dash: ImageBitmap, val full: ImageBitmap)

    /** Physical pixels per design pixel, so cached images match the screen (plan §3). */
    var pixelScale = 1f

    private var current: Slide? = null
    private var fadeFrom: Slide? = null
    private var fadeStart = 0.0
    private var fadeS = 0.0
    private var switchedAt = clock()
    private var preload: Deferred<Slide?>? = null
    private var jumping = false
    private var generation = 0 // bumped by reset(); results of older jumps are dropped
    private val history = ArrayDeque<DocEntry>()

    val fading: Boolean get() = fadeFrom != null

    private fun size(w: Int, h: Int) = PxSize(maxOf(1, (w * pixelScale).toInt()), maxOf(1, (h * pixelScale).toInt()))

    private fun decode(f: File?): ImageBitmap? = f?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }

    /** Resize (cache) and decode both sizes; null if unusable. Runs on [io]. */
    private fun prepare(entry: DocEntry): Slide? {
        val dash = decode(cache.get(entry, size(DASH_W, DASH_H), CacheMode.FILL)) ?: return null
        val full = decode(cache.get(entry, size(W.toInt(), H.toInt()), CacheMode.FIT)) ?: return null
        return Slide(entry, dash, full)
    }

    /** Next usable photo (unreadable files are skipped), or null. */
    private fun prepareNext(tries: Int = 10): Slide? {
        repeat(tries) {
            val entry = source.next() ?: return null
            prepare(entry)?.let { return it }
        }
        return null
    }

    private fun show(slide: Slide, fadeSeconds: Double, record: Boolean = true) {
        val now = clock()
        if (fadeSeconds > 0 && current != null) {
            fadeFrom = current
            fadeStart = now
            fadeS = fadeSeconds
        } else {
            fadeFrom = null
        }
        current = slide
        if (record) {
            history.addLast(slide.entry)
            while (history.size > HISTORY) history.removeFirst()
        }
        switchedAt = now
    }

    /** Called every frame. [step] is -1/+1 for Prev/Next. */
    fun update(intervalS: Int, fadeMs: Int, step: Int) {
        val t = clock()
        if (fadeFrom != null && t - fadeStart >= fadeS) fadeFrom = null
        if (step != 0) {
            jump(step)
            return
        }
        if (jumping) return
        val pending = preload
        if (current == null) {
            if (pending == null && t >= switchedAt) {
                preload = scope.async(io) { prepareNext() }
            } else if (pending != null && pending.isCompleted) {
                preload = null
                val slide = pending.getCompleted()
                if (slide != null) show(slide, 0.0) else switchedAt = t + 10 // nothing usable yet
            }
            return
        }
        val due = switchedAt + intervalS
        if (pending == null && t >= due - PRELOAD_S) preload = scope.async(io) { prepareNext() }
        if (pending != null && t >= due && pending.isCompleted) {
            preload = null
            val slide = pending.getCompleted()
            if (slide != null) show(slide, fadeMs / 1000.0) else switchedAt = t
        }
    }

    /** Prev / Next: a cut (no fade) that also restarts the interval. Prepared off the UI thread. */
    private fun jump(step: Int) {
        if (jumping) return
        jumping = true
        val pending = preload
        preload = null
        val back = if (step < 0 && history.size >= 2) history.elementAt(history.size - 2) else null
        if (step < 0 && back == null) {
            jumping = false
            return
        }
        val gen = generation
        scope.launch {
            val slide = if (back != null) {
                scope.async(io) { prepare(back) }.await()
            } else {
                // A queued preload keeps the shuffle order instead of skipping past it
                pending?.await() ?: scope.async(io) { prepareNext() }.await()
            }
            if (gen != generation) return@launch // reset() ran meanwhile; it already cleared jumping
            if (slide != null) {
                if (back != null) history.removeLast()
                show(slide, 0.0, record = back == null)
            }
            jumping = false
        }
    }

    /** Layers to draw for "dash" or "full": one image, or two during a fade. */
    fun layers(full: Boolean): List<Layer> {
        val cur = current ?: return emptyList()
        val pick = { s: Slide -> if (full) s.full else s.dash }
        val from = fadeFrom ?: return listOf(Layer(pick(cur), 1f))
        val a = ((clock() - fadeStart) / fadeS).coerceIn(0.0, 1.0).toFloat()
        return listOf(Layer(pick(from), 1f), Layer(pick(cur), a))
    }

    /** Forget the current photo (e.g. a new folder was picked). */
    fun reset() {
        generation++
        jumping = false
        preload?.cancel()
        preload = null
        current = null
        fadeFrom = null
        history.clear()
        switchedAt = clock()
    }

    companion object {
        const val DASH_W = 664
        const val DASH_H = 600
        const val PRELOAD_S = 5.0
        const val HISTORY = 50
    }
}
