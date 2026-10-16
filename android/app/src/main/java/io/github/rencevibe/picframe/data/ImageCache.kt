package io.github.rencevibe.picframe.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import io.github.rencevibe.picframe.domain.BACKDROP_BLUR
import io.github.rencevibe.picframe.domain.BACKDROP_BRIGHTNESS
import io.github.rencevibe.picframe.domain.BadSources
import io.github.rencevibe.picframe.domain.CacheEntry
import io.github.rencevibe.picframe.domain.CacheMode
import io.github.rencevibe.picframe.domain.MAX_DECODE_PIXELS
import io.github.rencevibe.picframe.domain.PxRect
import io.github.rencevibe.picframe.domain.PxSize
import io.github.rencevibe.picframe.domain.cacheKey
import io.github.rencevibe.picframe.domain.decodePlan
import io.github.rencevibe.picframe.domain.evictionPlan
import io.github.rencevibe.picframe.domain.fillPlan
import io.github.rencevibe.picframe.domain.fitPlan
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Display-ready JPEGs of photos at exactly the requested size, kept on disk with LRU eviction
 * (port of frame/photos.py ImageCache; the rules are in domain/Photos.kt).
 * "fill" crops; "fit" letterboxes onto a blurred, darkened copy of the same photo.
 */
class ImageCache(
    private val dir: File,
    var limitBytes: Long,
    private val open: (DocEntry) -> InputStream?,
    private val log: (String) -> Unit = {},
    private val maxPixels: Long = MAX_DECODE_PIXELS,
) {
    private val bad = BadSources()
    private var stamp = 0L

    /** Marks a cache file most recently used (strictly increasing, even within one clock tick). */
    private fun touch(f: File) {
        stamp = maxOf(System.currentTimeMillis(), stamp + 1)
        f.setLastModified(stamp)
    }

    /** The cached file, or null if the source is missing or can't be decoded (reported once). */
    @Synchronized
    fun get(src: DocEntry, size: PxSize, mode: CacheMode): File? {
        val out = File(dir, cacheKey(src.id, src.lastModifiedMs * 1_000_000, src.size, size, mode))
        if (out.exists()) {
            touch(out)
            return out
        }
        if (bad.isBad(src.id, src.lastModifiedMs, src.size)) return null
        val bitmap = try {
            render(src, size, mode)
        } catch (e: FileNotFoundException) {
            return null // gone since the last scan: not the photo's fault
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bitmap == null) {
            if (bad.markBad(src.id, src.lastModifiedMs, src.size)) log("photos: skipping unreadable ${src.relPath}")
            return null
        }
        dir.mkdirs()
        val tmp = File(dir, out.name + ".tmp")
        try {
            FileOutputStream(tmp).use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("encode failed") }
            if (!tmp.renameTo(out)) throw IOException("rename failed")
        } catch (e: IOException) {
            log("photos: cache write failed ($e)")
            tmp.delete()
            return null
        } finally {
            bitmap.recycle()
        }
        touch(out)
        evict(keep = out.name)
        return out
    }

    private fun evict(keep: String) {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
        val gone = evictionPlan(files.map { CacheEntry(it.name, it.lastModified(), it.length()) }, limitBytes, keep).toSet()
        files.filter { it.name in gone }.forEach { it.delete() }
    }

    private fun stream(src: DocEntry): InputStream = open(src) ?: throw FileNotFoundException(src.relPath)

    private fun render(src: DocEntry, size: PxSize, mode: CacheMode): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream(src).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = try {
            stream(src).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) { // no or unreadable EXIF: treat as upright
            ExifInterface.ORIENTATION_NORMAL
        }
        val isJpeg = bounds.outMimeType == "image/jpeg"
        val plan = decodePlan(
            PxSize(bounds.outWidth, bounds.outHeight), size, isJpeg = isJpeg, exifOrientation = orientation,
            maxPixels = maxPixels,
        ) ?: return null // too large to decode safely: checked before any pixels are decoded
        // BitmapFactory returns a partly gray bitmap for a truncated JPEG; Pillow refused it
        if (isJpeg && !stream(src).use { jpegComplete(it) }) return null
        val decoded = stream(src).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = plan.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        val upright = applyOrientation(decoded, orientation)
        return try {
            when (mode) {
                CacheMode.FILL -> fill(upright, size, portraitTop = upright.height > upright.width)
                CacheMode.FIT -> fit(upright, size)
            }
        } finally {
            upright.recycle()
        }
    }

    companion object {
        /**
         * True if a JPEG's main image is followed by its end-of-image marker. Segments before the
         * scan are skipped by length (so an EXIF thumbnail's markers don't count); trailers after
         * the end marker (some phones add them) are fine.
         */
        fun jpegComplete(input: InputStream): Boolean {
            val s = input.buffered()
            fun byte(): Int = s.read()
            if (byte() != 0xFF || byte() != 0xD8) return false
            while (true) { // header segments up to start-of-scan
                var b = byte()
                if (b != 0xFF) return false
                while (b == 0xFF) b = byte() // fill bytes
                when {
                    b < 0 -> return false
                    b == 0xDA -> break // start of scan
                    b == 0x01 || b in 0xD0..0xD7 -> continue // markers without a length
                }
                val len = (byte() shl 8) or byte()
                if (len < 2) return false
                var skip = (len - 2).toLong()
                while (skip > 0) {
                    val n = s.skip(skip)
                    if (n <= 0) return false
                    skip -= n
                }
            }
            var prev = 0
            while (true) { // entropy-coded data and any further scans
                val b = byte()
                if (b < 0) return false
                if (prev == 0xFF && b == 0xD9) return true
                prev = b
            }
        }

        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** Rotates/flips per EXIF orientation 2–8; recycles [b] when it makes a new bitmap. */
        fun applyOrientation(b: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                2 -> m.postScale(-1f, 1f)
                3 -> m.postRotate(180f)
                4 -> m.postScale(1f, -1f)
                5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.postRotate(90f)
                7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.postRotate(-90f)
                else -> return b
            }
            val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (out !== b) b.recycle()
            return out
        }

        fun fill(b: Bitmap, size: PxSize, portraitTop: Boolean): Bitmap {
            val plan = fillPlan(PxSize(b.width, b.height), size, portraitTop)
            val out = Bitmap.createBitmap(size.w, size.h, Bitmap.Config.ARGB_8888)
            val scaleX = plan.scaled.w.toFloat() / b.width
            val scaleY = plan.scaled.h.toFloat() / b.height
            val m = Matrix().apply {
                postScale(scaleX, scaleY)
                postTranslate(-plan.crop.x.toFloat(), -plan.crop.y.toFloat())
            }
            Canvas(out).drawBitmap(b, m, FILTER)
            return out
        }

        fun fit(b: Bitmap, size: PxSize): Bitmap {
            val plan = fitPlan(PxSize(b.width, b.height), size)
            val small = fill(b, plan.backdropSmall, portraitTop = false)
            boxBlur(small, BACKDROP_BLUR.roundToInt())
            val out = Bitmap.createBitmap(size.w, size.h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val dim = Paint(FILTER).apply {
                val k = BACKDROP_BRIGHTNESS.toFloat()
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
            }
            canvas.drawBitmap(small, null, Rect(0, 0, size.w, size.h), dim)
            small.recycle()
            canvas.drawBitmap(b, null, plan.photo.toRect(), FILTER)
            return out
        }

        private fun PxRect.toRect() = Rect(x, y, x + w, y + h)

        /** Three box-blur passes per axis ≈ a Gaussian; radius in pixels. In place. */
        fun boxBlur(b: Bitmap, radius: Int) {
            if (radius < 1) return
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            repeat(3) {
                blurPass(px, tmp, w, h, radius, horizontal = true)
                blurPass(tmp, px, w, h, radius, horizontal = false)
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val window = 2 * r + 1
            for (line in 0 until lines) {
                fun at(i: Int): Int {
                    val c = i.coerceIn(0, len - 1) // edge pixels repeat
                    return if (horizontal) src[line * w + c] else src[c * w + line]
                }
                var a = 0
                var rr = 0
                var g = 0
                var bb = 0
                for (i in -r..r) {
                    val p = at(i)
                    a += p ushr 24; rr += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bb += p and 0xff
                }
                for (i in 0 until len) {
                    val o = if (horizontal) line * w + i else i * w + line
                    dst[o] = ((a / window) shl 24) or ((rr / window) shl 16) or ((g / window) shl 8) or (bb / window)
                    val add = at(i + r + 1)
                    val sub = at(i - r)
                    a += (add ushr 24) - (sub ushr 24)
                    rr += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                    g += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                    bb += (add and 0xff) - (sub and 0xff)
                }
            }
        }
    }
}
