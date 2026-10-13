package com.venice.camstream

import android.content.Context

class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("camstream", Context.MODE_PRIVATE)

    var deviceId: String
        get() = sp.getString("deviceId", null) ?: newId().also { deviceId = it }
        set(v) = sp.edit().putString("deviceId", v.trim()).apply()

    var relayUrl: String
        get() = sp.getString("relayUrl", "") ?: ""
        set(v) = sp.edit().putString("relayUrl", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 12)
        set(v) = sp.edit().putInt("fps", v).apply()

    var quality: Int
        get() = sp.getInt("quality", 70)
        set(v) = sp.edit().putInt("quality", v).apply()

    var frontCamera: Boolean
        get() = sp.getBoolean("front", false)
        set(v) = sp.edit().putBoolean("front", v).apply()

    private fun newId(): String = "dev-" + (1000..9999).random()
}