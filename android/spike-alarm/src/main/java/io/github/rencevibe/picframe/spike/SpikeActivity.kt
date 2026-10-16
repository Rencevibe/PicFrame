package io.github.rencevibe.picframe.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Test console: grant permissions, set an alarm N minutes ahead, read the evidence log. */
class SpikeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        column.addView(status)

        fun button(label: String, action: () -> Unit) =
            column.addView(Button(this).apply { text = label; setOnClickListener { action(); refresh() } })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            button("Allow notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            button("Allow exact alarms") { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            button("Allow full-screen alarms") { openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
        }
        for (minutes in listOf(1, 3, 5, 10)) {
            button("Ring in $minutes min") { Spike.schedule(this, System.currentTimeMillis() + minutes * 60_000L) }
        }
        button("Log current state") { Spike.log(this, "STATE ${Spike.state(this)}") }
        button("Clear log") { Spike.clearLog(this) }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openSettings(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        status.text = "${Spike.state(this)}\npending=${Spike.stamp(Spike.pending(this))}"
        log.text = Spike.readLog(this)
    }
}
