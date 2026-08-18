package dev.hermesprompt.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hermesprompt.app.data.AppSettings
import dev.hermesprompt.app.data.HermesApi
import dev.hermesprompt.app.data.SettingsStore
import dev.hermesprompt.app.data.SettingsValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val profile: String = "",
    val isDirty: Boolean = false,
    val profileError: String? = null,
    val serverUrlError: String? = null,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
)

sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String) : TestResult()
}

/**
 * ViewModel for the Settings screen.
 *
 * Loads current settings from [SettingsStore], validates URL on save via
 * [SettingsValidator], and fires the health check via [HermesApi].
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val hermesApi: HermesApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var savedSettings = AppSettings("", "")
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                savedSettings = settings
                _uiState.update { current ->
                    current.copy(
                        serverUrl = settings.serverUrl,
                        apiKey = settings.apiKey,
                        profile = settings.profile,
                        isDirty = false,
                    )
                }
            }
        }
    }

    private fun checkDirty(
        serverUrl: String = _uiState.value.serverUrl,
        apiKey: String = _uiState.value.apiKey,
        profile: String = _uiState.value.profile,
    ): Boolean {
        return serverUrl != savedSettings.serverUrl ||
                apiKey != savedSettings.apiKey ||
                profile != savedSettings.profile
    }

    fun onServerUrlChange(url: String) {
        _uiState.update {
            it.copy(
                serverUrl = url,
                serverUrlError = null,
                testResult = null,
                isDirty = checkDirty(serverUrl = url),
            )
        }
    }

    fun onApiKeyChange(key: String) {
        _uiState.update {
            it.copy(
                apiKey = key,
                testResult = null,
                isDirty = checkDirty(apiKey = key),
            )
        }
    }

    fun onProfileChange(profile: String) {
        _uiState.update {
            it.copy(
                profile = profile,
                profileError = null,
                testResult = null,
                isDirty = checkDirty(profile = profile),
            )
        }
    }

    /** Validates, normalizes, and persists the current settings. */
    fun save() {
        val state = _uiState.value
        val urlResult = SettingsValidator.normalize(state.serverUrl)
        if (urlResult is SettingsValidator.UrlResult.Invalid) {
            _uiState.update { it.copy(serverUrlError = urlResult.reason) }
            return
        }
        val normalizedUrl = (urlResult as SettingsValidator.UrlResult.Valid).url

        val normalizedProfile = SettingsValidator.normalizeProfile(state.profile)
        if (normalizedProfile == null) {
            _uiState.update {
                it.copy(profileError = "Profile names: lowercase letters, digits, - or _. Max 64 chars.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, serverUrlError = null) }
            val newSettings = AppSettings(
                serverUrl = normalizedUrl,
                apiKey = state.apiKey.trim(),
                profile = normalizedProfile,
            )
            settingsStore.save(newSettings)
            savedSettings = newSettings
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isDirty = false,
                    serverUrl = normalizedUrl,
                    profile = normalizedProfile,
                )
            }
        }
    }

    /**
     * Tests server connectivity and authenticates the API key.
     * Only reports success if authentication is verified.
     */
    fun testConnection() {
        testJob?.cancel()
        val rawUrl = _uiState.value.serverUrl
        val urlResult = SettingsValidator.normalize(rawUrl)
        if (urlResult is SettingsValidator.UrlResult.Invalid) {
            _uiState.update { it.copy(serverUrlError = urlResult.reason) }
            return
        }
        val url = (urlResult as SettingsValidator.UrlResult.Valid).url
        val profile = SettingsValidator.normalizeProfile(_uiState.value.profile) ?: ""
        val apiKey = _uiState.value.apiKey.trim()

        if (apiKey.isBlank()) {
            _uiState.update {
                it.copy(testResult = TestResult.Failure("API key is required to test connection."))
            }
            return
        }

        testJob = viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val authResult = hermesApi.testAuth(url, apiKey, profile)
            when (authResult) {
                is HermesApi.AuthResult.Success -> {
                    val resolvedUrl = authResult.resolvedUrl
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            serverUrl = resolvedUrl,
                            testResult = TestResult.Success,
                            isDirty = checkDirty(serverUrl = resolvedUrl),
                        )
                    }
                }
                is HermesApi.AuthResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = TestResult.Failure(authResult.message),
                        )
                    }
                }
            }
        }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val hermesApi: HermesApi,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settingsStore, hermesApi) as T
    }
}
