package dev.hermesprompt.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-level DataStore instance, lazily created once per process. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/**
 * Typed wrapper around DataStore<Preferences> for Hermes connection settings.
 *
 * Persists:
 *   - [serverUrl] — the normalized base URL (e.g. "https://hermes.example.com")
 *   - [apiKey]    — the Bearer token; stored in DataStore, never in BuildConfig
 *   - [model]     — optional model override (empty = server default)
 *   - [profile]   — optional profile name (empty = default profile)
 */
class SettingsStore(context: Context) {

    private val store = context.dataStore

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_PROFILE = stringPreferencesKey("profile")
    }

    /** Current settings as a [Flow]. Emits immediately with the stored values. */
    val settingsFlow: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            apiKey = prefs[KEY_API_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: "",
            profile = prefs[KEY_PROFILE] ?: "",
        )
    }

    /** Persists [settings] atomically. Safe to call from any coroutine context. */
    suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_API_KEY] = settings.apiKey
            prefs[KEY_MODEL] = settings.model
            prefs[KEY_PROFILE] = settings.profile
        }
    }
}

/**
 * Immutable snapshot of the user-configured connection settings.
 */
data class AppSettings(
    val serverUrl: String,
    val apiKey: String,
    val model: String,
    val profile: String = "",
) {
    /** True if the minimum required fields are filled in. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}
