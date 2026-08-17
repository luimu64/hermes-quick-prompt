package dev.hermesprompt.app.data.models

import java.util.Locale

/**
 * Central registry and configuration for LLM providers and models supported by Hermes Agent.
 *
 * Provides:
 * - Structured metadata for providers and models (IDs, display names, context windows, tags).
 * - Lookup by ID, resolving empty strings to the server default.
 * - Dynamic handling and parsing of custom or unlisted model strings.
 * - Search, fuzzy matching, and ranking for dropdowns and comboboxes.
 * - Provider-based grouping.
 */
object ModelRegistry {

    // ── Providers ─────────────────────────────────────────────────────────────

    val DEFAULT_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_SERVER_DEFAULT,
        displayName = "Default",
        description = "Uses the server's default configuration",
        order = 0,
    )

    val NOUS_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_NOUS,
        displayName = "Nous Research",
        description = "Hermes and Nous Research open-weight agent models",
        websiteUrl = "https://nousresearch.com",
        order = 1,
    )

    val OPENROUTER_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_OPENROUTER,
        displayName = "OpenRouter",
        description = "Universal routing gateway across commercial and open models",
        websiteUrl = "https://openrouter.ai",
        order = 2,
    )

    val ANTHROPIC_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_ANTHROPIC,
        displayName = "Anthropic",
        description = "Claude 3.7 Sonnet, 3.5 Sonnet, and Haiku models",
        websiteUrl = "https://anthropic.com",
        order = 3,
    )

    val OPENAI_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_OPENAI,
        displayName = "OpenAI",
        description = "GPT-4o, o3-mini, and o1 models",
        websiteUrl = "https://openai.com",
        order = 4,
    )

    val GOOGLE_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_GOOGLE,
        displayName = "Google Gemini",
        description = "Gemini 2.5 Flash, 2.5 Pro, and 2.0 models",
        websiteUrl = "https://deepmind.google/technologies/gemini",
        order = 5,
    )

    val DEEPSEEK_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_DEEPSEEK,
        displayName = "DeepSeek",
        description = "DeepSeek V3 and DeepSeek R1 reasoning models",
        websiteUrl = "https://deepseek.com",
        order = 6,
    )

    val GROQ_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_GROQ,
        displayName = "Groq",
        description = "Ultra-low latency LPU inference for open models",
        websiteUrl = "https://groq.com",
        order = 7,
    )

    val MISTRAL_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_MISTRAL,
        displayName = "Mistral AI",
        description = "Mistral Large, Codestral, and open models",
        websiteUrl = "https://mistral.ai",
        order = 8,
    )

    val OLLAMA_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_OLLAMA,
        displayName = "Ollama (Local)",
        description = "Local models hosted via Ollama",
        websiteUrl = "https://ollama.com",
        order = 9,
    )

    val CUSTOM_PROVIDER = ProviderInfo(
        id = ProviderInfo.ID_CUSTOM,
        displayName = "Custom / Unlisted",
        description = "User-specified custom model strings",
        order = 99,
    )

    val providers: List<ProviderInfo> = listOf(
        DEFAULT_PROVIDER,
        NOUS_PROVIDER,
        OPENROUTER_PROVIDER,
        ANTHROPIC_PROVIDER,
        OPENAI_PROVIDER,
        GOOGLE_PROVIDER,
        DEEPSEEK_PROVIDER,
        GROQ_PROVIDER,
        MISTRAL_PROVIDER,
        OLLAMA_PROVIDER,
        CUSTOM_PROVIDER,
    ).sortedBy { it.order }

    private val providerMap: Map<String, ProviderInfo> = providers.associateBy { it.id.lowercase(Locale.ROOT) }

    // ── Server Default Constant ───────────────────────────────────────────────

    val SERVER_DEFAULT_MODEL = ModelInfo(
        id = "",
        displayName = "Server Default",
        providerId = ProviderInfo.ID_SERVER_DEFAULT,
        description = "Use the model configured by the Hermes Agent server",
        isDefault = true,
        tags = listOf("default", "server"),
    )

    // ── Curated Model Catalog ─────────────────────────────────────────────────

    val models: List<ModelInfo> = listOf(
        SERVER_DEFAULT_MODEL,

        // Nous Research
        ModelInfo(
            id = "nous/hermes-3-llama-3.1-405b",
            displayName = "Hermes 3 Llama 3.1 405B",
            providerId = ProviderInfo.ID_NOUS,
            description = "Flagship open-weights agentic model by Nous Research",
            contextWindow = 128_000,
            tags = listOf("nous", "hermes", "flagship", "agentic", "open-weights"),
        ),
        ModelInfo(
            id = "nous/hermes-3-llama-3.1-70b",
            displayName = "Hermes 3 Llama 3.1 70B",
            providerId = ProviderInfo.ID_NOUS,
            description = "High capability agentic model for general reasoning",
            contextWindow = 128_000,
            tags = listOf("nous", "hermes", "agentic", "recommended"),
        ),
        ModelInfo(
            id = "nous/hermes-3-llama-3.1-8b",
            displayName = "Hermes 3 Llama 3.1 8B",
            providerId = ProviderInfo.ID_NOUS,
            description = "Lightweight, fast agentic model",
            contextWindow = 128_000,
            tags = listOf("nous", "hermes", "fast", "lightweight"),
        ),
        ModelInfo(
            id = "nous/hermes-2-pro-llama-3-8b",
            displayName = "Hermes 2 Pro Llama 3 8B",
            providerId = ProviderInfo.ID_NOUS,
            description = "Tool calling specialist model",
            contextWindow = 8_192,
            tags = listOf("nous", "hermes", "tools"),
        ),

        // OpenRouter Curated
        ModelInfo(
            id = "openrouter/anthropic/claude-3.7-sonnet",
            displayName = "Claude 3.7 Sonnet (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Hybrid reasoning and coding model with extended thinking",
            contextWindow = 200_000,
            isReasoning = true,
            tags = listOf("openrouter", "anthropic", "claude", "reasoning", "coding", "flagship"),
        ),
        ModelInfo(
            id = "openrouter/anthropic/claude-3.5-sonnet",
            displayName = "Claude 3.5 Sonnet (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Industry benchmark for agentic coding and reasoning",
            contextWindow = 200_000,
            tags = listOf("openrouter", "anthropic", "claude", "coding", "agentic"),
        ),
        ModelInfo(
            id = "openrouter/anthropic/claude-3.5-haiku",
            displayName = "Claude 3.5 Haiku (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Ultra-fast lightweight intelligence",
            contextWindow = 200_000,
            tags = listOf("openrouter", "anthropic", "claude", "fast"),
        ),
        ModelInfo(
            id = "openrouter/openai/gpt-4o",
            displayName = "GPT-4o (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Flagship multimodal intelligence from OpenAI",
            contextWindow = 128_000,
            tags = listOf("openrouter", "openai", "gpt-4o", "multimodal"),
        ),
        ModelInfo(
            id = "openrouter/openai/gpt-4o-mini",
            displayName = "GPT-4o Mini (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Fast, cost-efficient multimodal model",
            contextWindow = 128_000,
            tags = listOf("openrouter", "openai", "gpt-4o", "fast"),
        ),
        ModelInfo(
            id = "openrouter/openai/o3-mini",
            displayName = "o3-mini (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "High-tier STEM and code reasoning model",
            contextWindow = 200_000,
            isReasoning = true,
            tags = listOf("openrouter", "openai", "reasoning", "math", "coding"),
        ),
        ModelInfo(
            id = "openrouter/deepseek/deepseek-r1",
            displayName = "DeepSeek R1 (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Open reasoning model with chain-of-thought",
            contextWindow = 64_000,
            isReasoning = true,
            tags = listOf("openrouter", "deepseek", "reasoning", "open-weights"),
        ),
        ModelInfo(
            id = "openrouter/deepseek/deepseek-chat",
            displayName = "DeepSeek V3 (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Fast 671B MoE model with strong general capabilities",
            contextWindow = 64_000,
            tags = listOf("openrouter", "deepseek", "moe", "fast"),
        ),
        ModelInfo(
            id = "openrouter/google/gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Next-gen fast multimodal model with 1M context",
            contextWindow = 1_000_000,
            tags = listOf("openrouter", "google", "gemini", "fast", "large-context"),
        ),
        ModelInfo(
            id = "openrouter/google/gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Advanced reasoning and coding with 2M context",
            contextWindow = 2_000_000,
            tags = listOf("openrouter", "google", "gemini", "reasoning", "large-context"),
        ),
        ModelInfo(
            id = "openrouter/meta-llama/llama-3.3-70b-instruct",
            displayName = "Llama 3.3 70B Instruct (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Flagship open weights model from Meta",
            contextWindow = 128_000,
            tags = listOf("openrouter", "meta", "llama", "open-weights"),
        ),
        ModelInfo(
            id = "openrouter/qwen/qwen-2.5-coder-32b-instruct",
            displayName = "Qwen 2.5 Coder 32B (OpenRouter)",
            providerId = ProviderInfo.ID_OPENROUTER,
            description = "Specialized open-source code generation model",
            contextWindow = 128_000,
            tags = listOf("openrouter", "qwen", "coding", "open-weights"),
        ),

        // Anthropic Direct
        ModelInfo(
            id = "anthropic/claude-3-7-sonnet-20250219",
            displayName = "Claude 3.7 Sonnet",
            providerId = ProviderInfo.ID_ANTHROPIC,
            description = "Direct Anthropic API: Hybrid reasoning and coding",
            contextWindow = 200_000,
            isReasoning = true,
            tags = listOf("anthropic", "claude", "reasoning", "flagship"),
        ),
        ModelInfo(
            id = "anthropic/claude-3-5-sonnet-20241022",
            displayName = "Claude 3.5 Sonnet",
            providerId = ProviderInfo.ID_ANTHROPIC,
            description = "Direct Anthropic API: Top coding & reasoning",
            contextWindow = 200_000,
            tags = listOf("anthropic", "claude", "coding"),
        ),
        ModelInfo(
            id = "anthropic/claude-3-5-haiku-20241022",
            displayName = "Claude 3.5 Haiku",
            providerId = ProviderInfo.ID_ANTHROPIC,
            description = "Direct Anthropic API: Fast and efficient",
            contextWindow = 200_000,
            tags = listOf("anthropic", "claude", "fast"),
        ),
        ModelInfo(
            id = "anthropic/claude-3-opus-20240229",
            displayName = "Claude 3 Opus",
            providerId = ProviderInfo.ID_ANTHROPIC,
            description = "Direct Anthropic API: Deep analysis and writing",
            contextWindow = 200_000,
            tags = listOf("anthropic", "claude", "writing"),
        ),

        // OpenAI Direct
        ModelInfo(
            id = "openai/gpt-4o",
            displayName = "GPT-4o",
            providerId = ProviderInfo.ID_OPENAI,
            description = "Direct OpenAI API: Flagship multimodal model",
            contextWindow = 128_000,
            tags = listOf("openai", "gpt-4o", "multimodal"),
        ),
        ModelInfo(
            id = "openai/gpt-4o-mini",
            displayName = "GPT-4o Mini",
            providerId = ProviderInfo.ID_OPENAI,
            description = "Direct OpenAI API: Fast, lightweight multimodal",
            contextWindow = 128_000,
            tags = listOf("openai", "gpt-4o", "fast"),
        ),
        ModelInfo(
            id = "openai/o3-mini",
            displayName = "o3-mini",
            providerId = ProviderInfo.ID_OPENAI,
            description = "Direct OpenAI API: Reasoning model for coding and math",
            contextWindow = 200_000,
            isReasoning = true,
            tags = listOf("openai", "reasoning", "coding"),
        ),
        ModelInfo(
            id = "openai/o1",
            displayName = "o1",
            providerId = ProviderInfo.ID_OPENAI,
            description = "Direct OpenAI API: Advanced full reasoning model",
            contextWindow = 200_000,
            isReasoning = true,
            tags = listOf("openai", "reasoning", "flagship"),
        ),

        // Google Direct
        ModelInfo(
            id = "google/gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            providerId = ProviderInfo.ID_GOOGLE,
            description = "Direct Google API: Advanced reasoning with 2M tokens",
            contextWindow = 2_000_000,
            tags = listOf("google", "gemini", "large-context"),
        ),
        ModelInfo(
            id = "google/gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            providerId = ProviderInfo.ID_GOOGLE,
            description = "Direct Google API: High-speed multimodal with 1M tokens",
            contextWindow = 1_000_000,
            tags = listOf("google", "gemini", "fast", "large-context"),
        ),
        ModelInfo(
            id = "google/gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            providerId = ProviderInfo.ID_GOOGLE,
            description = "Direct Google API: Low latency multimodal",
            contextWindow = 1_000_000,
            tags = listOf("google", "gemini", "fast"),
        ),

        // DeepSeek Direct
        ModelInfo(
            id = "deepseek/deepseek-chat",
            displayName = "DeepSeek V3",
            providerId = ProviderInfo.ID_DEEPSEEK,
            description = "Direct DeepSeek API: General knowledge and coding",
            contextWindow = 64_000,
            tags = listOf("deepseek", "fast"),
        ),
        ModelInfo(
            id = "deepseek/deepseek-reasoner",
            displayName = "DeepSeek R1",
            providerId = ProviderInfo.ID_DEEPSEEK,
            description = "Direct DeepSeek API: Extended thinking reasoning",
            contextWindow = 64_000,
            isReasoning = true,
            tags = listOf("deepseek", "reasoning"),
        ),

        // Groq Direct
        ModelInfo(
            id = "groq/llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B Versatile (Groq)",
            providerId = ProviderInfo.ID_GROQ,
            description = "Direct Groq API: Ultra-fast 70B inference",
            contextWindow = 128_000,
            tags = listOf("groq", "llama", "fast"),
        ),
        ModelInfo(
            id = "groq/llama-3.1-8b-instant",
            displayName = "Llama 3.1 8B Instant (Groq)",
            providerId = ProviderInfo.ID_GROQ,
            description = "Direct Groq API: Real-time speed 8B model",
            contextWindow = 128_000,
            tags = listOf("groq", "llama", "fast", "instant"),
        ),
        ModelInfo(
            id = "groq/deepseek-r1-distill-llama-70b",
            displayName = "DeepSeek R1 Distill 70B (Groq)",
            providerId = ProviderInfo.ID_GROQ,
            description = "Direct Groq API: Fast reasoning inference",
            contextWindow = 128_000,
            isReasoning = true,
            tags = listOf("groq", "deepseek", "reasoning", "fast"),
        ),

        // Mistral Direct
        ModelInfo(
            id = "mistral/mistral-large-latest",
            displayName = "Mistral Large",
            providerId = ProviderInfo.ID_MISTRAL,
            description = "Direct Mistral API: Flagship reasoning and multilingual",
            contextWindow = 128_000,
            tags = listOf("mistral", "flagship"),
        ),
        ModelInfo(
            id = "mistral/codestral-latest",
            displayName = "Codestral",
            providerId = ProviderInfo.ID_MISTRAL,
            description = "Direct Mistral API: Code generation specialist",
            contextWindow = 256_000,
            tags = listOf("mistral", "coding"),
        ),

        // Ollama Local
        ModelInfo(
            id = "ollama/hermes3",
            displayName = "Hermes 3 (Ollama)",
            providerId = ProviderInfo.ID_OLLAMA,
            description = "Local Hermes 3 instance via Ollama",
            contextWindow = 128_000,
            tags = listOf("ollama", "local", "nous", "hermes"),
        ),
        ModelInfo(
            id = "ollama/llama3.2",
            displayName = "Llama 3.2 (Ollama)",
            providerId = ProviderInfo.ID_OLLAMA,
            description = "Local lightweight Llama 3.2 model via Ollama",
            contextWindow = 128_000,
            tags = listOf("ollama", "local", "llama"),
        ),
        ModelInfo(
            id = "ollama/qwen2.5-coder",
            displayName = "Qwen 2.5 Coder (Ollama)",
            providerId = ProviderInfo.ID_OLLAMA,
            description = "Local code generation model via Ollama",
            contextWindow = 32_000,
            tags = listOf("ollama", "local", "coding"),
        ),
        ModelInfo(
            id = "ollama/deepseek-r1",
            displayName = "DeepSeek R1 (Ollama)",
            providerId = ProviderInfo.ID_OLLAMA,
            description = "Local reasoning model via Ollama",
            contextWindow = 64_000,
            isReasoning = true,
            tags = listOf("ollama", "local", "reasoning"),
        ),
    )

    private val modelMap: Map<String, ModelInfo> = models.associateBy { it.id.lowercase(Locale.ROOT) }

    /**
     * Recommended curated shortlist for quick selection.
     */
    val popularModels: List<ModelInfo> = listOf(
        SERVER_DEFAULT_MODEL,
        findModel("nous/hermes-3-llama-3.1-70b"),
        findModel("openrouter/anthropic/claude-3.7-sonnet"),
        findModel("openrouter/openai/gpt-4o"),
        findModel("openrouter/deepseek/deepseek-r1"),
        findModel("openrouter/google/gemini-2.5-flash"),
    )

    // ── Queries & Lookups ─────────────────────────────────────────────────────

    /**
     * Finds a provider by ID (case-insensitive).
     * Returns [CUSTOM_PROVIDER] if not recognized.
     */
    fun getProvider(providerId: String?): ProviderInfo {
        if (providerId.isNullOrBlank()) return DEFAULT_PROVIDER
        return providerMap[providerId.trim().lowercase(Locale.ROOT)] ?: CUSTOM_PROVIDER
    }

    /**
     * Returns all known models that belong to the specified [providerId].
     */
    fun getModelsForProvider(providerId: String): List<ModelInfo> {
        val target = providerId.trim().lowercase(Locale.ROOT)
        return models.filter { it.providerId.lowercase(Locale.ROOT) == target }
    }

    /**
     * Groups a list of models by their [ProviderInfo] in provider display order.
     */
    fun getModelsGroupedByProvider(modelsList: List<ModelInfo> = models): Map<ProviderInfo, List<ModelInfo>> {
        val grouped = LinkedHashMap<ProviderInfo, MutableList<ModelInfo>>()
        // Initialize with providers in order
        for (provider in providers) {
            grouped[provider] = mutableListOf()
        }
        for (model in modelsList) {
            val provider = getProvider(model.providerId)
            grouped.getOrPut(provider) { mutableListOf() }.add(model)
        }
        // Remove empty providers from map
        return grouped.filterValues { it.isNotEmpty() }
    }

    /**
     * Finds a model by exact or registered ID.
     *
     * - Null or blank strings return [SERVER_DEFAULT_MODEL].
     * - Known model IDs return their curated [ModelInfo].
     * - Unknown / custom model IDs are cleanly synthesized into a [ModelInfo]
     *   with [ModelInfo.isCustom] = true, inferring provider prefix where possible.
     */
    fun findModel(id: String?): ModelInfo {
        if (id.isNullOrBlank()) {
            return SERVER_DEFAULT_MODEL
        }
        val trimmed = id.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        val exactMatch = modelMap[lower]
        if (exactMatch != null) {
            return exactMatch
        }

        return createCustomModel(trimmed)
    }

    /**
     * Creates a structured [ModelInfo] for an arbitrary custom or unlisted model identifier.
     */
    fun createCustomModel(rawId: String, customDisplayName: String? = null): ModelInfo {
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) return SERVER_DEFAULT_MODEL

        val inferredProviderId = inferProviderId(trimmed)
        val name = customDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: formatCustomDisplayName(trimmed)

        val isReasoningInferred = trimmed.contains("r1", ignoreCase = true) ||
                trimmed.contains("o1", ignoreCase = true) ||
                trimmed.contains("o3", ignoreCase = true) ||
                trimmed.contains("thinking", ignoreCase = true) ||
                trimmed.contains("reasoning", ignoreCase = true)

        return ModelInfo(
            id = trimmed,
            displayName = name,
            providerId = inferredProviderId,
            description = "Custom model ($trimmed)",
            isReasoning = isReasoningInferred,
            isCustom = true,
            isDefault = false,
            tags = listOf("custom"),
        )
    }

    /**
     * Infers the provider ID from standard prefixes (e.g. "openrouter/...", "anthropic/...").
     */
    fun inferProviderId(modelId: String): String {
        val lower = modelId.trim().lowercase(Locale.ROOT)
        return when {
            lower.startsWith("nous/") -> ProviderInfo.ID_NOUS
            lower.startsWith("openrouter/") -> ProviderInfo.ID_OPENROUTER
            lower.startsWith("anthropic/") -> ProviderInfo.ID_ANTHROPIC
            lower.startsWith("openai/") -> ProviderInfo.ID_OPENAI
            lower.startsWith("google/") || lower.startsWith("gemini/") -> ProviderInfo.ID_GOOGLE
            lower.startsWith("deepseek/") -> ProviderInfo.ID_DEEPSEEK
            lower.startsWith("groq/") -> ProviderInfo.ID_GROQ
            lower.startsWith("mistral/") -> ProviderInfo.ID_MISTRAL
            lower.startsWith("ollama/") -> ProviderInfo.ID_OLLAMA
            else -> ProviderInfo.ID_CUSTOM
        }
    }

    /**
     * Formats a technical model ID into a presentable display string.
     */
    private fun formatCustomDisplayName(modelId: String): String {
        val lastSegment = modelId.substringAfterLast('/')
        // Capitalize words separated by hyphens/underscores/dots
        val words = lastSegment.split('-', '_', '.').filter { it.isNotBlank() }
        if (words.isEmpty()) return modelId
        return words.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    /**
     * Searches and filters models against a query string with ranking.
     *
     * If [includeCustomCandidate] is true and [query] is not blank and does not exactly
     * match an existing registered model ID, a synthesized custom model entry is appended.
     */
    fun searchModels(query: String, includeCustomCandidate: Boolean = true): List<ModelInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return models
        }
        val q = trimmed.lowercase(Locale.ROOT)

        val scored = mutableListOf<Pair<ModelInfo, Int>>()

        for (model in models) {
            val score = calculateMatchScore(model, q)
            if (score > 0) {
                scored.add(model to score)
            }
        }

        // Sort by score descending, then by original index
        val sortedResults = scored
            .sortedByDescending { it.second }
            .map { it.first }
            .toMutableList()

        if (includeCustomCandidate && sortedResults.none { it.id.equals(trimmed, ignoreCase = true) }) {
            sortedResults.add(createCustomModel(trimmed))
        }

        return sortedResults
    }

    private fun calculateMatchScore(model: ModelInfo, query: String): Int {
        val modelIdLower = model.id.lowercase(Locale.ROOT)
        val displayNameLower = model.displayName.lowercase(Locale.ROOT)
        val provider = getProvider(model.providerId)
        val providerNameLower = provider.displayName.lowercase(Locale.ROOT)
        val descLower = model.description?.lowercase(Locale.ROOT) ?: ""

        var score = 0

        // Exact match
        if (modelIdLower == query) score += 100
        if (displayNameLower == query) score += 90

        // Prefix match
        if (displayNameLower.startsWith(query)) score += 50
        if (modelIdLower.startsWith(query)) score += 40

        // Segment prefix (e.g. typing "sonnet" matches "claude-3.5-sonnet")
        val segments = modelIdLower.split('/', '-', '_')
        if (segments.any { it.startsWith(query) }) score += 35

        // Substring match
        if (displayNameLower.contains(query)) score += 30
        if (modelIdLower.contains(query)) score += 20
        if (providerNameLower.contains(query)) score += 15
        if (model.tags.any { it.lowercase(Locale.ROOT).contains(query) }) score += 15
        if (descLower.contains(query)) score += 5

        return score
    }
}
