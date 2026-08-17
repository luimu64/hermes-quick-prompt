package dev.hermesprompt.app.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `resolveModel handles empty string as server default`() {
        val model = ModelRegistry.resolveModel("")
        assertTrue(model.isDefault)
        assertEquals("Server Default", model.displayName)
        assertEquals("", model.id)
    }

    @Test
    fun `resolveModel handles whitespace-only string as server default`() {
        val model = ModelRegistry.resolveModel("   ")
        assertTrue(model.isDefault)
        assertEquals("Server Default", model.displayName)
        assertEquals("", model.id)
    }

    @Test
    fun `resolveModel handles null as server default`() {
        val model = ModelRegistry.resolveModel(null)
        assertTrue(model.isDefault)
        assertEquals("", model.id)
    }

    @Test
    fun `resolveModel finds registered model by exact id`() {
        val model = ModelRegistry.resolveModel("anthropic/claude-3-7-sonnet-20250219")
        assertFalse(model.isDefault)
        assertFalse(model.isCustom)
        assertEquals("Claude 3.7 Sonnet", model.displayName)
        assertEquals("anthropic", model.provider.id)
    }

    @Test
    fun `resolveModel is case-insensitive for registered model IDs`() {
        val model = ModelRegistry.resolveModel("ANTHROPIC/CLAUDE-3-7-SONNET-20250219")
        assertFalse(model.isDefault)
        assertFalse(model.isCustom)
        assertEquals("Claude 3.7 Sonnet", model.displayName)
        assertEquals("anthropic", model.provider.id)
    }

    @Test
    fun `resolveModel creates custom model with inferred provider`() {
        val model = ModelRegistry.resolveModel("openrouter/custom/my-fine-tune")
        assertTrue(model.isCustom)
        assertEquals("openrouter", model.provider.id)
        assertEquals("openrouter/custom/my-fine-tune", model.id)
        assertEquals("my-fine-tune", model.displayName)
    }

    @Test
    fun `resolveModel creates custom model with fallback custom provider`() {
        val model = ModelRegistry.resolveModel("custom-org/secret-model-v1")
        assertTrue(model.isCustom)
        assertEquals("custom", model.provider.id)
        assertEquals("custom-org/secret-model-v1", model.id)
    }

    @Test
    fun `filterModels with empty query returns all default models`() {
        val all = ModelRegistry.filterModels("")
        assertEquals(ModelRegistry.defaultModels.size, all.size)
    }

    @Test
    fun `filterModels filters by display name`() {
        val results = ModelRegistry.filterModels("Sonnet")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.displayName.contains("Sonnet", ignoreCase = true) || it.id.contains("sonnet", ignoreCase = true) })
    }

    @Test
    fun `filterModels filters by provider name`() {
        val results = ModelRegistry.filterModels("Google")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.provider.id == "google" })
    }

    @Test
    fun `filterModels supports multi-token search`() {
        val results = ModelRegistry.filterModels("openrouter claude sonnet")
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().id.contains("openrouter") && results.first().id.contains("sonnet"))
    }

    @Test
    fun `filterModels supports fuzzy subsequence search`() {
        // "c37s" matching claude-3-7-sonnet
        val results = ModelRegistry.filterModels("c37s")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.id.contains("claude-3-7-sonnet") || it.displayName.contains("Claude 3.7 Sonnet") })
    }

    @Test
    fun `filterModels returns empty list for completely unmatched random query`() {
        val results = ModelRegistry.filterModels("xyz999nonexistent123")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `inferProvider detects common prefixes`() {
        assertEquals("nous", ModelRegistry.inferProvider("nousresearch/custom")?.id)
        assertEquals("openrouter", ModelRegistry.inferProvider("openrouter/vendor/model")?.id)
        assertEquals("anthropic", ModelRegistry.inferProvider("anthropic/claude-next")?.id)
        assertEquals("openai", ModelRegistry.inferProvider("openai/gpt-5")?.id)
        assertEquals("google", ModelRegistry.inferProvider("google/gemini-ultra")?.id)
        assertEquals("deepseek", ModelRegistry.inferProvider("deepseek/deepseek-v4")?.id)
        assertEquals("groq", ModelRegistry.inferProvider("groq/custom-lpu")?.id)
        assertEquals("ollama", ModelRegistry.inferProvider("ollama/my-local")?.id)
        assertNull(ModelRegistry.inferProvider("unknown-vendor/model"))
    }

    @Test
    fun `fullDisplayLabel formats correctly for default and non-default models`() {
        assertEquals("Server Default", ModelRegistry.DEFAULT_MODEL.fullDisplayLabel)
        val custom = ModelOption(
            id = "openai/gpt-4o",
            displayName = "GPT-4o",
            provider = ModelRegistry.PROVIDER_OPENAI,
        )
        assertEquals("GPT-4o (openai/gpt-4o)", custom.fullDisplayLabel)
    }
}
