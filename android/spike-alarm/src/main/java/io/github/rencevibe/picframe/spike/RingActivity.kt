package io.github.rencevibe.picframe.spike

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The alarm screen, opened by the full-screen notification. Logs whether it appeared over the lock screen. */
class RingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val due = intent.getLongExtra(Spike.EXTRA_DUE, 0L)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        Spike.log(this, "RING SCREEN SHOWN overLockScreen=$locked due=${Spike.stamp(due)}")

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "ALARM\ndue ${Spike.stamp(due)}"
                textSize = 40f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Stop"
                setOnClickListener {
                    startService(Intent(context, RingService::class.java).setAction(RingService.ACTION_STOP))
                    finish()
                }
            })
        })
    }
}
