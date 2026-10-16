package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Port of tests/test_photos.py. Library tests port in full; cache tests port at the level of the
 * pure rules (geometry, decode guard, key, eviction, bad-file memo). Decoding and pixel checks
 * on real files belong to M3's ImageCache (see android/docs/m2-domain.md).
 */
class PhotosTest {
    // --- library ---

    @Test fun scanCountsSupportedOnly() {
        val paths = listOf("a.jpg", "b.JPEG", "c.png", "sub/d.jpg", "notes.txt", ".hidden.jpg", "e.webp", ".git/x.jpg")
        assertEquals(4, paths.count { isPhotoPath(it, webp = false) })
        assertEquals(5, paths.count { isPhotoPath(it, webp = true) })
    }

    @Test fun shuffleCoversAllBeforeRepeat() {
        val names = (0 until 7).map { "p$it.jpg" }.toSet()
        val q = PhotoQueue(Random(1))
        q.update(names)
        repeat(3) { // three full rounds
            assertEquals(names, (0 until 7).map { q.next() }.toSet())
        }
    }

    @Test fun noImmediateRepeatBetweenRounds() {
        for (seed in 0 until 30) {
            val q = PhotoQueue(Random(seed))
            q.update(setOf("p0.jpg", "p1.jpg", "p2.jpg"))
            val seq = (0 until 30).map { q.next() }
            assertTrue(seq.zipWithNext().all { (a, b) -> a != b })
        }
    }

    @Test fun newFileFoundOnRescan() {
        val q = PhotoQueue(Random(2))
        assertEquals(1, q.update(setOf("a.jpg")))
        assertEquals("a.jpg", q.next())
        assertEquals(1, q.update(setOf("b.jpg"))) // b added, a removed
        assertEquals(listOf("b.jpg", "b.jpg", "b.jpg"), (0 until 3).map { q.next() })
    }

    @Test fun emptyFolderNextIsNull() {
        val q = PhotoQueue()
        assertEquals(0, q.update(emptySet()))
        assertNull(q.next())
    }

    @Test fun newPhotosJoinTheCurrentRound() { // Kotlin-only
        val q = PhotoQueue(Random(3))
        q.update((0 until 5).map { "p$it.jpg" }.toSet())
        val first = (0 until 2).map { q.next() }
        q.update((0 until 6).map { "p$it.jpg" }.toSet())
        val rest = (0 until 4).map { q.next() }
        assertEquals((0 until 6).map { "p$it.jpg" }.toSet(), (first + rest).toSet())
    }

    // --- cache rules ---

    @Test fun cacheOutputSizeExact() {
        for (src in listOf(PxSize(1600, 900), PxSize(900, 1600))) {
            val dst = PxSize(664, 600)
            val fill = fillPlan(src, dst)
            assertEquals(dst, PxSize(fill.crop.w, fill.crop.h))
            assertTrue(fill.crop.x + fill.crop.w <= fill.scaled.w && fill.crop.y + fill.crop.h <= fill.scaled.h)
            val canvas = PxSize(1280, 800)
            val fit = fitPlan(src, canvas)
            assertTrue(fit.photo.x >= 0 && fit.photo.x + fit.photo.w <= canvas.w)
            assertTrue(fit.photo.y >= 0 && fit.photo.y + fit.photo.h <= canvas.h)
            assertTrue(fit.photo.w == canvas.w || fit.photo.h == canvas.h) // touches two edges
        }
    }

    @Test fun cacheHitReusesFileAndSourceChangeMisses() {
        val key = cacheKey("doc:a.jpg", 100, 2000, PxSize(664, 600), CacheMode.FILL)
        assertEquals(key, cacheKey("doc:a.jpg", 100, 2000, PxSize(664, 600), CacheMode.FILL))
        assertNotEquals(key, cacheKey("doc:a.jpg", 100, 2000, PxSize(1280, 800), CacheMode.FIT))
        assertNotEquals(key, cacheKey("doc:a.jpg", 1, 2000, PxSize(664, 600), CacheMode.FILL)) // edited photo
        assertTrue(Regex("[0-9a-f]{40}\\.jpg").matches(key))
    }

    @Test fun exifRotatedImageUpright() {
        // Stored 600x800 with orientation 6 displays as 800x600 landscape
        val shown = orientedSize(PxSize(600, 800), 6)
        assertEquals(PxSize(800, 600), shown)
        val photo = fitPlan(shown, PxSize(1280, 800)).photo
        assertEquals(PxRect(106, 0, 1067, 800), photo) // bars ~107 px, not 340 as for portrait
        assertTrue(200 in photo.x until photo.x + photo.w) // pixel (200, 400) is photo, not backdrop
        for (o in listOf(null, 1, 2, 3, 4)) assertEquals(PxSize(600, 800), orientedSize(PxSize(600, 800), o))
    }

    @Test fun fillPortraitKeepsTop() {
        val plan = fillPlan(PxSize(600, 1200), PxSize(664, 600))
        assertEquals(0, plan.crop.y) // crop starts at the photo's top edge
        val scale = plan.scaled.h / 1200.0
        assertTrue(100 * scale >= plan.crop.y && 300 * scale <= plan.crop.y + plan.crop.h) // top-third band in view
    }

    @Test fun fillLandscapeIsCentered() {
        val plan = fillPlan(PxSize(2000, 600), PxSize(664, 600))
        assertEquals(PxSize(2000, 600), plan.scaled)
        assertEquals(668, plan.crop.x)
        assertTrue(332 + plan.crop.x in 990 until 1010) // centre stripe at output x 332
    }

    @Test fun fitBackdropNotBlack() {
        val fit = fitPlan(PxSize(600, 900), PxSize(1280, 800))
        assertTrue(fit.photo.x > 0) // side bars show the backdrop …
        assertEquals(PxSize(160, 100), fit.backdropSmall) // … made at 1/8 size from the photo itself
        assertEquals(PxRect(0, (fit.backdrop.scaled.h - 100) / 2, 160, 100), fit.backdrop.crop) // centered, not top
        assertTrue(BACKDROP_BRIGHTNESS > 0.0 && BACKDROP_BRIGHTNESS < 1.0) // darker than the photo, not black
        assertEquals(3.0, BACKDROP_BLUR, 0.0)
    }

    @Test fun corruptFileReturnsNull() { // reported once per file version
        val bad = BadSources()
        val logged = (0 until 3).count { bad.markBad("bad.jpg", 1, 10) }
        assertEquals(1, logged)
        assertTrue(bad.isBad("bad.jpg", 1, 10))
        assertFalse(bad.isBad("bad.jpg", 2, 10)) // replaced file is tried again
    }

    @Test fun evictionUnderLimit() {
        val one = 1000L
        val entries = listOf(
            CacheEntry("n0", lastUsed = 1, bytes = one),
            CacheEntry("n2", lastUsed = 3, bytes = one),
            CacheEntry("n1", lastUsed = 4, bytes = one), // touched after n2
            CacheEntry("n3", lastUsed = 5, bytes = one), // just written
        )
        val limit = (one * 2.5).toLong()
        val gone = evictionPlan(entries, limit, keep = "n3")
        assertEquals(listOf("n0", "n2"), gone) // least recently used first
        assertTrue(entries.filter { it.name !in gone }.sumOf { it.bytes } <= limit)
        assertEquals(emptyList<String>(), evictionPlan(entries, 10 * one, keep = "n3"))
        assertEquals(listOf("n0"), evictionPlan(listOf(entries[0], entries[3]), one, keep = "n3"))
    }

    @Test fun sameMtimeReplacementMissesCache() {
        assertNotEquals(
            cacheKey("doc:a.png", 77, 1234, PxSize(664, 600), CacheMode.FILL),
            cacheKey("doc:a.png", 77, 5678, PxSize(664, 600), CacheMode.FILL),
        )
    }

    @Test fun oversizedImageRejectedBeforeDecoding() {
        val target = PxSize(664, 600)
        assertNull(decodePlan(PxSize(1200, 1000), target, isJpeg = false, exifOrientation = null, maxPixels = 1_000_000))
        // A big JPEG is fine: it decodes at reduced scale, under the limit
        val jpeg = decodePlan(PxSize(4000, 3000), target, isJpeg = true, exifOrientation = null, maxPixels = 1_000_000)
        assertNotNull(jpeg)
        assertEquals(DecodePlan(4, PxSize(1000, 750)), jpeg)
    }

    @Test fun decodeSampleRespectsTargetAndRotation() { // Kotlin-only
        // Rotated 90°: the 1280x800 target is compared against stored 3000x4000 as 800x1280
        assertEquals(2, decodePlan(PxSize(3000, 4000), PxSize(1280, 800), isJpeg = true, exifOrientation = 6)!!.sampleSize)
        assertEquals(1, decodePlan(PxSize(500, 400), PxSize(1280, 800), isJpeg = true, exifOrientation = null)!!.sampleSize)
        assertEquals(PxSize(1000, 750), decodePlan(PxSize(1000, 750), PxSize(1280, 800), isJpeg = false, exifOrientation = null)!!.decoded)
    }
}
