package com.hotel.zayavki

import android.content.Context

data class Login(val url: String, val key: String, val email: String, val pw: String)

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun login(c: Context): Login? {
        val s = p(c); val u = s.getString("url", null) ?: return null
        return Login(u, s.getString("key", "") ?: "", s.getString("email", "") ?: "", s.getString("pw", "") ?: "")
    }
    fun saveLogin(c: Context, url: String, key: String, email: String, pw: String) =
        p(c).edit().putString("url", url).putString("key", key).putString("email", email).putString("pw", pw).remove("snap").apply()
    fun clearLogin(c: Context) = p(c).edit().remove("url").remove("key").remove("email").remove("pw").remove("snap").apply()
    fun str(c: Context, k: String): String? = p(c).getString(k, null)
    fun putStr(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun long(c: Context, k: String): Long = p(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()
    fun asked(c: Context, k: String) = p(c).getBoolean(k, false)
    fun setAsked(c: Context, k: String) = p(c).edit().putBoolean(k, true).apply()
}
