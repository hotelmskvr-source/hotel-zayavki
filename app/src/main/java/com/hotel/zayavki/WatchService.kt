package com.hotel.zayavki

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
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val ACTION_STOP_ALARM = "com.hotel.zayavki.STOP_ALARM"
const val ACTION_REFRESH = "com.hotel.zayavki.REFRESH"
private const val ALARM_NOTIF = 9001

// Раз в 30 секунд спрашивает у Supabase список заявок и сравнивает с прошлым разом.
class WatchService : Service() {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var token = ""
    private var uid = ""
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val ringing = mutableSetOf<Int>()
    private val stopRunnable = Runnable { stopAlarm() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Слежение за заявками", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("watch_min", "Слежение (скрытое)", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("tickets", "Заявки", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alarm", "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        val n = watchNotif()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        ex.scheduleWithFixedDelay({ try { poll() } catch (e: Exception) { } }, 1, 30, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) stopAlarm()
                if (intent?.action == ACTION_REFRESH) {
            val n = watchNotif()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        }
        return START_STICKY
    }

    override fun onDestroy() { ex.shutdownNow(); stopAlarm(); super.onDestroy() }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // Плашка слежения: обычная или скрытая (без значка в строке состояния), по переключателю в «Ещё»
    private fun watchNotif(): android.app.Notification {
        val show = Prefs.bool(this, "show_watch", true)
        return NotificationCompat.Builder(this, if (show) "watch" else "watch_min").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Заявки").setContentText("Слежение за заявками включено")
            .setPriority(if (show) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp()).setOngoing(true).build()
    }

    // ---------- сеть ----------
    private fun http(method: String, url: String, headers: Map<String, String>, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 15000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return code to text
    }

    private fun signIn(l: Login): Boolean {
        val (code, t) = http("POST", "${l.url}/auth/v1/token?grant_type=password",
            mapOf("apikey" to l.key, "Content-Type" to "application/json"),
            JSONObject().put("email", if (l.email.contains("@")) l.email else l.email).put("password", l.pw).toString())
        if (code != 200) return false
        val j = JSONObject(t)
        token = j.getString("access_token"); uid = j.getJSONObject("user").getString("id")
        return true
    }

    private fun get(l: Login, path: String): String? {
        if (token.isEmpty() && !signIn(l)) return null
        var r = http("GET", "${l.url}/rest/v1/$path", mapOf("apikey" to l.key, "Authorization" to "Bearer $token"))
        if (r.first == 401) {
            if (!signIn(l)) return null
            r = http("GET", "${l.url}/rest/v1/$path", mapOf("apikey" to l.key, "Authorization" to "Bearer $token"))
        }
        return if (r.first == 200) r.second else null
    }

    // ---------- проверка заявок ----------
    private fun poll() {
        val l = Prefs.login(this) ?: run { stopSelf(); return }
        val since = Instant.now().minusSeconds(7 * 86400).toString()
        val raw = get(l, "tickets?select=id,status,category,subtopic,location,urgent,created_at,assignee_id,author:profiles!tickets_author_id_fkey(full_name)" +
            "&or=(status.in.(new,in_progress),created_at.gte.$since)&order=created_at.desc&limit=200") ?: return
        val prof = get(l, "profiles?id=eq.$uid&select=role,notify_new,notify_taken,notify_done,alarm_on,alarm_minutes")
            ?.let { JSONArray(it).optJSONObject(0) } ?: return
        val isAdmin = prof.optString("role") == "admin"
        val oldRaw = Prefs.str(this, "snap")
        val first = oldRaw == null            // первый запуск: только запоминаем, чтобы не засыпать уведомлениями
        val old = JSONObject(oldRaw ?: "{}")
        val arr = JSONArray(raw); val snap = JSONObject(); val newIds = mutableSetOf<Int>()
        val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val id = t.getInt("id"); val st = t.getString("status"); snap.put(id.toString(), st)
            val sub = t.optString("subtopic").let { if (it.isNotEmpty() && it != "null") " · $it" else "" }
            val text = t.getString("category") + sub + ", " + t.optString("location")
            val author = t.optJSONObject("author")?.optString("full_name") ?: ""
            val created = OffsetDateTime.parse(t.getString("created_at")).toInstant().toEpochMilli()
            val prev = if (old.has(id.toString())) old.getString(id.toString()) else null
            val mine = t.optString("assignee_id") == uid
            if (isAdmin && !first) {
                if (prev == null && st == "new") {
                    if (now - created < 6 * 3600_000L && prof.optBoolean("notify_new", true))
                        notifyUser(id, if (t.optBoolean("urgent")) "🔴 СРОЧНО: новая заявка" else "Новая заявка", "$text. $author")
                } else if (prev != null && prev != st) {
                    when (st) {
                        "in_progress" -> if (!mine && prof.optBoolean("notify_taken", true)) notifyUser(id, "Заявка взята в работу", text)
                        "done" -> if (!mine && prof.optBoolean("notify_done", true)) notifyUser(id, "Заявка выполнена", text)
                        "cancelled" -> if (prof.optBoolean("notify_new", true)) notifyUser(id, "Заявка отменена", text)
                    }
                }
            }
            if (st == "new") {
                newIds.add(id)
                if (isAdmin && prof.optBoolean("alarm_on", true)) {
                    val ageMin = (now - created) / 60000
                    val last = Prefs.long(this, "alarm_$id")
                    // срабатывает через alarm_minutes после создания и повторяется каждые 10 минут, пока заявку не взяли
                    if (ageMin >= prof.optInt("alarm_minutes", 30) && now - last >= 10 * 60000L) {
                        Prefs.putLong(this, "alarm_$id", now); ringing.add(id)
                        startAlarm("Заявка без ответа $ageMin мин: $text")
                    }
                }
            }
        }
        ringing.retainAll(newIds)
        if (ringing.isEmpty()) stopAlarm()
        Prefs.putStr(this, "snap", snap.toString())
    }

    private fun notifyUser(id: Int, title: String, body: String) {
        val n = NotificationCompat.Builder(this, "tickets").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(openApp()).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        getSystemService(NotificationManager::class.java).notify((title + id).hashCode(), n)
    }

    // ---------- будильник: обычный звук будильника + вибрация ----------
    @Synchronized private fun startAlarm(text: String) {
        val stopPi = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "alarm").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⏰ Будильник").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true).setContentIntent(openApp()).addAction(0, "Выключить", stopPi).build()
        getSystemService(NotificationManager::class.java).notify(ALARM_NOTIF, n)
        if (player != null) return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@WatchService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { player = null }
        vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800), 0))
        handler.postDelayed(stopRunnable, 120_000) // сам замолчит через 2 минуты, если не выключили
    }

    @Synchronized private fun stopAlarm() {
        handler.removeCallbacks(stopRunnable)
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        player = null
        vibrator?.cancel(); vibrator = null
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIF)
    }
}
