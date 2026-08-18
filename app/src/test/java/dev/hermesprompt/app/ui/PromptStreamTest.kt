package dev.hermesprompt.app.ui

import dev.hermesprompt.app.data.HermesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class PromptStreamTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `promptStream includes prompt and profile and streams SSE chunks`() = runTest(testDispatcher) {
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

        val events = mutableListOf<HermesApi.HermesEvent>()
        hermesApi.promptStream(
            baseUrl = "https://hermes.example.com",
            apiKey = "test-key",
            prompt = "Hello prompt stream",
            profile = "chat",
        ).collect { events.add(it) }

        val body = capturedRequestBody.get()
        assertNotNull(body)
        assertTrue("Request body should contain messages content", body.contains("Hello prompt stream"))
        assertTrue("Request body should contain profile parameter", body.contains(""""profile":"chat""""))

        assertTrue(events.any { it is HermesApi.HermesEvent.MessageDelta && it.delta == "Hello " })
        assertTrue(events.any { it is HermesApi.HermesEvent.MessageDelta && it.delta == "world!" })
        assertTrue(events.any { it is HermesApi.HermesEvent.RunCompleted && it.output == "Hello world!" })
    }

    @Test
    fun `testAuth returns Success when auth succeeds and resolves port 8642 fallback`() = runTest(testDispatcher) {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.port == 8642 && url.encodedPath.endsWith("/v1/models")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"object\":\"list\",\"data\":[{\"id\":\"hermes-agent\"}]}".toResponseBody("application/json".toMediaType()))
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
