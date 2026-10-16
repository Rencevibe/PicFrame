package io.github.rencevibe.picframe.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rencevibe.picframe.data.DocumentTree
import io.github.rencevibe.picframe.data.FolderGrants
import io.github.rencevibe.picframe.data.ImageCache
import io.github.rencevibe.picframe.data.PhotoSource
import io.github.rencevibe.picframe.data.SafTree
import io.github.rencevibe.picframe.data.SettingsRepository
import io.github.rencevibe.picframe.data.TodoSource
import io.github.rencevibe.picframe.data.httpGet
import io.github.rencevibe.picframe.domain.Alarm
import io.github.rencevibe.picframe.domain.AlarmState
import io.github.rencevibe.picframe.domain.Config
import io.github.rencevibe.picframe.domain.Controller
import io.github.rencevibe.picframe.domain.Mode
import io.github.rencevibe.picframe.domain.NightController
import io.github.rencevibe.picframe.domain.TodayItem
import io.github.rencevibe.picframe.domain.WeatherService
import io.github.rencevibe.picframe.domain.daysLabel
import io.github.rencevibe.picframe.domain.fmtClock
import io.github.rencevibe.picframe.domain.visibleToday
import io.github.rencevibe.picframe.platform.AlarmSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * Wires the domain controller to storage, photos, weather, the Today list and the screens
 * (the Android counterpart of frame/app.py). The activity calls [tick] on a timer while it is
 * started, forwards touches, and fulfils [takeRequest] with system pickers.
 */
class FrameViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val clock: () -> Double = { SystemClock.elapsedRealtime() / 1000.0 }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo = SettingsRepository.create(
        File(ctx.filesDir, "datastore/settings.preferences_pb"), File(ctx.filesDir, "settings-backup.json"), ioScope,
    )

    /** Pruned inside [saveBlocking], which blocks the UI thread, so no new grant is taken meanwhile. */
    private val grants = object : FolderGrants {
        override fun held() = ctx.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        override fun release(uri: String) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                warn("folder: release failed: $e")
            }
        }
    }

    var ready by mutableStateOf(false)
        private set
    var showSetup by mutableStateOf(false)
        private set

    /** Bumped every tick; the canvas reads it so it redraws. */
    var frame by mutableIntStateOf(0)
        private set

    /** Touch targets drawn last frame, for hit-testing (design pixels). */
    var hits: List<Hit> = emptyList()

    /** The same targets as state, for the accessibility overlay (updated off the draw pass). */
    var a11yHits by mutableStateOf<List<Hit>>(emptyList())
        private set

    lateinit var cfg: Config
        private set
    private lateinit var alarm: Alarm
    lateinit var controller: Controller
        private set
    private lateinit var weather: WeatherService

    @Volatile private var tree: DocumentTree? = null
    private val photos = PhotoSource({ tree }, log = ::warn)
    private val cache = ImageCache(File(ctx.cacheDir, "photos"), 500L * MB, { e -> tree?.open(e.id) }, ::warn)
    val slides = Slideshow(photos, cache, viewModelScope, Dispatchers.IO, clock)
    private val todo = TodoSource({ tree }, ::warn)
    private val sound = AlarmSound(ctx)

    @Volatile private var items: List<TodayItem> = emptyList()
    @Volatile var folderLabel = NO_FOLDER
        private set
    private var soundFor: String? = null
    private var soundLabel = "Device default"
    private var lastRescan = Double.NEGATIVE_INFINITY
    private var lastTodo = Double.NEGATIVE_INFINITY
    private var scanning = false
    private var sounding = false
    private var request: String? = null

    var pressed: String? = null
        private set
    private var down: Pair<String?, Double>? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            loaded.warnings.forEach { warn("config: $it") }
            cfg = loaded.config
            alarm = Alarm(cfg)
            val night = NightController(cfg)
            controller = Controller(cfg, alarm, night, clock, save = ::saveBlocking, log = ::warn)
            controller.configBanner = loaded.banner
            weather = WeatherService(cfg, ::httpGet, clock = clock, log = ::warn)
            openTree()
            showSetup = cfg.photoDir == null && !loaded.setupDone
            ready = true
        }
    }

    private fun warn(msg: String) {
        Log.w(TAG, msg)
    }

    /** Controller.save runs on the UI thread (Done / idle close); DataStore writes are small. */
    private fun saveBlocking(c: Config): String? = runBlocking(Dispatchers.IO) { repo.saveAndPruneGrants(c, grants) }

    private fun openTree() {
        tree = cfg.photoDir?.let { SafTree(ctx, Uri.parse(it)) }
        if (tree == null) folderLabel = NO_FOLDER
        lastRescan = Double.NEGATIVE_INFINITY
        lastTodo = Double.NEGATIVE_INFINITY
    }

    // --- time passes ---

    val busy: Boolean get() = ready && (slides.fading || controller.mode == Mode.ALARM)

    /** Window brightness 0–1: minimum while the night screen is black (plan §3). */
    val brightness: Float get() = when {
        !ready -> 1f
        controller.mode == Mode.DARK -> 0.01f
        else -> cfg.brightness / 100f
    }

    val isBlack: Boolean get() = ready && !showSetup && (controller.mode == Mode.DARK || controller.mode == Mode.NIGHT_CLOCK)

    fun tick() {
        if (!ready) return
        val now = ZonedDateTime.now()
        controller.onTick(now)
        val ringing = alarm.state == AlarmState.RINGING
        if (ringing != sounding) {
            if (ringing) sound.play(cfg.alarmSound) else sound.stop()
            sounding = ringing
        }
        weather.update(now)
        val t = clock()
        if (t - lastRescan >= RESCAN_S) rescan()
        if (t - lastTodo >= TODO_RELOAD_S) reloadTodo()
        cache.limitBytes = cfg.cacheMb * MB
        slides.update(cfg.intervalS, cfg.fadeMs, controller.takePhotoStep())
        controller.takeRequest()?.let { request = it }
        if (cfg.alarmSound != soundFor) {
            soundFor = cfg.alarmSound
            soundLabel = sound.title(soundFor)
        }
        if (hits != a11yHits) a11yHits = hits
        frame++
    }

    private fun rescan() {
        lastRescan = clock()
        if (scanning) return
        scanning = true
        viewModelScope.launch(Dispatchers.IO) {
            photos.scan()
            tree?.let { t -> folderLabel = try { t.displayName } catch (e: Exception) { NO_ACCESS } }
            scanning = false
        }
    }

    private fun reloadTodo() {
        lastTodo = clock()
        viewModelScope.launch(Dispatchers.IO) { items = todo.load() }
    }

    /** A picker or dialog the activity should open: pick_folder, edit_location or pick_sound. */
    fun takeRequest(): String? = request.also { request = null }

    /** The activity left the foreground: nothing on screen can stop a ring, so stop the sound. */
    fun onStopped() {
        if (sounding) sound.stop()
        sounding = false
    }

    // --- results from pickers ---

    fun onFolderPicked(uri: String?) {
        if (!ready) return
        if (uri != null) {
            cfg.photoDir = uri
            openTree()
            slides.reset()
            if (showSetup) {
                saveBlocking(cfg)
                finishSetup()
            }
        }
        controller.touchSettings()
    }

    fun onLocation(lat: Double?, lon: Double?) {
        if (ready && (lat == null) == (lon == null)) {
            cfg.lat = lat
            cfg.lon = lon
        }
        controller.touchSettings()
    }

    fun onSoundPicked(uri: String?, picked: Boolean) {
        if (ready && picked) cfg.alarmSound = uri
        controller.touchSettings()
    }

    private fun finishSetup() {
        showSetup = false
        viewModelScope.launch(Dispatchers.IO) { repo.setSetupDone() }
    }

    fun onBack() {
        if (!ready || showSetup) return
        val action = when (controller.mode) {
            Mode.SETTINGS -> if (controller.settingsPage == "frame") "page_main" else "done"
            Mode.PHOTO -> "to_dashboard"
            else -> return // the frame has nowhere to go back to
        }
        tap(action, clock())
    }

    // --- touch (spec "Touch rules": pressed on down, fires on release inside the same target) ---

    private fun hitAt(p: Offset): String? = hits.firstOrNull { it.rect.contains(p) }?.action

    fun onDown(p: Offset) {
        val a = hitAt(p)
        down = a to clock()
        pressed = a
        frame++
    }

    fun onMove(p: Offset) {
        val d = down ?: return
        val now = if (hitAt(p) == d.first) d.first else null
        if (now != pressed) {
            pressed = now
            frame++
        }
    }

    fun onUp(p: Offset) {
        val d = down ?: return
        down = null
        pressed = null
        if (hitAt(p) == d.first) tap(d.first, d.second)
        frame++
    }

    /** The gesture was cancelled: clear the pressed state, fire nothing. */
    fun onCancel() {
        if (down == null && pressed == null) return
        down = null
        pressed = null
        frame++
    }

    /** Accessibility activation of a target (no finger-down time; the alarm guard still applies). */
    fun a11yTap(action: String) = tap(action, clock())

    private fun tap(action: String?, downAt: Double) {
        if (!ready) return
        val a = action?.ifEmpty { null }
        if (showSetup) {
            when (a) {
                "setup_pick_folder" -> request = "pick_folder"
                "setup_skip" -> finishSetup()
            }
            return
        }
        controller.onTap(a, ZonedDateTime.now(), downAt)
    }

    // --- drawing ---

    fun render(ink: Ink): List<Hit> {
        if (!ready) {
            ink.scope.drawRect(Palette.BG)
            return emptyList()
        }
        if (showSetup) return ink.setup(SetupVm(pressed))
        val now = ZonedDateTime.now()
        val c = cfg
        return when (controller.mode) {
            Mode.DASHBOARD -> ink.dashboard(dashboardVm(now))
            Mode.PHOTO -> ink.photoMode(photoVm(now))
            Mode.SETTINGS -> ink.settings(SettingsVm(c, controller.settingsPage, controller.saveError, pressed, folderLabel, soundLabel))
            Mode.ALARM -> {
                val (hhmm, ampm) = splitTime(now, c.clock24h)
                ink.alarmScreen(
                    AlarmVm(hhmm, ampm, now.format(LONG_DATE), c.snoozeMin, alarm.isTest, pressed),
                    (0.5 + 0.5 * cos(2 * PI * clock())).toFloat(),
                )
            }
            Mode.NIGHT_CLOCK -> {
                val (hhmm, ampm) = splitTime(now, c.clock24h)
                val label = if (c.alarmEnabled) "Alarm ${fmtClock(c.alarmTime, c.clock24h)} · ${daysLabel(c.alarmDays)}" else "Alarm off"
                ink.nightClock(NightVm(hhmm, ampm, label, pressed))
            }
            Mode.DARK -> ink.dark()
        }
    }

    private fun weatherNow(now: ZonedDateTime) = weather.current(now)

    fun dashboardVm(now: ZonedDateTime): DashboardVm {
        val c = cfg
        val (hhmm, ampm) = splitTime(now, c.clock24h)
        val (shown, more) = visibleToday(items, now)
        val w = weatherNow(now)
        val asOf = w?.takeIf { Duration.between(it.fetched.toInstant(), now.toInstant()).seconds > 3600 }
            ?.let { fmtClock(it.fetched.toLocalTime(), c.clock24h) }
        return DashboardVm(
            time = hhmm, ampm = ampm, date = now.format(LONG_DATE),
            todayItems = shown.map { TodayRow(it.at?.let { t -> fmtClock(t, c.clock24h) } ?: "", it.kind, it.title) },
            more = more, grid = monthGrid(now.toLocalDate(), c.weekStart),
            alarmText = fmtClock(c.alarmTime, c.clock24h), alarmDays = daysLabel(c.alarmDays), alarmOn = c.alarmEnabled,
            snoozed = alarm.state == AlarmState.SNOOZED,
            snoozeUntil = alarm.snoozeUntil?.let { fmtClock(it.toLocalTime(), c.clock24h) },
            countdown = alarm.countdown(now), weather = w, weatherAsOf = asOf, hasLocation = c.lat != null,
            photo = slides.layers(full = false), folderLabel = folderLabel,
            banner = controller.banner(now), pressed = pressed,
        )
    }

    fun photoVm(now: ZonedDateTime): PhotoVm {
        val c = cfg
        val (hhmm, ampm) = splitTime(now, c.clock24h)
        val b = controller.banner(now)
        return PhotoVm(
            photo = slides.layers(full = true), time = hhmm, ampm = ampm, date = now.format(SHORT_DATE),
            weather = weatherNow(now), corner = c.overlayCorner, missed = b?.takeIf { it.kind == "missed" }?.text,
            folderLabel = folderLabel, pressed = pressed, controlsVisible = controller.controlsVisible,
            hideProgress = controller.hideProgress.toFloat(),
        )
    }

    override fun onCleared() = close()

    /** Stops the sound and closes settings storage (also used by tests, which can't clear a ViewModel). */
    internal fun close() {
        sound.stop()
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** [close], then wait until settings storage has released its file (tests open a new instance). */
    internal suspend fun closeAndJoin() {
        close()
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.join()
    }

    companion object {
        private const val TAG = "PicFrame"
        const val MB = 1024L * 1024L
        const val RESCAN_S = 5 * 60.0
        const val TODO_RELOAD_S = 60.0
        const val NO_FOLDER = "No folder chosen"
        const val NO_ACCESS = "Folder unavailable — choose it again"
        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMMM d", Locale.US)

        /** "12:41" + "PM", or "12:41" + null in 24 h mode. */
        fun splitTime(now: ZonedDateTime, clock24h: Boolean): Pair<String, String?> {
            val s = fmtClock(now.toLocalTime(), clock24h)
            if (clock24h) return s to null
            val i = s.lastIndexOf(' ')
            return s.substring(0, i) to s.substring(i + 1)
        }
    }
}
