package com.hotel.zayavki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

const val ACTION_STOP_ALARM = "com.hotel.zayavki.STOP_ALARM"
private const val ALARM_BASE = 1_000_000

class MyFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Prefs.setToken(this, token) }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        val type = d["type"] ?: return
        val id = d["ticket_id"]?.toIntOrNull() ?: 0
        val title = d["title"] ?: "Заявки"
        val body = d["body"] ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        channels(nm)
        when (type) {
            "cancel" -> { nm.cancel(ALARM_BASE + id); sendBroadcast(Intent(ACTION_STOP_ALARM).setPackage(packageName)) }
            "alarm" -> alarm(nm, id, title, body)
            else -> normal(nm, id, title, body)
        }
    }

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel("tickets", "Заявки", NotificationManager.IMPORTANCE_HIGH))
        val a = NotificationChannel("alarm", "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(a)
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun normal(nm: NotificationManager, id: Int, title: String, body: String) {
        val n = NotificationCompat.Builder(this, "tickets").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(open()).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        nm.notify(id, n)
    }

    private fun alarm(nm: NotificationManager, id: Int, title: String, body: String) {
        val full = PendingIntent.getActivity(
            this, id, Intent(this, AlarmActivity::class.java).putExtra("title", title).putExtra("body", body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "alarm").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setAutoCancel(true).setContentIntent(open()).setFullScreenIntent(full, true).build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(ALARM_BASE + id, n)
    }
}
