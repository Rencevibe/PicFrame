package io.github.rencevibe.picframe.domain

import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * Pure parts of frame/photos.py. Listing the picked folder, decoding, blurring and writing the
 * cache are the data layer's job (M3); the rules they follow live here so they're unit-tested
 * on the JVM.
 */

val BASE_EXTS = setOf("jpg", "jpeg", "png")

/** True for a supported photo at [relPath] ("sub/a.jpg"); any hidden segment (".x") is skipped,
 *  as the Pi scan skips hidden files and folders. WebP only when the decoder supports it. */
fun isPhotoPath(relPath: String, webp: Boolean): Boolean {
    val parts = relPath.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.any { it.startsWith(".") }) return false
    val name = parts.last()
    val dot = name.lastIndexOf('.')
    val ext = if (dot < 0) "" else name.substring(dot + 1).lowercase()
    return ext in BASE_EXTS || (webp && ext == "webp")
}

/** Shuffle order over the current set of photos (port of PhotoLibrary without the folder walk).
 *  Every photo shows once per round, no photo repeats across a round boundary, and new photos
 *  join the current round at random places. */
class PhotoQueue(private val rng: Random = Random.Default) {
    private var files: Set<String> = emptySet()
    private var queue = mutableListOf<String>() // still to show this round, taken from the end
    private var last: String? = null

    /** Replaces the known set with the result of a scan; returns its size. */
    fun update(found: Set<String>): Int {
        val new = found - files
        queue.retainAll(found)
        for (p in new.sorted()) queue.add(rng.nextInt(0, queue.size + 1), p)
        files = found.toSet()
        return files.size
    }

    fun next(): String? {
        if (files.isEmpty()) return null
        if (queue.isEmpty()) {
            queue = files.sorted().toMutableList()
            queue.shuffle(rng)
            if (queue.size > 1 && queue.last() == last) { // no repeat across rounds
                val end = queue.lastIndex
                queue[0] = queue[end].also { queue[end] = queue[0] }
            }
        }
        return queue.removeAt(queue.lastIndex).also { last = it }
    }
}

// --- image geometry (port of _open / _fill / _render) ---

const val BACKDROP_SCALE = 8 // backdrop is made at 1/8 size …
const val BACKDROP_BLUR = 24.0 / BACKDROP_SCALE // … so radius 3 there ≈ radius 24 at full size
const val BACKDROP_BRIGHTNESS = 0.4
const val MAX_DECODE_PIXELS = 40_000_000L // ≈ 120 MB as RGB

data class PxSize(val w: Int, val h: Int)

data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

enum class CacheMode(val key: String) { FILL("fill"), FIT("fit") }

/** Python's round(): half to even. */
private fun roundEven(x: Double): Int = Math.rint(x).toInt()

/** Scale the photo to [scaled], then crop [crop] out of it. */
data class FillPlan(val scaled: PxSize, val crop: PxRect)

/** Fill-crop to exactly [dst]. Portrait photos start at the top, the only crop that keeps the
 *  whole upper third (where faces usually are) in view; landscape photos are centered. */
fun fillPlan(src: PxSize, dst: PxSize, portraitTop: Boolean = src.h > src.w): FillPlan {
    val scale = max(dst.w.toDouble() / src.w, dst.h.toDouble() / src.h)
    val rw = max(dst.w, roundEven(src.w * scale))
    val rh = max(dst.h, roundEven(src.h * scale))
    val x = (rw - dst.w) / 2
    val y = if (portraitTop) 0 else (rh - dst.h) / 2
    return FillPlan(PxSize(rw, rh), PxRect(x, y, dst.w, dst.h))
}

/** Fit onto a [dst] canvas: the backdrop is a centered fill of the photo at 1/8 size (then
 *  blurred, brightened ×0.4 and scaled up); the photo is pasted at [photo]. */
data class FitPlan(val backdropSmall: PxSize, val backdrop: FillPlan, val photo: PxRect)

fun fitPlan(src: PxSize, dst: PxSize): FitPlan {
    val small = PxSize(max(1, dst.w / BACKDROP_SCALE), max(1, dst.h / BACKDROP_SCALE))
    val scale = min(dst.w.toDouble() / src.w, dst.h.toDouble() / src.h)
    val fw = min(dst.w, roundEven(src.w * scale))
    val fh = min(dst.h, roundEven(src.h * scale))
    return FitPlan(small, fillPlan(src, small, portraitTop = false), PxRect((dst.w - fw) / 2, (dst.h - fh) / 2, fw, fh))
}

/** Display size after EXIF rotation: orientations 5–8 swap width and height. */
fun orientedSize(stored: PxSize, exifOrientation: Int?): PxSize =
    if (exifOrientation in 5..8) PxSize(stored.h, stored.w) else stored

/** How to decode a photo: at 1/[sampleSize] scale, giving [decoded] (stored orientation). */
data class DecodePlan(val sampleSize: Int, val decoded: PxSize)

/**
 * JPEGs decode at the largest of 1/8, 1/4, 1/2 or full scale that stays at least [target]
 * (Pillow's draft rule; the target is swapped first when EXIF will rotate by 90°). Other formats
 * decode at full size. Returns null when the decoded image would exceed [maxPixels], checked
 * before any pixels are decoded.
 */
fun decodePlan(
    stored: PxSize,
    target: PxSize,
    isJpeg: Boolean,
    exifOrientation: Int?,
    maxPixels: Long = MAX_DECODE_PIXELS,
): DecodePlan? {
    var sample = 1
    if (isJpeg) {
        val t = if (exifOrientation in 5..8) PxSize(target.h, target.w) else target
        val scale = min(stored.w / t.w, stored.h / t.h)
        sample = listOf(8, 4, 2, 1).firstOrNull { scale >= it } ?: 1
    }
    val decoded = PxSize((stored.w + sample - 1) / sample, (stored.h + sample - 1) / sample)
    if (decoded.w.toLong() * decoded.h > maxPixels) return null
    return DecodePlan(sample, decoded)
}

// --- disk cache rules (port of ImageCache's key and eviction) ---

/** Cache file name for [sourceId] at [size]/[mode]. Last-modified time and byte size are both in
 *  the key, so an edited file copied with its old timestamp still misses. */
fun cacheKey(sourceId: String, modifiedNs: Long, byteSize: Long, size: PxSize, mode: CacheMode): String {
    val key = "$sourceId|$modifiedNs|$byteSize|${size.w}x${size.h}|${mode.key}"
    val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + ".jpg"
}

data class CacheEntry(val name: String, val lastUsed: Long, val bytes: Long)

/** Files to delete, least recently used first, until the total is within [limitBytes];
 *  [keep] (the file just written) is never deleted. */
fun evictionPlan(entries: List<CacheEntry>, limitBytes: Long, keep: String): List<String> {
    var total = entries.sumOf { it.bytes }
    val out = mutableListOf<String>()
    for (e in entries.sortedWith(compareBy<CacheEntry>({ it.lastUsed }, { it.bytes }, { it.name }))) {
        if (total <= limitBytes) break
        if (e.name != keep) {
            out += e.name
            total -= e.bytes
        }
    }
    return out
}

/** Sources that failed to decode, by version, so each is reported once and not retried until
 *  the file changes. */
class BadSources {
    private val bad = HashSet<Triple<String, Long, Long>>()

    fun isBad(sourceId: String, modifiedNs: Long, byteSize: Long) = Triple(sourceId, modifiedNs, byteSize) in bad

    /** Returns true the first time, so the caller logs it once. */
    fun markBad(sourceId: String, modifiedNs: Long, byteSize: Long) = bad.add(Triple(sourceId, modifiedNs, byteSize))
}
