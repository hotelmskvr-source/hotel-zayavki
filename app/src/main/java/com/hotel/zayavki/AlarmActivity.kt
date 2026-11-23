package com.hotel.zayavki

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class AlarmActivity : Activity() {
    private val stop = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64); setBackgroundColor(Color.parseColor("#1F4A14"))
        }
        col.addView(TextView(this).apply { text = "⏰ " + (intent.getStringExtra("title") ?: "Заявка без ответа"); textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = intent.getStringExtra("body") ?: ""; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 32, 0, 48) })
        col.addView(Button(this).apply {
            text = "Открыть заявки"
            setOnClickListener {
                getSystemService(NotificationManager::class.java).cancelAll()
                startActivity(Intent(this@AlarmActivity, MainActivity::class.java)); finish()
            }
        })
        col.addView(Button(this).apply {
            text = "Выключить"
            setOnClickListener { getSystemService(NotificationManager::class.java).cancelAll(); finish() }
        })
        setContentView(col)
        ContextCompat.registerReceiver(this, stop, IntentFilter(ACTION_STOP_ALARM), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() { unregisterReceiver(stop); super.onDestroy() }
}
