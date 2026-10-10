package io.reasonix.mobile.storage

import android.content.Context
import android.content.SharedPreferences

data class SavedDevice(
    val id: String,
    val name: String,
    val origin: String,
    val lastConnected: Long
)

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reasonix_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_URL = "last_connected_url"
        private const val KEY_LAST_ORIGIN = "last_connected_origin"
        private const val KEY_AUTH_TOKEN = "cached_auth_token"
    }

    var lastConnectedUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    var lastConnectedOrigin: String?
        get() = prefs.getString(KEY_LAST_ORIGIN, null)
        set(value) = prefs.edit().putString(KEY_LAST_ORIGIN, value).apply()

    var cachedAuthToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
