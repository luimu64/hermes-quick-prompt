package dev.hermesprompt.app.ui.prompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hermesprompt.app.data.AppSettings
import dev.hermesprompt.app.data.HermesApi
import dev.hermesprompt.app.data.HermesApiException
import dev.hermesprompt.app.data.RunState
import dev.hermesprompt.app.data.SettingsStore
import dev.hermesprompt.app.data.appendDelta
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PromptUiState(
    val promptText: String = "",
    val runState: RunState = RunState.Idle,
    val settings: AppSettings = AppSettings("", "", ""),
)

/**
 * ViewModel for the prompt sheet.
 *
 * Manages the full run lifecycle: starting a run, accumulating streamed deltas,
 * transitioning to Done or Error states, and cancelling in-flight runs.
 *
 * Uses [viewModelScope] so all coroutines are automatically cancelled when the
 * activity hosting the sheet is finished (tap outside, Back press, etc.).
 */
class PromptViewModel(
    private val settingsStore: SettingsStore,
    private val hermesApi: HermesApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PromptUiState())
    val uiState: StateFlow<PromptUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onPromptChange(text: String) {
        _uiState.update { it.copy(promptText = text) }
    }

    /**
     * Sends the current prompt text to the Hermes server.
     *
     * State machine: Idle → Running → Done | Error
     */
    fun sendPrompt() {
        val state = _uiState.value
        if (state.promptText.isBlank()) return
        if (state.runState is RunState.Running) return

        val settings = state.settings
        val prompt = state.promptText.trim()

        runJob?.cancel()
        runJob = viewModelScope.launch {
            _uiState.update { it.copy(runState = RunState.Running("", "")) }

            // Start the run
            val startResponse = try {
                hermesApi.startRun(
                    baseUrl = settings.serverUrl,
                    apiKey = settings.apiKey,
                    prompt = prompt,
                    model = settings.model.takeIf { it.isNotBlank() },
                    profile = settings.profile,
                )
            } catch (e: HermesApiException) {
                _uiState.update { it.copy(runState = RunState.Error("Error ${e.code}: ${e.message}")) }
                return@launch
            } catch (e: Exception) {
                _uiState.update { it.copy(runState = RunState.Error("Cannot reach server: ${e.message}")) }
                return@launch
            }

            val runId = startResponse.runId
            _uiState.update { it.copy(runState = RunState.Running(runId, "")) }

            // Stream events
            try {
                hermesApi.runEvents(
                    baseUrl = settings.serverUrl,
                    apiKey = settings.apiKey,
                    runId = runId,
                    profile = settings.profile,
                ).collect { event ->
                    when (event) {
                        is HermesApi.HermesEvent.MessageDelta -> {
                            _uiState.update { uiState ->
                                val running = uiState.runState as? RunState.Running ?: return@update uiState
                                uiState.copy(runState = running.appendDelta(event.delta))
                            }
                        }
                        is HermesApi.HermesEvent.RunCompleted -> {
                            _uiState.update { it.copy(runState = RunState.Done(event.output)) }
                        }
                        is HermesApi.HermesEvent.AssistantCompleted -> {
                            // Only use as fallback if we don't already have a Done state
                            _uiState.update { uiState ->
                                if (uiState.runState !is RunState.Done) {
                                    uiState.copy(runState = RunState.Done(event.content))
                                } else uiState
                            }
                        }
                        is HermesApi.HermesEvent.ErrorEvent -> {
                            _uiState.update { it.copy(runState = RunState.Error(event.message)) }
                        }
                        is HermesApi.HermesEvent.Done -> {
                            // Stream ended — if still Running (no run.completed received), mark as error
                            _uiState.update { uiState ->
                                if (uiState.runState is RunState.Running) {
                                    val text = (uiState.runState as RunState.Running).streamedText
                                    if (text.isNotBlank()) {
                                        uiState.copy(runState = RunState.Done(text))
                                    } else {
                                        uiState.copy(runState = RunState.Error("Connection lost"))
                                    }
                                } else uiState
                            }
                        }
                        is HermesApi.HermesEvent.Ignored -> { /* no-op */ }
                    }
                }
            } catch (e: Exception) {
                if (_uiState.value.runState is RunState.Running) {
                    _uiState.update { it.copy(runState = RunState.Error("Connection lost: ${e.message}")) }
                }
            }
        }
    }

    /**
     * Cancels the in-flight run (if any) and stops the SSE stream.
     *
     * Called when the user dismisses the sheet mid-run. The cancel is
     * fire-and-forget — we don't await the stop confirmation.
     */
    fun cancelRun() {
        val running = _uiState.value.runState as? RunState.Running ?: return
        runJob?.cancel()
        runJob = null

        if (running.runId.isNotBlank()) {
            viewModelScope.launch {
                hermesApi.stopRun(
                    baseUrl = _uiState.value.settings.serverUrl,
                    apiKey = _uiState.value.settings.apiKey,
                    runId = running.runId,
                    profile = _uiState.value.settings.profile,
                )
            }
        }
        _uiState.update { it.copy(runState = RunState.Idle) }
    }

    fun resetToIdle() {
        cancelRun()
        _uiState.update { it.copy(runState = RunState.Idle) }
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val hermesApi: HermesApi,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PromptViewModel(settingsStore, hermesApi) as T
    }
}
