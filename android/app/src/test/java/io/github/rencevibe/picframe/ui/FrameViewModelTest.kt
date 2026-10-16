package io.github.rencevibe.picframe.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.rencevibe.picframe.domain.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The view-model wiring (M4): first-run setup, touch semantics, back, folder and location flows. */
@RunWith(RobolectricTestRunner::class)
class FrameViewModelTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val measurer = TextMeasurer(createFontFamilyResolver(app), Density(1f), LayoutDirection.Ltr, 128)

    private val made = mutableListOf<FrameViewModel>()

    @org.junit.After fun closeAll() = kotlinx.coroutines.runBlocking { made.forEach { it.closeAndJoin() } }

    private fun ready(vm: FrameViewModel): FrameViewModel {
        made += vm
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.ready) return vm
            Thread.sleep(10)
        }
        error("view-model never loaded its settings")
    }

    /** Draws one frame (so the touch targets exist) and returns them. */
    private fun frame(vm: FrameViewModel): List<Hit> {
        vm.tick()
        val img = ImageBitmap(1280, 800)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(1280f, 800f)) {
            vm.hits = vm.render(Ink(this, measurer))
        }
        return vm.hits
    }

    private fun tapOn(vm: FrameViewModel, action: String) {
        val hit = frame(vm).first { it.action == action }
        vm.onDown(hit.rect.center)
        vm.onUp(hit.rect.center)
    }

    @Test fun firstRunShowsSetupAndSkipIsRemembered() {
        File(app.filesDir, "datastore").deleteRecursively()
        val vm = ready(FrameViewModel(app))
        assertTrue(vm.showSetup)
        assertTrue(frame(vm).any { it.action == "setup_pick_folder" })
        tapOn(vm, "setup_pick_folder")
        assertEquals("pick_folder", vm.takeRequest())
        tapOn(vm, "setup_skip")
        assertFalse(vm.showSetup)
        assertEquals(Mode.DASHBOARD, vm.controller.mode)
        // "Remembered": a new instance (app restart) doesn't show setup again
        repeat(100) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } // let the IO write finish
        kotlinx.coroutines.runBlocking { vm.closeAndJoin() }
        made.remove(vm)
        assertFalse(ready(FrameViewModel(app)).showSetup)
    }

    @Test fun cancelledTouchDoesNotFire() { // regression: a cancelled gesture used to count as a release
        val vm = ready(FrameViewModel(app))
        if (vm.showSetup) tapOn(vm, "setup_skip")
        val toPhoto = frame(vm).first { it.action == "to_photo" }
        vm.onDown(toPhoto.rect.center)
        assertEquals("to_photo", vm.pressed)
        vm.onCancel()
        assertNull(vm.pressed)
        vm.onUp(toPhoto.rect.center) // a stray release after the cancel does nothing
        assertEquals(Mode.DASHBOARD, vm.controller.mode)
    }

    @Test fun tapFiresOnReleaseInsideOnlyAndBackLeavesModes() {
        val vm = ready(FrameViewModel(app))
        if (vm.showSetup) tapOn(vm, "setup_skip")
        val toPhoto = frame(vm).first { it.action == "to_photo" }
        vm.onDown(toPhoto.rect.center)
        assertEquals("to_photo", vm.pressed) // pressed feedback on finger-down
        vm.onMove(Offset(10f, 10f)) // slid off
        assertNull(vm.pressed)
        vm.onUp(Offset(10f, 10f))
        assertEquals(Mode.DASHBOARD, vm.controller.mode) // released outside: cancelled
        tapOn(vm, "to_photo")
        assertEquals(Mode.PHOTO, vm.controller.mode)
        vm.onBack()
        assertEquals(Mode.DASHBOARD, vm.controller.mode)
        tapOn(vm, "open_settings")
        assertEquals(Mode.SETTINGS, vm.controller.mode)
        tapOn(vm, "page_frame")
        vm.onBack()
        assertEquals("main", vm.controller.settingsPage) // back from the Frame page goes to the main page
        vm.onBack()
        assertEquals(Mode.DASHBOARD, vm.controller.mode) // back from Settings = Done
    }

    @Test fun pickersUpdateTheConfig() {
        val vm = ready(FrameViewModel(app))
        if (vm.showSetup) tapOn(vm, "setup_skip")
        tapOn(vm, "open_settings")
        tapOn(vm, "page_frame")
        tapOn(vm, "edit_location")
        vm.tick()
        assertEquals("edit_location", vm.takeRequest())
        vm.onLocation(47.61, -122.33)
        assertEquals(47.61, vm.cfg.lat!!, 0.0)
        vm.onLocation(47.0, null) // only one of the pair: ignored
        assertEquals(-122.33, vm.cfg.lon!!, 0.0)
        vm.onSoundPicked("content://media/internal/audio/media/9", picked = true)
        assertEquals("content://media/internal/audio/media/9", vm.cfg.alarmSound)
        vm.onSoundPicked(null, picked = false) // picker cancelled: unchanged
        assertEquals("content://media/internal/audio/media/9", vm.cfg.alarmSound)
        vm.onFolderPicked("content://com.android.externalstorage.documents/tree/primary%3APictures")
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3APictures", vm.cfg.photoDir)
        assertEquals(Mode.SETTINGS, vm.controller.mode) // the picker didn't close Settings
    }

    @Test fun brightnessFollowsSettingsAndNight() {
        val vm = ready(FrameViewModel(app))
        vm.cfg.brightness = 60
        assertEquals(0.6f, vm.brightness, 0.001f)
        vm.controller.mode = Mode.DARK
        assertEquals(0.01f, vm.brightness, 0.001f)
        assertTrue(vm.isBlack || vm.showSetup)
    }
}
