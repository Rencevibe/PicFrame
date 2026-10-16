package io.github.rencevibe.picframe.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Foreground service (type systemExempted, V3) that rings on the alarm stream and posts the
 *  full-screen notification that opens RingActivity. Stops itself after 5 minutes. */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val due = intent?.getLongExtra(Spike.EXTRA_DUE, 0L) ?: 0L
        val notification = buildNotification(due)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Spike.log(this, "FOREGROUND STARTED ${Spike.state(this)}")
        } catch (e: Exception) {
            Spike.log(this, "FOREGROUND FAILED ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        startSound(due)
        handler.postDelayed({ stopSelf() }, AUTO_STOP_MS)
        return START_NOT_STICKY
    }

    private fun buildNotification(due: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Spike alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // sound comes from MediaPlayer on the alarm stream
            },
        )
        val ring = Intent(this, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(Spike.EXTRA_DUE, due)
        val fullScreen = PendingIntent.getActivity(
            this, 2, ring, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, RingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("PicFrame spike alarm")
            .setContentText("Due ${Spike.stamp(due)}")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startSound(due: Long) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                setWakeMode(this@RingService, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
            val now = System.currentTimeMillis()
            Spike.log(this, "SOUND PLAYING usage=ALARM delta_ms=${if (due > 0) now - due else "?"} uri=$uri ${Spike.state(this)}")
        } catch (e: Exception) {
            Spike.log(this, "SOUND FAILED ${e.javaClass.simpleName}: ${e.message} uri=$uri")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        Spike.log(this, "RING STOPPED")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.rencevibe.picframe.spike.STOP"
        private const val CHANNEL = "spike_alarm"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_STOP_MS = 5 * 60 * 1000L
    }
}
