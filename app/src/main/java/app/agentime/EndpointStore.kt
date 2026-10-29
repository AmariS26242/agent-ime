package app.agentime

import android.content.Context
import android.content.SharedPreferences

/** Stores only backend endpoint and client auth token. No upstream secret. */
class EndpointStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("agentime_prefs", Context.MODE_PRIVATE)

    fun backendUrl(): String {
        val v = prefs.getString(KEY_BACKEND, null)
        return if (v.isNullOrBlank()) BuildConfig.DEFAULT_BACKEND else v
    }

    fun token(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun setBackendUrl(url: String) {
        prefs.edit().putString(KEY_BACKEND, url).apply()
    }

    fun setToken(t: String) {
        prefs.edit().putString(KEY_TOKEN, t).apply()
    }

    companion object {
        const val KEY_BACKEND = "backend_url"
        const val KEY_TOKEN = "auth_token"
    }
}
