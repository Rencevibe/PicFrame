package io.github.rencevibe.picframe.ui

import io.github.rencevibe.picframe.data.FileTree
import io.github.rencevibe.picframe.data.ImageCache
import io.github.rencevibe.picframe.data.PhotoSource
import io.github.rencevibe.picframe.data.makeImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/** Slideshow transitions with a deterministic test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SlideshowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = TestScope()
    private val io = StandardTestDispatcher(scope.testScheduler)
    private var t = 100.0

    private fun slideshow(): Slideshow {
        val photos = tmp.newFolder("photos")
        (0 until 3).forEach { makeImage(File(photos, "p$it.jpg"), 320, 240) }
        val tree = FileTree(photos)
        val source = PhotoSource({ tree }, Random(1)).also { it.scan() }
        val cache = ImageCache(File(tmp.root, "cache"), 50L * 1024 * 1024, { tree.open(it.id) })
        return Slideshow(source, cache, scope, io) { t }
    }

    private fun Slideshow.firstPhoto() {
        update(30, 0, 0) // starts loading the first photo
        scope.advanceUntilIdle()
        update(30, 0, 0) // shows it
        assertEquals(1, layers(full = false).size)
    }

    @Test fun nextShowsAnotherPhotoAndPrevGoesBack() {
        val s = slideshow()
        s.firstPhoto()
        val first = s.layers(false).single().image
        s.update(30, 0, 1)
        scope.advanceUntilIdle()
        val second = s.layers(false).single().image
        assertTrue(first !== second)
        s.update(30, 0, -1)
        scope.advanceUntilIdle()
        assertEquals(1, s.layers(false).size) // back to a photo, as a cut (no fade layers)
    }

    @Test fun resetDropsAJumpInFlight() { // regression: a stale jump showed a photo from the old folder
        val s = slideshow()
        s.firstPhoto()
        s.update(30, 0, 1) // Next: preparing on the IO dispatcher …
        s.reset() // … when a new folder is picked
        scope.advanceUntilIdle()
        assertEquals(0, s.layers(false).size)
        s.update(30, 0, 1) // and a new jump isn't blocked by the dropped one
        scope.advanceUntilIdle()
        assertEquals(1, s.layers(false).size)
    }

    @Test fun fadeBlendsTwoLayersThenSettles() {
        val s = slideshow()
        s.firstPhoto()
        t += 25.0 // 5 s before the switch: preload starts
        s.update(30, 1000, 0)
        scope.advanceUntilIdle()
        t += 5.0
        s.update(30, 1000, 0) // due: switch with a 1 s fade
        t += 0.5
        val mid = s.layers(false)
        assertEquals(2, mid.size)
        assertEquals(0.5f, mid[1].alpha, 0.01f)
        t += 0.6
        s.update(30, 1000, 0)
        assertEquals(1, s.layers(false).size)
    }
}
