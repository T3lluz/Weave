package com.t3lluz.weave.data.remote

/** A Claude model as the picker lists it. */
data class AnthropicModelOption(
    val id: String,
    val displayName: String,
    val blurb: String,
)

/**
 * Claude's models and depths. The list is the account's own, read from Anthropic's Models API
 * ([AiModels.refresh]) as DailyDash's dashboard reads it; [options] stands in until then, or
 * when it can't be read. Depths are the ones `claude --effort` takes.
 */
object AnthropicModels {
    const val DEFAULT_ID = "claude-opus-5-5"

    /** Depths, shallow to deep, as `output_config.effort` takes them. */
    val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    const val DEFAULT_EFFORT = "low"

    val EFFORT_SHORT = mapOf("low" to "low", "medium" to "med", "high" to "high", "xhigh" to "xhigh", "max" to "max")
    val EFFORT_NAME = mapOf("low" to "low", "medium" to "medium", "high" to "high", "xhigh" to "extra high", "max" to "max")
    val EFFORT_HINT = mapOf(
        "low" to "light thinking, quick", "medium" to "balanced", "high" to "slower, more thorough",
        "xhigh" to "slow, digs deep", "max" to "slowest, spends the most",
    )

    /** What the Models API listed for the account on 2026-11-18, newest first. */
    val options: List<AnthropicModelOption> = listOf(
        "claude-fable-5-2" to "Claude Fable 5.2",
        "claude-opus-5-5" to "Claude Opus 5.5",
        "claude-sonnet-5-5" to "Claude Sonnet 5.5",
        "claude-haiku-5-5" to "Claude Haiku 5.5",
        "claude-fable-5-1" to "Claude Fable 5.1",
        "claude-opus-5" to "Claude Opus 5",
        "claude-sonnet-5" to "Claude Sonnet 5",
        "claude-fable-5" to "Claude Fable 5",
        "claude-opus-4-8" to "Claude Opus 4.8",
        "claude-opus-4-7" to "Claude Opus 4.7",
        "claude-sonnet-4-6" to "Claude Sonnet 4.6",
        "claude-opus-4-6" to "Claude Opus 4.6",
        "claude-opus-4-5-20251101" to "Claude Opus 4.5",
        "claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
        "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5",
    ).map { (id, name) -> AnthropicModelOption(id, name, blurbFor(id)) }

    /** The line under a model's name, by its tier. */
    fun blurbFor(id: String): String = when {
        "fable" in id -> "Most capable, and slowest"
        "opus" in id -> "Smart and steady"
        "sonnet" in id -> "Fast and capable"
        "haiku" in id -> "Fastest and cheapest"
        else -> ""
    }

    /** An older generation (4.x and before), folded under "Older" in the pickers. */
    fun isOld(id: String): Boolean =
        Regex("""claude-(?:[a-z]+-)?([0-9]+)""").find(id)?.groupValues?.get(1)?.toIntOrNull()?.let { it < 5 } ?: false

    fun find(id: String?, known: List<AnthropicModelOption> = options): AnthropicModelOption =
        known.firstOrNull { it.id == id } ?: options.firstOrNull { it.id == id }
            ?: id?.takeIf { it.startsWith("claude-") }?.let { AnthropicModelOption(it, prettyName(it), blurbFor(it)) }
            ?: options.first { it.id == DEFAULT_ID }

    /** A saved pick stays as it is (the account's list may have it); blank is the default. */
    fun resolveId(stored: String?): String = stored?.trim()?.takeIf { it.startsWith("claude-") } ?: DEFAULT_ID

    /** "claude-opus-4-5-20251101" → "Claude Opus 4.5", for an id the list never named. */
    fun prettyName(id: String): String = id.removePrefix("claude-").replace(Regex("""-\d{8}$"""), "").split('-').let { parts ->
        val name = parts.takeWhile { p -> p.none(Char::isDigit) }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val version = parts.dropWhile { p -> p.none(Char::isDigit) }.joinToString(".")
        listOf("Claude", name, version).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Sampling parameters were removed from the current models: `temperature` is a 400 on
     * Opus 5.5, Fable 5.1, Sonnet 5.5 and Haiku 5.5. Only Haiku 4.5 and older take it.
     */
    fun acceptsSamplingParams(modelId: String): Boolean =
        modelId.startsWith("claude-haiku-4") || modelId.startsWith("claude-3")

    /** `output_config.effort` works on every current model; Haiku 4.5 rejects it. */
    fun acceptsEffort(modelId: String): Boolean = !modelId.startsWith("claude-haiku-4") && !modelId.startsWith("claude-3")

    /** The depths [modelId] offers: all of them, or none on a model that takes no effort. */
    fun effortsFor(modelId: String): List<String> = if (acceptsEffort(modelId)) EFFORTS else emptyList()

    /** Adaptive thinking (and its summarized display) on every current model; Haiku 4.5 predates it. */
    fun acceptsAdaptiveThinking(modelId: String): Boolean = acceptsEffort(modelId)

    /**
     * The web search tool with dynamic filtering runs on Opus and Sonnet; Haiku and Fable
     * keep the basic one.
     */
    fun webSearchToolType(modelId: String): String =
        if (modelId.startsWith("claude-opus") || modelId.startsWith("claude-sonnet")) "web_search_20260209" else "web_search_20250305"
}
