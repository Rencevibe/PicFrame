package io.github.rencevibe.picframe.data

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import io.github.rencevibe.picframe.domain.CacheMode
import io.github.rencevibe.picframe.domain.PxSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The file and pixel parts of tests/test_photos.py's cache cases (deferred from M2), on real
 * decoded bitmaps (Robolectric native graphics).
 */
@RunWith(RobolectricTestRunner::class)
class ImageCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dash = PxSize(664, 600)
    private val full = PxSize(1280, 800)

    private fun setup(limitMb: Long = 500, maxPixels: Long = 40_000_000L): Triple<File, FileTree, ImageCache> {
        val photos = tmp.newFolder("photos")
        val tree = FileTree(photos)
        val logged = mutableListOf<String>()
        val cache = ImageCache(File(tmp.root, "cache"), limitMb * 1024 * 1024, { tree.open(it.id) }, { logged += it }, maxPixels)
        lastLog = logged
        return Triple(photos, tree, cache)
    }

    private var lastLog = mutableListOf<String>()

    private fun bitmap(f: File?) = BitmapFactory.decodeFile(f!!.path)!!

    @Test fun cacheOutputSizeExact() {
        val (photos, tree, cache) = setup()
        for (src in listOf(makeImage(File(photos, "land.jpg"), 1600, 900), makeImage(File(photos, "port.png"), 900, 1600))) {
            for ((size, mode) in listOf(dash to CacheMode.FILL, full to CacheMode.FIT)) {
                val b = bitmap(cache.get(tree.entry(src), size, mode))
                assertEquals(size, PxSize(b.width, b.height))
            }
        }
    }

    @Test fun cacheHitReusesFileAndSourceChangeMisses() {
        val (photos, tree, cache) = setup()
        val src = makeImage(File(photos, "a.jpg"))
        val first = cache.get(tree.entry(src), dash, CacheMode.FILL)
        assertEquals(first, cache.get(tree.entry(src), dash, CacheMode.FILL))
        assertNotEquals(first, cache.get(tree.entry(src), full, CacheMode.FIT))
        src.setLastModified(1000) // edited photo → new key
        assertNotEquals(first, cache.get(tree.entry(src), dash, CacheMode.FILL))
    }

    @Test fun exifRotatedImageUpright() {
        // Stored 600x800 with orientation 6 → displays as 800x600 landscape
        val (photos, tree, cache) = setup()
        val src = makeImage(File(photos, "phone.jpg"), 600, 800, Color.WHITE, exifOrientation = 6)
        val out = bitmap(cache.get(tree.entry(src), full, CacheMode.FIT))
        // Landscape fits as 1067x800 (bars ~107 px); portrait would fit as 600x800 (bars 340 px)
        assertTrue(Color.red(out.getPixel(200, 400)) > 240) // photo, not backdrop
    }

    @Test fun fillPortraitKeepsTop() {
        val (photos, tree, cache) = setup()
        val gray = Color.rgb(128, 128, 128)
        val src = makeImage(File(photos, "portrait.png"), 600, 1200, gray, band = Rect(0, 100, 600, 300) to Color.RED)
        val out = bitmap(cache.get(tree.entry(src), dash, CacheMode.FILL))
        val p = out.getPixel(332, 200) // band (scaled to rows 111–332) is in view
        assertTrue(Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) < 60)
        val top = out.getPixel(332, 0) // crop starts at the photo's top edge
        assertTrue(listOf(Color.red(top), Color.green(top), Color.blue(top)).all { it in 120..136 })
    }

    @Test fun fillLandscapeIsCentered() {
        val (photos, tree, cache) = setup()
        val src = makeImage(File(photos, "wide.png"), 2000, 600, Color.rgb(128, 128, 128), band = Rect(990, 0, 1010, 600) to Color.BLUE)
        val out = bitmap(cache.get(tree.entry(src), dash, CacheMode.FILL))
        assertTrue(Color.blue(out.getPixel(332, 300)) > 200)
    }

    @Test fun fitBackdropNotBlack() {
        val (photos, tree, cache) = setup()
        val src = makeImage(File(photos, "port.jpg"), 600, 900, Color.rgb(40, 160, 90))
        val out = bitmap(cache.get(tree.entry(src), full, CacheMode.FIT))
        val corner = out.getPixel(5, 5)
        val centre = out.getPixel(640, 400)
        assertNotEquals(Color.BLACK, corner)
        fun sum(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c)
        assertTrue(sum(corner) > 0 && sum(corner) < sum(centre)) // backdrop is darker than the photo
    }

    @Test fun corruptFileReturnsNull() {
        val (photos, tree, cache) = setup()
        val bad = File(photos, "bad.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + " not really a jpeg".toByteArray()) }
        val good = makeImage(File(photos, "good.jpg"), 800, 600)
        val truncated = File(photos, "half.jpg").apply { writeBytes(good.readBytes().copyOf(400)) }
        repeat(3) {
            assertNull(cache.get(tree.entry(bad), dash, CacheMode.FILL))
            assertNull(cache.get(tree.entry(truncated), dash, CacheMode.FILL))
        }
        assertEquals(1, lastLog.count { "bad.jpg" in it })
        assertEquals(1, lastLog.count { "half.jpg" in it })
        val gone = DocEntry(File(photos, "gone.jpg").path, "gone.jpg", 1, 1)
        assertNull(cache.get(gone, dash, CacheMode.FILL))
        assertTrue(File(tmp.root, "cache").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertNotNull(cache.get(tree.entry(good), dash, CacheMode.FILL)) // a complete JPEG still works
    }

    @Test fun evictionUnderLimit() {
        val (photos, tree, cache) = setup()
        val srcs = (0 until 4).map { noise(File(photos, "n$it.png"), 664, 600) }
        val one = cache.get(tree.entry(srcs[0]), dash, CacheMode.FILL)!!.length()
        cache.limitBytes = (one * 2.5).toLong() // room for two outputs
        val outs = srcs.subList(1, 3).map { cache.get(tree.entry(it), dash, CacheMode.FILL)!! }
        cache.get(tree.entry(srcs[1]), dash, CacheMode.FILL) // touch: n1 is now more recent than n2
        val last = cache.get(tree.entry(srcs[3]), dash, CacheMode.FILL)!!
        val files = File(tmp.root, "cache").listFiles().orEmpty()
        assertTrue(files.sumOf { it.length() } <= cache.limitBytes)
        assertTrue(last.exists() && outs[0].exists()) // newest + recently used survive
        assertFalse(outs[1].exists()) // least recently used went first
    }

    @Test fun sameMtimeReplacementMissesCache() {
        val (photos, tree, cache) = setup()
        val src = makeImage(File(photos, "a.png"), 800, 600, Color.RED)
        val first = cache.get(tree.entry(src), dash, CacheMode.FILL)
        val stamp = src.lastModified()
        noise(src, 900, 600) // different photo, different byte size …
        src.setLastModified(stamp) // … but the same mtime
        val second = cache.get(tree.entry(src), dash, CacheMode.FILL)
        assertNotEquals(first, second)
        assertNotEquals(Color.RED, bitmap(second).getPixel(332, 300))
    }

    @Test fun oversizedImageRejectedBeforeDecoding() {
        val (photos, tree, cache) = setup(maxPixels = 1000L * 1000)
        val bigPng = makeImage(File(photos, "big.png"), 1200, 1000)
        assertNull(cache.get(tree.entry(bigPng), dash, CacheMode.FILL))
        assertEquals(2, tree.opens.count { it == bigPng.path }) // bounds + EXIF only: never decoded
        // A big JPEG is fine: it decodes at 1/4 scale (1000×750), under the limit
        val bigJpg = makeImage(File(photos, "big.jpg"), 4000, 3000)
        assertNotNull(cache.get(tree.entry(bigJpg), dash, CacheMode.FILL))
    }

    @Test fun jpegCompletenessCheck() { // Kotlin-only
        val (photos, _, _) = setup()
        val good = makeImage(File(photos, "g.jpg"), 64, 48, exifOrientation = 6) // EXIF segment before the scan
        assertTrue(good.inputStream().use { ImageCache.jpegComplete(it) })
        assertTrue((good.readBytes() + "trailer".toByteArray()).inputStream().use { ImageCache.jpegComplete(it) })
        assertFalse(good.readBytes().copyOf(good.length().toInt() - 2).inputStream().use { ImageCache.jpegComplete(it) })
        assertFalse("not a jpeg".byteInputStream().use { ImageCache.jpegComplete(it) })
    }
}
