package io.github.rencevibe.picframe.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * Alarm tone on the alarm stream (D4: the device's default alarm tone unless the user picked
 * another). Used while the frame is on screen; the background alarm path waits for M1.
 * If nothing can play, the alarm stays visual only, as on the Pi.
 */
class AlarmSound(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(chosen: String?) {
        stop()
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        for (uri in listOfNotNull(chosen?.let(Uri::parse), default).distinct()) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) { // tone deleted, no audio device …
                Log.w(TAG, "alarm sound $uri failed ($e)")
                player?.release()
                player = null
            }
        }
        Log.w(TAG, "no alarm sound; alarm is visual only")
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            it.release()
        }
        player = null
    }

    /** Display name of a tone, or "Device default". */
    fun title(chosen: String?): String {
        if (chosen == null) return "Device default"
        return try {
            RingtoneManager.getRingtone(context, Uri.parse(chosen))?.getTitle(context) ?: "Chosen tone"
        } catch (e: Exception) {
            "Chosen tone"
        }
    }

    private companion object {
        const val TAG = "PicFrame"
    }
}
