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
    val model: String = "",
    val profile: String = "",
    val availableModels: List<dev.hermesprompt.app.data.models.ModelInfo> = emptyList(),
    val isLoadingModels: Boolean = false,
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

    private var testJob: Job? = null
    private var modelsJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(
                    serverUrl = settings.serverUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    profile = settings.profile,
                ) }
                if (settings.isConfigured) {
                    fetchModels(settings.serverUrl, settings.apiKey, settings.profile)
                }
            }
        }
    }

    fun fetchModels(serverUrl: String, apiKey: String, profile: String) {
        val urlResult = SettingsValidator.normalize(serverUrl)
        if (urlResult !is SettingsValidator.UrlResult.Valid) return
        val url = urlResult.url
        val prof = SettingsValidator.normalizeProfile(profile) ?: ""

        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }
            android.util.Log.d("SettingsViewModel", "Fetching models for $url...")
            val models = hermesApi.listModels(url, apiKey.trim(), prof)
            android.util.Log.d("SettingsViewModel", "Fetched ${models.size} models from server")
            _uiState.update { it.copy(
                availableModels = models,
                isLoadingModels = false,
            ) }
        }
    }

    fun refreshModels() {
        val state = _uiState.value
        fetchModels(state.serverUrl, state.apiKey, state.profile)
    }

    fun onServerUrlChange(url: String) {
        _uiState.update { it.copy(serverUrl = url, serverUrlError = null, testResult = null) }
    }

    fun onApiKeyChange(key: String) {
        _uiState.update { it.copy(apiKey = key, testResult = null) }
    }

    fun onModelChange(model: String) {
        _uiState.update { it.copy(model = model) }
    }

    fun onProfileChange(profile: String) {
        _uiState.update { it.copy(profile = profile, profileError = null, testResult = null) }
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
            settingsStore.save(
                AppSettings(
                    serverUrl = normalizedUrl,
                    apiKey = state.apiKey.trim(),
                    model = state.model.trim(),
                    profile = normalizedProfile,
                )
            )
            _uiState.update { it.copy(isSaving = false, serverUrl = normalizedUrl, profile = normalizedProfile) }
        }
    }

    /** Fires a health check against the currently entered server URL. */
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

        testJob = viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val ok = hermesApi.health(url, profile)
            if (ok) {
                val models = hermesApi.listModels(url, _uiState.value.apiKey.trim(), profile)
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = TestResult.Success,
                        availableModels = models,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = TestResult.Failure("Server did not return OK"),
                    )
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
