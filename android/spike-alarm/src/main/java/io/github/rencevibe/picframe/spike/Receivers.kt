package io.github.rencevibe.picframe.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The alarm itself: records how late it fired, then starts the ringing service. */
class FireReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val due = Spike.pending(c)
        Spike.clearPending(c)
        Spike.log(c, "FIRED due=${Spike.stamp(due)} delta_ms=${if (due > 0) now - due else "?"} ${Spike.state(c)}")
        try {
            c.startForegroundService(Intent(c, RingService::class.java).putExtra(Spike.EXTRA_DUE, due))
        } catch (e: Exception) {
            Spike.log(c, "RING SERVICE START FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/** Boot (including before first unlock), app update and exact-alarm grant: re-register the pending alarm. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val action = intent.action ?: return
        val due = Spike.pending(c)
        Spike.log(c, "RECEIVED $action pending=${Spike.stamp(due)}")
        if (due > 0) Spike.register(c, due, action.substringAfterLast('.'))
    }
}
