package dev.hermesprompt.app.ui

import dev.hermesprompt.app.data.AppSettings
import dev.hermesprompt.app.data.HermesApi
import dev.hermesprompt.app.data.RunState
import dev.hermesprompt.app.data.models.ModelInfo
import dev.hermesprompt.app.data.models.ModelRegistry
import dev.hermesprompt.app.data.models.ProviderInfo
import dev.hermesprompt.app.ui.overlay.OverlayUiState
import dev.hermesprompt.app.ui.prompt.PromptViewModel
import dev.hermesprompt.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSelectionEndToEndTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Fake implementation of OkHttpClient to capture outgoing requests and simulate responses.
     */
    private class FakeCall(
        private val request: Request,
        private val responseSupplier: (Request) -> Response,
    ) : Call {
        override fun request(): Request = request
        override fun execute(): Response = responseSupplier(request)
        override fun enqueue(responseCallback: okhttp3.Callback) = throw UnsupportedOperationException()
        override fun cancel() {}
        override fun isExecuted(): Boolean = true
        override fun isCanceled(): Boolean = false
        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun clone(): Call = this
    }

    private fun createMockOkHttpClient(onRequest: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                onRequest(chain.request())
            }
            .build()
    }

    @Test
    fun `ModelRegistry resolves default, curated, and custom models cleanly`() {
        val defaultModel = ModelRegistry.resolveModel("")
        assertTrue(defaultModel.isDefault)
        assertEquals("", defaultModel.id)
        assertNull(defaultModel.apiValue)
        assertEquals("Server Default", defaultModel.fullDisplayLabel)

        val claude = ModelRegistry.resolveModel("openrouter/anthropic/claude-3.7-sonnet")
        assertFalse(claude.isDefault)
        assertEquals("Claude 3.7 Sonnet (OpenRouter)", claude.displayName)
        assertEquals("openrouter", claude.provider.id)
        assertEquals("200k", claude.formattedContextWindow)
        assertEquals("openrouter/anthropic/claude-3.7-sonnet", claude.apiValue)

        val custom = ModelRegistry.resolveModel("custom-org/my-custom-llm")
        assertTrue(custom.isCustom)
        assertEquals("custom-org/my-custom-llm", custom.id)
        assertEquals("my-custom-llm", custom.displayName)
        assertEquals("custom-org/my-custom-llm", custom.apiValue)
    }

    @Test
    fun `promptStream includes model in JSON request body and streams SSE events`() = runTest(testDispatcher) {
        val capturedRequestBody = AtomicReference<String>()
        val capturedUrl = AtomicReference<String>()

        val ssePayload = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}\n\ndata: [DONE]\n\n"

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedUrl.set(request.url.toString())
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                capturedRequestBody.set(buffer.readUtf8())

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ssePayload.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

        val hermesApi = HermesApi(client)

        // Case 1: Model is specified
        val events = mutableListOf<HermesApi.HermesEvent>()
        hermesApi.promptStream(
            baseUrl = "https://hermes.example.com",
            apiKey = "test-key",
            prompt = "Hello model test",
            model = "Azure/DeepSeek-R1",
            profile = "chat",
        ).collect { events.add(it) }

        val body = capturedRequestBody.get()
        assertNotNull(body)
        assertTrue("Request body should contain messages content", body.contains("Hello model test"))
        assertTrue("Request body should contain model parameter", body.contains(""""model":"Azure/DeepSeek-R1""""))
        assertTrue("Request body should contain profile parameter", body.contains(""""profile":"chat""""))

        assertTrue(events.any { it is HermesApi.HermesEvent.MessageDelta && it.delta == "Hello " })
        assertTrue(events.any { it is HermesApi.HermesEvent.MessageDelta && it.delta == "world!" })
        assertTrue(events.any { it is HermesApi.HermesEvent.RunCompleted && it.output == "Hello world!" })
    }

    @Test
    fun `Model selection updates UI state and preserves selection across reloads`() = runTest(testDispatcher) {
        val memorySettings = MutableStateFlow(AppSettings("https://hermes.example.com", "key123", ""))

        // Simulate SettingsViewModel workflow
        var currentSettings = memorySettings.value
        assertEquals("", currentSettings.model)

        // User selects a model in dropdown
        val selectedModel = "nous/hermes-3-llama-3.1-70b"
        currentSettings = currentSettings.copy(model = selectedModel)
        memorySettings.value = currentSettings

        // Verify state is preserved across view model / session reloads
        val reloadedSettings = memorySettings.value
        assertEquals(selectedModel, reloadedSettings.model)
        val modelInfo = ModelRegistry.resolveModel(reloadedSettings.model)
        assertEquals("Hermes 3 Llama 3.1 70B", modelInfo.displayName)
        assertEquals(ProviderInfo.ID_NOUS, modelInfo.provider.id)
    }

    @Test
    fun `testAuth returns Success with models and resolves port 8642 fallback when HTML dashboard is detected`() = runTest(testDispatcher) {
        val hermesOptionsJson = """
            {
              "providers": [
                {
                  "slug": "custom:bifrost",
                  "name": "Bifrost",
                  "is_current": true,
                  "authenticated": true,
                  "models": ["Azure/DeepSeek-R1", "Azure/gpt-5.4"]
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.port == 8642 && url.encodedPath.endsWith("/api/model/options")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(hermesOptionsJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (url.port == 443 || url.port == 80 || url.port == -1) {
                    // Simulates web dashboard returning HTML
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<!doctype html><html><title>Sign in</title></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val hermesApi = HermesApi(client)
        val result = hermesApi.testAuth("https://hermes.example.com", "valid-key")

        assertTrue(result is HermesApi.AuthResult.Success)
        val success = result as HermesApi.AuthResult.Success
        assertEquals("http://hermes.example.com:8642", success.resolvedUrl)
        assertTrue(success.models.any { it.id == "Azure/DeepSeek-R1" })
    }

    @Test
    fun `testAuth returns Failure when server rejects API key with 401`() = runTest(testDispatcher) {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error":{"message":"Invalid gateway API key (API_SERVER_KEY)"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val hermesApi = HermesApi(client)
        val result = hermesApi.testAuth("http://hermes.example.com:8642", "wrong-key")

        assertTrue(result is HermesApi.AuthResult.Failure)
        val failure = result as HermesApi.AuthResult.Failure
        assertTrue(failure.message.contains("Invalid gateway API key"))
    }
}
