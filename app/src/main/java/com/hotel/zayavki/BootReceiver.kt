package com.hotel.zayavki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.login(c) != null) ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
    }
}
