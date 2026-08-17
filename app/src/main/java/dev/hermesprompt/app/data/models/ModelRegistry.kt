package dev.hermesprompt.app.data.models

/**
 * Provider representing a hosting platform or LLM provider.
 */
data class Provider(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val sortOrder: Int = 0,
)

/**
 * ModelOption representing a selectable model with friendly name and technical ID.
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val provider: Provider,
    val description: String? = null,
    val contextWindow: String? = null,
    val isCustom: Boolean = false,
) {
    /** True if this represents the server default (empty ID). */
    val isDefault: Boolean get() = id.isBlank()

    /** Label suitable for display in text fields or dropdown headers. */
    val fullDisplayLabel: String
        get() = if (id.isBlank()) displayName else "$displayName ($id)"
}

/**
 * Model registry providing a curated catalog of standard LLM providers and models,
 * fuzzy search, provider grouping, and custom model resolution.
 */
object ModelRegistry {

    val PROVIDER_DEFAULT = Provider(
        id = "default",
        displayName = "Default",
        description = "Server default configuration",
        sortOrder = 0,
    )

    val PROVIDER_NOUS = Provider(
        id = "nous",
        displayName = "Nous Research",
        description = "Hermes and Nous Research models",
        sortOrder = 1,
    )

    val PROVIDER_OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        description = "Unified multi-provider API",
        sortOrder = 2,
    )

    val PROVIDER_ANTHROPIC = Provider(
        id = "anthropic",
        displayName = "Anthropic",
        description = "Claude 3.7, 3.5, and Opus models",
        sortOrder = 3,
    )

    val PROVIDER_OPENAI = Provider(
        id = "openai",
        displayName = "OpenAI",
        description = "GPT-4o, o1, and o3 series",
        sortOrder = 4,
    )

    val PROVIDER_GOOGLE = Provider(
        id = "google",
        displayName = "Google",
        description = "Gemini 2.0 and 1.5 series",
        sortOrder = 5,
    )

    val PROVIDER_DEEPSEEK = Provider(
        id = "deepseek",
        displayName = "DeepSeek",
        description = "DeepSeek V3 and R1 reasoning models",
        sortOrder = 6,
    )

    val PROVIDER_GROQ = Provider(
        id = "groq",
        displayName = "Groq",
        description = "Ultra-low-latency hosted models",
        sortOrder = 7,
    )

    val PROVIDER_OLLAMA = Provider(
        id = "ollama",
        displayName = "Local / Ollama",
        description = "Locally hosted models",
        sortOrder = 8,
    )

    val PROVIDER_CUSTOM = Provider(
        id = "custom",
        displayName = "Custom",
        description = "User-specified custom model",
        sortOrder = 99,
    )

    val DEFAULT_MODEL = ModelOption(
        id = "",
        displayName = "Server Default",
        provider = PROVIDER_DEFAULT,
        description = "Use default model configured on Hermes server",
    )

    val defaultModels: List<ModelOption> = listOf(
        DEFAULT_MODEL,

        // Nous Research
        ModelOption(
            id = "nousresearch/hermes-3-llama-3.1-405b",
            displayName = "Hermes 3 Llama 3.1 405B",
            provider = PROVIDER_NOUS,
            contextWindow = "128k",
            description = "Nous flagship 405B Hermes model",
        ),
        ModelOption(
            id = "nousresearch/hermes-3-llama-3.1-70b",
            displayName = "Hermes 3 Llama 3.1 70B",
            provider = PROVIDER_NOUS,
            contextWindow = "128k",
            description = "High-intelligence 70B general & agent model",
        ),
        ModelOption(
            id = "nousresearch/hermes-3-llama-3.1-8b",
            displayName = "Hermes 3 Llama 3.1 8B",
            provider = PROVIDER_NOUS,
            contextWindow = "128k",
            description = "Fast 8B general agent model",
        ),
        ModelOption(
            id = "nousresearch/hermes-2-pro-llama-3-8b",
            displayName = "Hermes 2 Pro Llama 3 8B",
            provider = PROVIDER_NOUS,
            contextWindow = "8k",
            description = "Function calling & tool use 8B model",
        ),

        // OpenRouter
        ModelOption(
            id = "openrouter/anthropic/claude-3.7-sonnet",
            displayName = "Claude 3.7 Sonnet (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "200k",
            description = "Anthropic hybrid reasoning & coding model",
        ),
        ModelOption(
            id = "openrouter/anthropic/claude-3.5-sonnet",
            displayName = "Claude 3.5 Sonnet (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "200k",
            description = "Anthropic flagship coding model",
        ),
        ModelOption(
            id = "openrouter/openai/gpt-4o",
            displayName = "GPT-4o (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "128k",
            description = "OpenAI multimodal flagship",
        ),
        ModelOption(
            id = "openrouter/google/gemini-2.0-flash-001",
            displayName = "Gemini 2.0 Flash (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "1M",
            description = "Fast, multimodal with 1M context",
        ),
        ModelOption(
            id = "openrouter/deepseek/deepseek-r1",
            displayName = "DeepSeek R1 (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "64k",
            description = "Open-weights reasoning model",
        ),
        ModelOption(
            id = "openrouter/deepseek/deepseek-chat",
            displayName = "DeepSeek V3 (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "64k",
            description = "Open-weights general model",
        ),
        ModelOption(
            id = "openrouter/meta-llama/llama-3.3-70b-instruct",
            displayName = "Llama 3.3 70B (OpenRouter)",
            provider = PROVIDER_OPENROUTER,
            contextWindow = "128k",
            description = "Meta high-capability open model",
        ),

        // Anthropic
        ModelOption(
            id = "anthropic/claude-3-7-sonnet-20250219",
            displayName = "Claude 3.7 Sonnet",
            provider = PROVIDER_ANTHROPIC,
            contextWindow = "200k",
            description = "Hybrid reasoning & agentic coding",
        ),
        ModelOption(
            id = "anthropic/claude-3-5-sonnet-20241022",
            displayName = "Claude 3.5 Sonnet",
            provider = PROVIDER_ANTHROPIC,
            contextWindow = "200k",
            description = "Industry-standard coding & reasoning",
        ),
        ModelOption(
            id = "anthropic/claude-3-5-haiku-20241022",
            displayName = "Claude 3.5 Haiku",
            provider = PROVIDER_ANTHROPIC,
            contextWindow = "200k",
            description = "Fast, lightweight assistant",
        ),
        ModelOption(
            id = "anthropic/claude-3-opus-20240229",
            displayName = "Claude 3 Opus",
            provider = PROVIDER_ANTHROPIC,
            contextWindow = "200k",
            description = "Deep analysis and complex reasoning",
        ),

        // OpenAI
        ModelOption(
            id = "openai/gpt-4o",
            displayName = "GPT-4o",
            provider = PROVIDER_OPENAI,
            contextWindow = "128k",
            description = "Versatile flagship model",
        ),
        ModelOption(
            id = "openai/gpt-4o-mini",
            displayName = "GPT-4o Mini",
            provider = PROVIDER_OPENAI,
            contextWindow = "128k",
            description = "Fast, lightweight daily assistant",
        ),
        ModelOption(
            id = "openai/o1",
            displayName = "o1",
            provider = PROVIDER_OPENAI,
            contextWindow = "200k",
            description = "Deep reasoning model",
        ),
        ModelOption(
            id = "openai/o3-mini",
            displayName = "o3-mini",
            provider = PROVIDER_OPENAI,
            contextWindow = "200k",
            description = "Fast STEM & coding reasoning",
        ),

        // Google
        ModelOption(
            id = "google/gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            provider = PROVIDER_GOOGLE,
            contextWindow = "1M",
            description = "Next-gen multimodal with 1M context",
        ),
        ModelOption(
            id = "google/gemini-2.0-pro-exp-02-05",
            displayName = "Gemini 2.0 Pro",
            provider = PROVIDER_GOOGLE,
            contextWindow = "2M",
            description = "Advanced reasoning & 2M context",
        ),
        ModelOption(
            id = "google/gemini-1.5-pro",
            displayName = "Gemini 1.5 Pro",
            provider = PROVIDER_GOOGLE,
            contextWindow = "2M",
            description = "Long context analysis",
        ),

        // DeepSeek
        ModelOption(
            id = "deepseek/deepseek-chat",
            displayName = "DeepSeek V3",
            provider = PROVIDER_DEEPSEEK,
            contextWindow = "64k",
            description = "Efficient general & coding model",
        ),
        ModelOption(
            id = "deepseek/deepseek-reasoner",
            displayName = "DeepSeek R1",
            provider = PROVIDER_DEEPSEEK,
            contextWindow = "64k",
            description = "Reinforcement learning reasoning model",
        ),

        // Groq
        ModelOption(
            id = "groq/llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B (Groq)",
            provider = PROVIDER_GROQ,
            contextWindow = "128k",
            description = "Ultra-fast LPU inference",
        ),
        ModelOption(
            id = "groq/deepseek-r1-distill-llama-70b",
            displayName = "DeepSeek R1 Distill 70B (Groq)",
            provider = PROVIDER_GROQ,
            contextWindow = "128k",
            description = "High-speed reasoning model",
        ),

        // Ollama / Local
        ModelOption(
            id = "ollama/qwen2.5-coder:32b",
            displayName = "Qwen 2.5 Coder 32B (Local)",
            provider = PROVIDER_OLLAMA,
            contextWindow = "32k",
            description = "Local coding specialist",
        ),
        ModelOption(
            id = "ollama/llama3.2",
            displayName = "Llama 3.2 (Local)",
            provider = PROVIDER_OLLAMA,
            contextWindow = "128k",
            description = "Lightweight local model",
        ),
    )

    /**
     * Resolves a model ID string to a [ModelOption].
     * If the ID is empty, returns [DEFAULT_MODEL].
     * If the ID matches a registered model, returns it.
     * Otherwise returns a custom [ModelOption] with an inferred or custom provider.
     */
    fun resolveModel(id: String?): ModelOption {
        val trimmed = id?.trim() ?: ""
        if (trimmed.isEmpty()) return DEFAULT_MODEL
        val existing = defaultModels.find { it.id.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing

        val provider = inferProvider(trimmed) ?: PROVIDER_CUSTOM
        return ModelOption(
            id = trimmed,
            displayName = formatCustomDisplayName(trimmed),
            provider = provider,
            description = "Custom model override",
            isCustom = true,
        )
    }

    /**
     * Infers a provider from a model ID string based on known prefixes.
     */
    fun inferProvider(modelId: String): Provider? {
        val lower = modelId.lowercase()
        return when {
            lower.startsWith("nousresearch/") || lower.startsWith("nous/") -> PROVIDER_NOUS
            lower.startsWith("openrouter/") -> PROVIDER_OPENROUTER
            lower.startsWith("anthropic/") || lower.startsWith("claude") -> PROVIDER_ANTHROPIC
            lower.startsWith("openai/") || lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3") -> PROVIDER_OPENAI
            lower.startsWith("google/") || lower.startsWith("gemini") -> PROVIDER_GOOGLE
            lower.startsWith("deepseek/") -> PROVIDER_DEEPSEEK
            lower.startsWith("groq/") -> PROVIDER_GROQ
            lower.startsWith("ollama/") || lower.startsWith("local/") -> PROVIDER_OLLAMA
            else -> null
        }
    }

    private fun formatCustomDisplayName(id: String): String {
        val leaf = id.substringAfterLast("/")
        return leaf.ifBlank { id }
    }

    /**
     * Filters models by a search query using fuzzy matching across displayName,
     * technical ID, provider name, and description.
     */
    fun filterModels(query: String, models: List<ModelOption> = defaultModels): List<ModelOption> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return models

        return models
            .mapNotNull { model ->
                val score = calculateMatchScore(cleanQuery, model)
                if (score > 0) Pair(model, score) else null
            }
            .sortedWith(
                compareByDescending<Pair<ModelOption, Int>> { it.second }
                    .thenBy { it.first.provider.sortOrder }
                    .thenBy { it.first.displayName }
            )
            .map { it.first }
    }

    /**
     * Calculates a fuzzy match score. Returns > 0 if there is a match, higher score = better match.
     */
    private fun calculateMatchScore(query: String, model: ModelOption): Int {
        val q = query.lowercase()
        val idLower = model.id.lowercase()
        val nameLower = model.displayName.lowercase()
        val providerLower = model.provider.displayName.lowercase()
        val descLower = (model.description ?: "").lowercase()

        // Exact match
        if (idLower == q || nameLower == q) return 1000

        // Prefix match
        if (nameLower.startsWith(q)) return 800
        if (idLower.startsWith(q)) return 750

        // Substring match in name
        if (nameLower.contains(q)) return 500
        // Substring match in ID
        if (idLower.contains(q)) return 400
        // Substring match in provider name
        if (providerLower.contains(q)) return 300
        // Substring match in description
        if (descLower.contains(q)) return 200

        // Multi-word token match: all words in query appear somewhere in model metadata
        val tokens = q.split(" ", "-", "_", "/").filter { it.isNotBlank() }
        if (tokens.size > 1) {
            val allTokensMatch = tokens.all { token ->
                nameLower.contains(token) || idLower.contains(token) || providerLower.contains(token) || descLower.contains(token)
            }
            if (allTokensMatch) return 350
        }

        // Fuzzy subsequence match in name or ID (e.g. "c37s" matching "claude-3-7-sonnet")
        if (isSubsequenceMatch(q, nameLower)) return 150
        if (isSubsequenceMatch(q, idLower)) return 100

        return 0
    }

    private fun isSubsequenceMatch(query: String, target: String): Boolean {
        if (query.length > target.length) return false
        var queryIdx = 0
        var targetIdx = 0
        while (queryIdx < query.length && targetIdx < target.length) {
            if (query[queryIdx] == target[targetIdx]) {
                queryIdx++
            }
            targetIdx++
        }
        return queryIdx == query.length
    }
}
