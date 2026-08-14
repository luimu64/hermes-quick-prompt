package dev.hermesprompt.app

import android.app.Application
import dev.hermesprompt.app.data.HermesApi
import dev.hermesprompt.app.data.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application class that wires together the app-level object graph (manual DI).
 *
 * [AppContainer] holds singleton instances that survive configuration changes
 * and are injected into ViewModels via their factories.
 */
class HermesPromptApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Application-scoped dependency container.
 *
 * This is intentionally simple — the app has two screens and one API client.
 * Hilt would be ceremony; this is lean, explicit, and zero-magic.
 */
class AppContainer(application: Application) {

    /** Shared OkHttpClient with generous timeouts for SSE streaming. */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // No read timeout — SSE streams can be long
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Hermes API client. */
    val hermesApi: HermesApi = HermesApi(okHttpClient)

    /** Persistent settings storage. */
    val settingsStore: SettingsStore = SettingsStore(application)
}
