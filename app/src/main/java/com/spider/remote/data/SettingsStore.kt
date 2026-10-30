package com.spider.remote.data

import android.content.Context

/** تخزين محلي بسيط لإعدادات الجهاز. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("spider_remote", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v).apply()

    var ports: String
        get() = prefs.getString("ports", "80, 8080, 23, 554") ?: "80, 8080, 23, 554"
        set(v) = prefs.edit().putString("ports", v).apply()
}
