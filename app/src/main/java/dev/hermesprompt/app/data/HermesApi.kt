package dev.hermesprompt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Thin OkHttp-based client for the Hermes Agent REST/SSE API.
 *
 * All methods are suspend-or-Flow; no UI dependencies.
 * Networking is executed on [Dispatchers.IO].
 */
class HermesApi(private val client: OkHttpClient) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Serialization models ──────────────────────────────────────────────────

    @Serializable
    private data class StartRunRequest(
        val input: String,
        val model: String? = null,
        @SerialName("session_id") val sessionId: String? = null,
    )

    @Serializable
    data class StartRunResponse(
        @SerialName("run_id") val runId: String,
        val status: String,
    )

    /** Sealed hierarchy for parsed SSE events from the run events stream. */
    sealed class HermesEvent {
        /** A text chunk to accumulate. */
        data class MessageDelta(val delta: String) : HermesEvent()
        /** Run finished; [output] is the authoritative final answer. */
        data class RunCompleted(val output: String) : HermesEvent()
        /** The assistant signalled completion with a full content string. */
        data class AssistantCompleted(val content: String) : HermesEvent()
        /** Server-side error. */
        data class ErrorEvent(val message: String) : HermesEvent()
        /** Stream is finished (done frame or EOF). */
        data object Done : HermesEvent()
        /** An event type we don't act on (tool.*, reasoning.available, etc.). */
        data object Ignored : HermesEvent()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the full URL for an API path, inserting the multiplex profile
     * prefix when one is set. The Hermes API server routes profile-scoped
     * requests via a `/p/<profile>/` URL prefix (gateway.multiplex_profiles);
     * an empty profile keeps the plain path (default profile).
     */
    private fun apiUrl(baseUrl: String, profile: String, path: String): String {
        val prefix = profile.trim().takeIf { it.isNotEmpty() }?.let { "/p/$it" } ?: ""
        return "$baseUrl$prefix$path"
    }

    /**
     * Starts a new Hermes run.
     *
     * @param baseUrl Normalized server URL (no trailing slash).
     * @param apiKey Bearer token.
     * @param prompt The user's text prompt.
     * @param model Optional model override; omitted from the request when blank.
     * @param profile Optional profile name; blank routes to the default profile.
     * @return [StartRunResponse] on HTTP 202.
     * @throws IOException on network error.
     * @throws HermesApiException on non-2xx response.
     */
    suspend fun startRun(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        model: String?,
        profile: String = "",
    ): StartRunResponse = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val body = StartRunRequest(
            input = prompt,
            model = model?.takeIf { it.isNotBlank() },
            sessionId = "hermes-quick-prompt",
        )
        val bodyJson = json.encodeToString(StartRunRequest.serializer(), body)

        val request = Request.Builder()
            .url(apiUrl(baseUrl, profile, "/v1/runs"))
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(responseBody, response.code)
                throw HermesApiException(response.code, errorMsg)
            }
            json.decodeFromString(StartRunResponse.serializer(), responseBody)
        }
    }

    /**
     * Opens the SSE event stream for [runId] and emits parsed [HermesEvent]s.
     *
     * The flow runs on [Dispatchers.IO]. It retries once with a 500 ms delay
     * on HTTP 404 (race between POST /v1/runs and the event stream becoming
     * available), then gives up.
     *
     * The caller is responsible for cancelling the flow (e.g. via
     * [kotlinx.coroutines.Job.cancel]) when the user dismisses the sheet.
     */
    fun runEvents(
        baseUrl: String,
        apiKey: String,
        runId: String,
        profile: String = "",
    ): Flow<HermesEvent> = flow {
        val url = apiUrl(baseUrl, profile, "/v1/runs/$runId/events")
        var attempt = 0
        val maxRetries = 2

        while (coroutineContext.isActive) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "text/event-stream")
                .get()
                .build()

            val call = client.newCall(request)

            try {
                val response = call.execute()
                if (response.code == 404 && attempt < maxRetries) {
                    response.close()
                    attempt++
                    kotlinx.coroutines.delay(500)
                    continue
                }
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code}: ${response.message}"
                    response.close()
                    emit(HermesEvent.ErrorEvent(msg))
                    return@flow
                }

                if (response.body == null) {
                    emit(HermesEvent.ErrorEvent("Empty response body"))
                    return@flow
                }

                try {
                    // Read lines from the SSE stream
                    val dataBuffer = StringBuilder()

                    // Use buffered line reading
                    val reader = response.body!!.source().inputStream().bufferedReader(Charsets.UTF_8)
                    for (line in reader.lineSequence()) {
                        if (!coroutineContext.isActive) break

                        when {
                            line.startsWith(":") -> {
                                // Comment / keepalive — ignore
                            }
                            line.startsWith("data:") -> {
                                val payload = line.removePrefix("data:").trimStart()
                                dataBuffer.append(payload)
                            }
                            line.isBlank() && dataBuffer.isNotEmpty() -> {
                                val frameData = dataBuffer.toString().trim()
                                dataBuffer.clear()
                                if (frameData.isNotEmpty()) {
                                    val event = parseEvent(frameData)
                                    emit(event)
                                    if (event is HermesEvent.Done || event is HermesEvent.RunCompleted) {
                                        return@flow
                                    }
                                }
                            }
                        }
                    }

                    // Flush remaining buffer at EOF
                    val remaining = dataBuffer.toString().trim()
                    if (remaining.isNotEmpty()) {
                        emit(parseEvent(remaining))
                    }
                } finally {
                    response.close()
                }
                return@flow

            } catch (e: IOException) {
                if (coroutineContext.isActive) {
                    emit(HermesEvent.ErrorEvent("Cannot reach server: ${e.message}"))
                }
                return@flow
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fires a stop request for [runId]. Fire-and-forget; errors are swallowed.
     */
    suspend fun stopRun(baseUrl: String, apiKey: String, runId: String, profile: String = "") {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(apiUrl(baseUrl, profile, "/v1/runs/$runId/stop"))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post("".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // Fire-and-forget; ignore all errors
            }
        }
    }

    /**
     * Health check — no auth required.
     *
     * @param profile Optional profile name; when set the check targets the
     *   profile-scoped path so an unknown profile surfaces as a failure.
     * @return true on HTTP 2xx with `{"status":"ok"}` (or any 2xx).
     */
    suspend fun health(baseUrl: String, profile: String = ""): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl(baseUrl, profile, "/v1/health"))
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parseEvent(data: String): HermesEvent {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), data)
            val eventType = obj["event"]?.jsonPrimitive?.content ?: return HermesEvent.Ignored

            when (eventType) {
                "message.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.content ?: ""
                    HermesEvent.MessageDelta(delta)
                }
                "run.completed" -> {
                    val output = obj["output"]?.jsonPrimitive?.content ?: ""
                    HermesEvent.RunCompleted(output)
                }
                "assistant.completed" -> {
                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                    HermesEvent.AssistantCompleted(content)
                }
                "error" -> {
                    val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    HermesEvent.ErrorEvent(message)
                }
                "done" -> HermesEvent.Done
                else -> HermesEvent.Ignored
            }
        } catch (_: Exception) {
            HermesEvent.Ignored
        }
    }

    private fun extractErrorMessage(body: String, code: Int): String {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), body)
            // Try error.message first (as specified in brief), then message, then error
            val errorObj = obj["error"]
            if (errorObj != null) {
                try {
                    val errorJson = json.decodeFromString(JsonObject.serializer(), errorObj.toString())
                    errorJson["message"]?.jsonPrimitive?.content
                } catch (_: Exception) {
                    errorObj.jsonPrimitive.content
                }
            } else {
                obj["message"]?.jsonPrimitive?.content
            } ?: "HTTP $code error"
        } catch (_: Exception) {
            "HTTP $code error"
        }
    }
}

/** Thrown when the Hermes server returns a non-2xx response. */
class HermesApiException(val code: Int, override val message: String) : Exception(message)
