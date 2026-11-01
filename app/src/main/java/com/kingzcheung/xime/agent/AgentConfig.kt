package com.kingzcheung.xime.agent

import android.content.Context

/** Minimal local Agent endpoint configuration. The API key stays outside the APK. */
object AgentConfig {
    private const val PREFS = "agentime_config"
    private const val ENDPOINT = "endpoint"
    private const val TOKEN = "token"
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:8787/v1/chat/stream"
    const val DEFAULT_TOKEN = "dev-agentime-token"
    const val DEFAULT_MODEL = "deepseek-flash"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun endpoint(): String = prefs?.getString(ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
    fun token(): String = prefs?.getString(TOKEN, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN
}
