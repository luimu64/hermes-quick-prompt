package dev.hermesprompt.app.data

import dev.hermesprompt.app.data.models.ModelInfo
import dev.hermesprompt.app.data.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        val profile: String? = null,
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
        return "$baseUrl$path"
    }

    /**
     * Starts a new Hermes run or streams chat completions.
     *
     * Streams completions via `/v1/chat/completions` (OpenAI streaming API), which
     * routes directly to the configured provider/backend (such as Bifrost) and
     * cleanly supports per-request model overrides and profile selection.
     */
    fun promptStream(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        model: String?,
        profile: String = "",
    ): Flow<HermesEvent> = flow {
        val prof = profile.trim().takeIf { it.isNotBlank() }
        val modelParam = model?.trim()?.takeIf { it.isNotBlank() }

        val requestPayload = buildJsonObject {
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            if (modelParam != null) {
                put("model", modelParam)
            }
            if (prof != null) {
                put("profile", prof)
            }
        }

        val bodyJson = json.encodeToString(JsonObject.serializer(), requestPayload)

        val request = Request.Builder()
            .url(apiUrl(baseUrl, profile, "/v1/chat/completions"))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .apply {
                if (prof != null) {
                    addHeader("X-Hermes-Profile", prof)
                }
            }
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                val msg = extractErrorMessage(errorBody, response.code)
                response.close()
                emit(HermesEvent.ErrorEvent("Error ${response.code}: $msg"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(HermesEvent.ErrorEvent("Empty response body"))
                return@flow
            }

            try {
                val reader = body.source().inputStream().bufferedReader(Charsets.UTF_8)
                val fullAccumulated = java.lang.StringBuilder()

                for (line in reader.lineSequence()) {
                    if (!coroutineContext.isActive) break

                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue

                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload == "[DONE]") {
                            emit(HermesEvent.RunCompleted(fullAccumulated.toString()))
                            emit(HermesEvent.Done)
                            return@flow
                        }

                        try {
                            val chunkObj = json.decodeFromString(JsonObject.serializer(), payload)
                            val choices = chunkObj["choices"] as? JsonArray
                            val firstChoice = choices?.firstOrNull() as? JsonObject
                            val deltaObj = firstChoice?.get("delta") as? JsonObject
                            val content = deltaObj?.get("content")?.jsonPrimitive?.contentOrNull

                            if (!content.isNullOrEmpty()) {
                                fullAccumulated.append(content)
                                emit(HermesEvent.MessageDelta(content))
                            }
                        } catch (_: Exception) {
                            // Non-JSON or unrecognized SSE chunk
                        }
                    }
                }

                if (fullAccumulated.isNotEmpty()) {
                    emit(HermesEvent.RunCompleted(fullAccumulated.toString()))
                }
                emit(HermesEvent.Done)
            } finally {
                response.close()
            }
        } catch (e: IOException) {
            if (coroutineContext.isActive) {
                emit(HermesEvent.ErrorEvent("Cannot reach server: ${e.message}"))
            }
        }
    }.flowOn(Dispatchers.IO)

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

    /** Sealed result of connection and authentication testing. */
    sealed class AuthResult {
        data class Success(val resolvedUrl: String, val models: List<ModelInfo>) : AuthResult()
        data class Failure(val message: String) : AuthResult()
    }

    private sealed class AuthProbeResult {
        data class Success(val resolvedUrl: String, val models: List<ModelInfo>) : AuthProbeResult()
        data class AuthFailed(val message: String) : AuthProbeResult()
        data object HtmlDashboard : AuthProbeResult()
        data class HttpError(val code: Int, val message: String) : AuthProbeResult()
        data class NetworkError(val message: String) : AuthProbeResult()
    }

    /**
     * Tests server connectivity AND validates the Bearer API key against the Hermes API.
     * Only returns [AuthResult.Success] when the server returns a valid 2xx response with
     * authorized JSON (never for unauthenticated HTML web dashboard responses).
     *
     * If [baseUrl] lacks an explicit port and returns web HTML, this probes the standard
     * Hermes API server port 8642 (`http://<host>:8642`).
     */
    suspend fun testAuth(
        baseUrl: String,
        apiKey: String,
        profile: String = "",
    ): AuthResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext AuthResult.Failure("API key is required.")
        }
        if (baseUrl.isBlank()) {
            return@withContext AuthResult.Failure("Server address is required.")
        }

        val urlCandidate = baseUrl.trim().removeSuffix("/")

        fun probe(candidateUrl: String): AuthProbeResult {
            return try {
                // Try /api/model/options first
                val optionsUrl = apiUrl(candidateUrl, profile, "/api/model/options")
                val reqOptions = Request.Builder()
                    .url(optionsUrl)
                    .addHeader("Authorization", "Bearer $trimmedKey")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                client.newCall(reqOptions).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        if (body.trimStart().startsWith("<")) {
                            return AuthProbeResult.HtmlDashboard
                        }
                        val models = parseModelOptionsJson(body)
                        if (models.isNotEmpty()) {
                            return AuthProbeResult.Success(candidateUrl, models)
                        }
                    } else if (resp.code == 401 || resp.code == 403) {
                        val errMsg = extractErrorMessage(body, resp.code)
                        return AuthProbeResult.AuthFailed(errMsg)
                    }
                }

                // Fallback: try /v1/models
                val v1Url = apiUrl(candidateUrl, profile, "/v1/models")
                val reqV1 = Request.Builder()
                    .url(v1Url)
                    .addHeader("Authorization", "Bearer $trimmedKey")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                client.newCall(reqV1).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        if (body.trimStart().startsWith("<")) {
                            return AuthProbeResult.HtmlDashboard
                        }
                        val models = parseV1ModelsJson(body)
                        if (models.isNotEmpty()) {
                            return AuthProbeResult.Success(candidateUrl, models)
                        }
                        return AuthProbeResult.Success(candidateUrl, listOf(ModelRegistry.SERVER_DEFAULT_MODEL))
                    } else if (resp.code == 401 || resp.code == 403) {
                        val errMsg = extractErrorMessage(body, resp.code)
                        return AuthProbeResult.AuthFailed(errMsg)
                    } else {
                        val errMsg = extractErrorMessage(body, resp.code)
                        return AuthProbeResult.HttpError(resp.code, errMsg)
                    }
                }
            } catch (e: Exception) {
                AuthProbeResult.NetworkError(e.message ?: "Connection failed")
            }
        }

        // 1. Probe the provided URL
        val primaryResult = probe(urlCandidate)
        if (primaryResult is AuthProbeResult.Success) {
            return@withContext AuthResult.Success(primaryResult.resolvedUrl, primaryResult.models)
        }

        // 2. If primary was rejected or was web HTML, and no port was given, check Hermes port 8642
        val parsedUri = runCatching { java.net.URI.create(urlCandidate) }.getOrNull()
        if (parsedUri != null && parsedUri.port == -1) {
            val port8642Http = "http://${parsedUri.host}:8642"
            val fallbackHttp = probe(port8642Http)
            if (fallbackHttp is AuthProbeResult.Success) {
                return@withContext AuthResult.Success(fallbackHttp.resolvedUrl, fallbackHttp.models)
            }

            val port8642Https = "https://${parsedUri.host}:8642"
            val fallbackHttps = probe(port8642Https)
            if (fallbackHttps is AuthProbeResult.Success) {
                return@withContext AuthResult.Success(fallbackHttps.resolvedUrl, fallbackHttps.models)
            }
        }

        when (primaryResult) {
            is AuthProbeResult.AuthFailed -> AuthResult.Failure("Authentication failed: ${primaryResult.message}")
            is AuthProbeResult.HtmlDashboard -> AuthResult.Failure("Connected to Web Dashboard instead of API Server. Please use the API port (e.g. http://${parsedUri?.host ?: "host"}:8642).")
            is AuthProbeResult.HttpError -> AuthResult.Failure("Server error (HTTP ${primaryResult.code}): ${primaryResult.message}")
            is AuthProbeResult.NetworkError -> AuthResult.Failure("Cannot reach server: ${primaryResult.message}")
            else -> AuthResult.Failure("Authentication failed.")
        }
    }

    /**
     * Fetches the list of models exposed by the Hermes server / gateway.
     * Checks `GET /api/model/options` first (Hermes rich catalog endpoint),
     * and falls back to `GET /v1/models` (standard OpenAI discovery endpoint).
     *
     * @param baseUrl Normalized server URL.
     * @param apiKey Bearer token.
     * @param profile Optional profile name.
     * @return List of [ModelInfo] objects discovered from the server.
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        profile: String = "",
    ): List<ModelInfo> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()

        // 1. Try /api/model/options first (Hermes rich catalog endpoint)
        val optionsResult = fetchModelOptions(baseUrl, apiKey, profile)
        if (optionsResult.isNotEmpty()) {
            return@withContext optionsResult
        }

        // 2. Fallback to /v1/models (Standard OpenAI endpoint)
        val v1ModelsResult = fetchV1Models(baseUrl, apiKey, profile)
        if (v1ModelsResult.isNotEmpty()) {
            return@withContext v1ModelsResult
        }

        emptyList()
    }

    private fun fetchModelOptions(baseUrl: String, apiKey: String, profile: String): List<ModelInfo> {
        return try {
            val url = apiUrl(baseUrl, profile, "/api/model/options")
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer $apiKey")
                    }
                    addHeader("Accept", "application/json")
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseModelOptionsJson(body)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fetchV1Models(baseUrl: String, apiKey: String, profile: String): List<ModelInfo> {
        return try {
            val url = apiUrl(baseUrl, profile, "/v1/models")
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer $apiKey")
                    }
                    addHeader("Accept", "application/json")
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseV1ModelsJson(body)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Parses the payload from Hermes `/api/model/options` into rich [ModelInfo] instances.
     */
    fun parseModelOptionsJson(body: String): List<ModelInfo> {
        val result = mutableListOf<ModelInfo>()
        result.add(ModelRegistry.SERVER_DEFAULT_MODEL)

        try {
            val jsonElement = json.parseToJsonElement(body)
            val rootObj = jsonElement as? JsonObject ?: return emptyList()
            val providersArray = rootObj["providers"] as? JsonArray ?: return emptyList()

            for (providerElem in providersArray) {
                val pObj = providerElem as? JsonObject ?: continue
                val slug = pObj["slug"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = pObj["name"]?.jsonPrimitive?.contentOrNull ?: slug
                val isCurrent = pObj["is_current"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: (pObj["is_current"]?.toString() == "true")
                val isAuthenticated = pObj["authenticated"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: (pObj["authenticated"]?.toString() == "true")
                val warning = pObj["warning"]?.jsonPrimitive?.contentOrNull

                val providerInfo = dev.hermesprompt.app.data.models.ProviderInfo(
                    id = slug,
                    displayName = name,
                    description = if (isCurrent) "Active server provider" else warning ?: "Provided by server ($name)",
                    order = if (isCurrent) 1 else if (isAuthenticated) 5 else 50,
                )
                ModelRegistry.registerProvider(providerInfo)

                val modelsArray = pObj["models"] as? JsonArray
                if (modelsArray != null) {
                    for (modelElem in modelsArray) {
                        val modelId = when (modelElem) {
                            is JsonPrimitive -> modelElem.contentOrNull
                            is JsonObject -> modelElem["id"]?.jsonPrimitive?.contentOrNull
                                ?: modelElem["name"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        } ?: continue

                        if (modelId.isBlank()) continue

                        val isReasoning = modelId.contains("reasoning", ignoreCase = true) ||
                            modelId.contains("r1", ignoreCase = true) ||
                            modelId.contains("o1", ignoreCase = true) ||
                            modelId.contains("o3", ignoreCase = true) ||
                            modelId.contains("thinking", ignoreCase = true)

                        val displayName = formatModelDisplayName(modelId)

                        val modelInfo = ModelInfo(
                            id = modelId,
                            displayName = displayName,
                            providerId = slug,
                            description = "$name • $modelId",
                            isReasoning = isReasoning,
                            isCustom = false,
                            isDefault = false,
                            tags = listOf(slug, name.lowercase(java.util.Locale.ROOT)),
                        )
                        result.add(modelInfo)
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return if (result.size > 1) result else emptyList()
    }

    /**
     * Parses standard OpenAI `/v1/models` format into [ModelInfo] instances.
     */
    fun parseV1ModelsJson(body: String): List<ModelInfo> {
        val result = mutableListOf<ModelInfo>()
        result.add(ModelRegistry.SERVER_DEFAULT_MODEL)

        try {
            val jsonElement = json.parseToJsonElement(body)
            val dataArray = when (jsonElement) {
                is JsonObject -> jsonElement["data"] as? JsonArray ?: jsonElement["models"] as? JsonArray
                is JsonArray -> jsonElement
                else -> null
            } ?: return emptyList()

            for (elem in dataArray) {
                val id = when (elem) {
                    is JsonObject -> elem["id"]?.jsonPrimitive?.contentOrNull
                        ?: elem["name"]?.jsonPrimitive?.contentOrNull
                        ?: elem["model"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> elem.contentOrNull
                    else -> null
                } ?: continue

                if (id.isBlank()) continue

                val resolved = ModelRegistry.findModel(id)
                result.add(resolved)
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return if (result.size > 1) result else emptyList()
    }

    private fun formatModelDisplayName(modelId: String): String {
        val known = ModelRegistry.findCuratedModel(modelId)
        if (known != null && !known.isCustom && !known.isDefault) {
            return known.displayName
        }
        if (modelId.contains('/')) {
            val prefix = modelId.substringBeforeLast('/')
            val leaf = modelId.substringAfterLast('/')
            return "$leaf ($prefix)"
        }
        return modelId
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
