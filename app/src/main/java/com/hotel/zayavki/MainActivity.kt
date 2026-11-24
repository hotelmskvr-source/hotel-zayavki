package com.hotel.zayavki

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var web: WebView

    inner class Bridge {
        @JavascriptInterface fun saveLogin(url: String, key: String, email: String, pw: String) {
            Prefs.saveLogin(this@MainActivity, url, key, email, pw)
            runOnUiThread { startWatch() }
        }
        @JavascriptInterface fun clearLogin() {
            Prefs.clearLogin(this@MainActivity)
            stopService(Intent(this@MainActivity, WatchService::class.java))
        }
        @JavascriptInterface fun hasLogin(): Boolean = Prefs.login(this@MainActivity) != null
        @JavascriptInterface fun openWatchSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "watch_min"))
            }
        }
        @JavascriptInterface fun getShowWatch(): Boolean = Prefs.bool(this@MainActivity, "show_watch", true)
        @JavascriptInterface fun setShowWatch(v: Boolean) {
            Prefs.putBool(this@MainActivity, "show_watch", v)
            if (Prefs.login(this@MainActivity) != null) runOnUiThread {
                startService(Intent(this@MainActivity, WatchService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }

    private fun startWatch() = ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString + " HotelApp"
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == Uri.parse(Config.APP_URL).host) return false
                startActivity(Intent(Intent.ACTION_VIEW, r.url)); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl(Config.APP_URL)
        if (Prefs.login(this) != null) startWatch()
        askPermissions()
    }

    // Сначала уведомления, затем работа в фоне. Каждый шаг спрашиваем один раз.
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1); return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!Prefs.asked(this, "bat") && !pm.isIgnoringBatteryOptimizations(packageName)) {
            Prefs.setAsked(this, "bat")
            AlertDialog.Builder(this)
                .setTitle("Работа в фоне")
                .setMessage("Чтобы уведомления и будильник приходили при выключенном экране, приложению нужно разрешить работать без ограничений по батарее.\n\nНа следующем экране выберите «Разрешить» (или «Без ограничений» в настройках батареи).")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r); askPermissions()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
