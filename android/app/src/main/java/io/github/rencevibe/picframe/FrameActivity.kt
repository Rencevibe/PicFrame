package io.github.rencevibe.picframe

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.rencevibe.picframe.ui.FrameScreen
import io.github.rencevibe.picframe.ui.FrameViewModel
import io.github.rencevibe.picframe.ui.LocationDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The frame: landscape (manifest), screen kept on, system bars hidden; drives the view-model's
 *  tick while started and opens the system pickers it asks for. */
class FrameActivity : ComponentActivity() {
    private val vm: FrameViewModel by viewModels()
    private var showLocation by mutableStateOf(false)
    private var appliedBrightness = -1f

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // provider doesn't offer persistable access; works until restart
            }
        }
        vm.onFolderPicked(uri?.toString())
    }

    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.resultCode == RESULT_OK
        val uri: Uri? = result.data?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
        }
        // The "Default" entry means the device default: stored as null (D4)
        val chosen = uri?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString()
        vm.onSoundPicked(chosen, picked && uri != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { vm.onBack() }
        setContent {
            FrameScreen(vm)
            if (showLocation) {
                LocationDialog(vm.cfg.lat, vm.cfg.lon) { lat, lon, save ->
                    showLocation = false
                    if (save) vm.onLocation(lat, lon) else vm.controller.touchSettings()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.tick()
                    vm.takeRequest()?.let(::open)
                    applyBrightness(vm.brightness)
                    delay(if (vm.busy) 33L else 100L) // 30 fps during fades and the alarm, else 10
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.onStopped()
    }

    private fun open(request: String) {
        when (request) {
            "pick_folder" -> pickFolder.launch(null)
            "edit_location" -> showLocation = true
            "pick_sound" -> pickSound.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        vm.cfg.alarmSound?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                    ),
            )
        }
    }

    /** Window brightness, no system permission (plan §3: replaces brightness_cmd and screen_power). */
    private fun applyBrightness(value: Float) {
        if (value == appliedBrightness) return
        appliedBrightness = value
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
