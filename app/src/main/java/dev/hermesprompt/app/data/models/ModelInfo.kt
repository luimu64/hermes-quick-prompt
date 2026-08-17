package dev.hermesprompt.app.data.models

import kotlinx.serialization.Serializable

/**
 * Metadata defining a model configuration available in Hermes Agent.
 *
 * @param id The exact identifier sent to Hermes backend (e.g. "openrouter/anthropic/claude-3.7-sonnet" or "" for default).
 * @param displayName Friendly human-readable name shown in UI selectors (e.g. "Claude 3.7 Sonnet").
 * @param providerId Identifier of the owning [ProviderInfo].
 * @param description Brief description of capabilities, specialization, or tier.
 * @param contextWindow Maximum context token limit (e.g. 200_000).
 * @param isReasoning Whether the model features reasoning / thought chain capabilities.
 * @param isCustom Whether this model was custom-defined by the user rather than pre-registered.
 * @param isDefault Whether this model represents the server-configured default (empty ID).
 * @param tags Search and filtering tags (e.g. "coding", "fast", "flagship", "vision").
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val providerId: String,
    val description: String? = null,
    val contextWindow: Int? = null,
    val isReasoning: Boolean = false,
    val isCustom: Boolean = false,
    val isDefault: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    /**
     * Technical identifier or fallback text.
     * E.g. "(Server Default)" when empty, or exact model ID string.
     */
    val technicalId: String
        get() = if (id.isBlank()) "(Server Default)" else id

    /**
     * Value passed in Hermes API requests (`POST /v1/runs`).
     * Blank / empty strings resolve to `null`, letting Hermes use its server default.
     */
    val apiValue: String?
        get() = id.trim().takeIf { it.isNotBlank() }

    /**
     * Human-friendly formatted context window string (e.g. "128k", "200k", "1M").
     */
    val formattedContextWindow: String?
        get() = contextWindow?.let { formatTokenCount(it) }

    companion object {
        fun formatTokenCount(tokens: Int): String {
            return when {
                tokens >= 1_000_000 -> {
                    val count = tokens / 1_000_000.0
                    if (count % 1.0 == 0.0) "${count.toInt()}M" else String.format("%.1fM", count)
                }
                tokens >= 1_000 -> {
                    val count = tokens / 1_000.0
                    if (count % 1.0 == 0.0) "${count.toInt()}k" else String.format("%.1fk", count)
                }
                else -> tokens.toString()
            }
        }
    }
}
