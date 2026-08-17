package dev.hermesprompt.app.data.models

import kotlinx.serialization.Serializable

/**
 * Metadata defining a model provider (e.g. Nous Research, OpenRouter, Anthropic).
 *
 * @param id Unique provider identifier used in routing and grouping.
 * @param displayName Human-readable name shown in dropdowns and headers.
 * @param description Short summary of the provider or its role.
 * @param websiteUrl Optional link to the provider's portal or documentation.
 * @param order Display ordering priority in UI dropdowns (lower comes first).
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val websiteUrl: String? = null,
    val order: Int = 100,
) {
    companion object {
        const val ID_SERVER_DEFAULT = "default"
        const val ID_NOUS = "nous"
        const val ID_OPENROUTER = "openrouter"
        const val ID_ANTHROPIC = "anthropic"
        const val ID_OPENAI = "openai"
        const val ID_GOOGLE = "google"
        const val ID_DEEPSEEK = "deepseek"
        const val ID_GROQ = "groq"
        const val ID_MISTRAL = "mistral"
        const val ID_OLLAMA = "ollama"
        const val ID_CUSTOM = "custom"
    }
}
