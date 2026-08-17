package dev.hermesprompt.app.data

import dev.hermesprompt.app.data.models.ModelInfo
import dev.hermesprompt.app.data.models.ModelRegistry
import dev.hermesprompt.app.data.models.ProviderInfo
import org.junit.Assert.*
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `server default model has empty ID and null apiValue`() {
        val defaultModel = ModelRegistry.SERVER_DEFAULT_MODEL
        assertEquals("", defaultModel.id)
        assertNull(defaultModel.apiValue)
        assertTrue(defaultModel.isDefault)
        assertEquals("(Server Default)", defaultModel.technicalId)
        assertEquals(ProviderInfo.ID_SERVER_DEFAULT, defaultModel.providerId)
    }

    @Test
    fun `findModel with null or blank returns server default`() {
        assertSame(ModelRegistry.SERVER_DEFAULT_MODEL, ModelRegistry.findModel(null))
        assertSame(ModelRegistry.SERVER_DEFAULT_MODEL, ModelRegistry.findModel(""))
        assertSame(ModelRegistry.SERVER_DEFAULT_MODEL, ModelRegistry.findModel("   "))
    }

    @Test
    fun `findModel matches curated models case-insensitively`() {
        val sonnet = ModelRegistry.findModel("openrouter/anthropic/claude-3.7-sonnet")
        assertEquals("Claude 3.7 Sonnet (OpenRouter)", sonnet.displayName)
        assertEquals(ProviderInfo.ID_OPENROUTER, sonnet.providerId)
        assertTrue(sonnet.isReasoning)
        assertEquals(200_000, sonnet.contextWindow)
        assertFalse(sonnet.isCustom)
        assertFalse(sonnet.isDefault)
        assertEquals("openrouter/anthropic/claude-3.7-sonnet", sonnet.apiValue)

        // Case insensitivity
        val sonnetUpper = ModelRegistry.findModel("OPENROUTER/ANTHROPIC/CLAUDE-3.7-SONNET")
        assertEquals(sonnet.id, sonnetUpper.id)

        // Nous model
        val nousModel = ModelRegistry.findModel("nous/hermes-3-llama-3.1-405b")
        assertEquals("Hermes 3 Llama 3.1 405B", nousModel.displayName)
        assertEquals(ProviderInfo.ID_NOUS, nousModel.providerId)
    }

    @Test
    fun `findModel handles unlisted custom models cleanly and infers provider prefix`() {
        val openRouterCustom = ModelRegistry.findModel("openrouter/meta-llama/llama-4-scout")
        assertTrue(openRouterCustom.isCustom)
        assertEquals("openrouter/meta-llama/llama-4-scout", openRouterCustom.id)
        assertEquals(ProviderInfo.ID_OPENROUTER, openRouterCustom.providerId)
        assertEquals("openrouter/meta-llama/llama-4-scout", openRouterCustom.apiValue)

        val anthropicCustom = ModelRegistry.findModel("anthropic/claude-4-opus-future")
        assertTrue(anthropicCustom.isCustom)
        assertEquals(ProviderInfo.ID_ANTHROPIC, anthropicCustom.providerId)

        val unknownCustom = ModelRegistry.findModel("my-custom-fine-tune-v1")
        assertTrue(unknownCustom.isCustom)
        assertEquals(ProviderInfo.ID_CUSTOM, unknownCustom.providerId)
    }

    @Test
    fun `custom model infers reasoning capabilities from identifier keywords`() {
        val r1Custom = ModelRegistry.findModel("ollama/custom-r1-distill")
        assertTrue(r1Custom.isReasoning)

        val o3Custom = ModelRegistry.findModel("openai/o3-custom-preview")
        assertTrue(o3Custom.isReasoning)

        val standardCustom = ModelRegistry.findModel("custom-standard-model")
        assertFalse(standardCustom.isReasoning)
    }

    @Test
    fun `providers are ordered properly and cover expected platforms`() {
        val providers = ModelRegistry.providers
        assertTrue(providers.isNotEmpty())
        assertEquals(ProviderInfo.ID_SERVER_DEFAULT, providers.first().id)

        val providerIds = providers.map { it.id }
        assertTrue(providerIds.contains(ProviderInfo.ID_NOUS))
        assertTrue(providerIds.contains(ProviderInfo.ID_OPENROUTER))
        assertTrue(providerIds.contains(ProviderInfo.ID_ANTHROPIC))
        assertTrue(providerIds.contains(ProviderInfo.ID_OPENAI))
        assertTrue(providerIds.contains(ProviderInfo.ID_GOOGLE))
        assertTrue(providerIds.contains(ProviderInfo.ID_DEEPSEEK))
        assertTrue(providerIds.contains(ProviderInfo.ID_GROQ))
        assertTrue(providerIds.contains(ProviderInfo.ID_OLLAMA))
        assertTrue(providerIds.contains(ProviderInfo.ID_CUSTOM))

        // Ensure sorted by order
        for (i in 0 until providers.size - 1) {
            assertTrue(providers[i].order <= providers[i + 1].order)
        }
    }

    @Test
    fun `getProvider returns correct provider or fallback`() {
        assertEquals("Nous Research", ModelRegistry.getProvider("nous").displayName)
        assertEquals("OpenRouter", ModelRegistry.getProvider("OPENROUTER").displayName)
        assertEquals(ProviderInfo.ID_SERVER_DEFAULT, ModelRegistry.getProvider("").id)
        assertEquals(ProviderInfo.ID_CUSTOM, ModelRegistry.getProvider("nonexistent-provider").id)
    }

    @Test
    fun `getModelsForProvider filters models correctly`() {
        val nousModels = ModelRegistry.getModelsForProvider(ProviderInfo.ID_NOUS)
        assertTrue(nousModels.isNotEmpty())
        assertTrue(nousModels.all { it.providerId == ProviderInfo.ID_NOUS })
    }

    @Test
    fun `getModelsGroupedByProvider maintains provider ordering and excludes empty providers`() {
        val grouped = ModelRegistry.getModelsGroupedByProvider()
        assertTrue(grouped.isNotEmpty())
        // Each entry has at least 1 model
        for ((provider, models) in grouped) {
            assertTrue(models.isNotEmpty())
            assertTrue(models.all { it.providerId == provider.id })
        }
    }

    @Test
    fun `searchModels filters by name, ID, tags, and ranks relevant results first`() {
        // Blank search returns all models
        val all = ModelRegistry.searchModels("")
        assertEquals(ModelRegistry.models.size, all.size)

        // Query for claude
        val claudeResults = ModelRegistry.searchModels("claude", includeCustomCandidate = false)
        assertTrue(claudeResults.isNotEmpty())
        assertTrue(claudeResults.all {
            it.displayName.contains("claude", ignoreCase = true) ||
            it.id.contains("claude", ignoreCase = true) ||
            it.tags.any { tag -> tag.contains("claude", ignoreCase = true) }
        })

        // Query by tag "reasoning"
        val reasoningResults = ModelRegistry.searchModels("reasoning", includeCustomCandidate = false)
        assertTrue(reasoningResults.isNotEmpty())
        assertTrue(reasoningResults.any { it.isReasoning })

        // Search with custom query adds custom candidate at end
        val query = "super-custom-experimental-model"
        val customSearchResults = ModelRegistry.searchModels(query, includeCustomCandidate = true)
        assertEquals(1, customSearchResults.size)
        val candidate = customSearchResults.first()
        assertTrue(candidate.isCustom)
        assertEquals(query, candidate.id)
    }

    @Test
    fun `context window token formatter formats various counts correctly`() {
        assertEquals("128k", ModelInfo.formatTokenCount(128_000))
        assertEquals("200k", ModelInfo.formatTokenCount(200_000))
        assertEquals("1M", ModelInfo.formatTokenCount(1_000_000))
        assertEquals("2M", ModelInfo.formatTokenCount(2_000_000))
        assertEquals("1.5M", ModelInfo.formatTokenCount(1_500_000))
        assertEquals("500", ModelInfo.formatTokenCount(500))

        val modelWithContext = ModelInfo(
            id = "test/model",
            displayName = "Test Model",
            providerId = ProviderInfo.ID_CUSTOM,
            contextWindow = 128_000,
        )
        assertEquals("128k", modelWithContext.formattedContextWindow)
    }
}
