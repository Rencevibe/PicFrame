package io.github.rencevibe.picframe.spike

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scheduling and evidence log shared by the spike components. Storage is device-protected,
 *  so it works before the first unlock after a reboot. */
object Spike {
    const val TAG = "PicFrameSpike"
    const val EXTRA_DUE = "due"
    private const val KEY_TRIGGER = "trigger_at"
    private const val KEY_LOG = "log"

    private fun prefs(c: Context): SharedPreferences =
        c.createDeviceProtectedStorageContext().getSharedPreferences("spike", Context.MODE_PRIVATE)

    fun stamp(ms: Long): String =
        if (ms <= 0) "?" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    /** Writes to logcat (tag PicFrameSpike) and to the in-app log, newest first. */
    fun log(c: Context, msg: String) {
        val line = "${stamp(System.currentTimeMillis())} $msg"
        Log.i(TAG, line)
        val p = prefs(c)
        p.edit().putString(KEY_LOG, (line + "\n" + p.getString(KEY_LOG, "")).take(20_000)).commit()
    }

    fun readLog(c: Context): String = prefs(c).getString(KEY_LOG, "") ?: ""

    fun clearLog(c: Context) {
        prefs(c).edit().remove(KEY_LOG).commit()
    }

    fun pending(c: Context): Long = prefs(c).getLong(KEY_TRIGGER, 0L)

    fun clearPending(c: Context) {
        prefs(c).edit().remove(KEY_TRIGGER).commit()
    }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canFullScreen(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** One line describing every permission and state the pass conditions depend on. */
    fun state(c: Context): String {
        val audio = c.getSystemService(AudioManager::class.java)
        val alarmVol = "${audio.getStreamVolume(AudioManager.STREAM_ALARM)}/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}"
        val mediaVol = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        return "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "canExact=${canExact(c)} canFullScreen=${canFullScreen(c)} " +
            "notifications=${c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()} " +
            "locked=${c.getSystemService(KeyguardManager::class.java).isKeyguardLocked} " +
            "userUnlocked=${c.getSystemService(UserManager::class.java).isUserUnlocked} " +
            "alarmVol=$alarmVol mediaVol=$mediaVol"
    }

    fun schedule(c: Context, triggerAt: Long) {
        prefs(c).edit().putLong(KEY_TRIGGER, triggerAt).commit()
        register(c, triggerAt, "set")
    }

    /** setAlarmClock with the plan's exact-alarm path; a SecurityException is evidence, not a crash. */
    fun register(c: Context, triggerAt: Long, reason: String) {
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, SpikeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val fire = PendingIntent.getBroadcast(
            c, 1, Intent(c, FireReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            c.getSystemService(AlarmManager::class.java)
                .setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fire)
            log(c, "SCHEDULED ($reason) due=${stamp(triggerAt)} ${state(c)}")
        } catch (e: SecurityException) {
            log(c, "SCHEDULE FAILED ($reason) SecurityException: ${e.message} ${state(c)}")
        }
    }
}
