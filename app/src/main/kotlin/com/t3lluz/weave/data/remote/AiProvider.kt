package com.t3lluz.weave.data.remote

/**
 * The AI that makes shortcuts from a few words. Only Claude for now, signed in with a Claude
 * plan or an API key. Kept as a list so the picker and Settings → AI read the way DailyDash's do.
 */
enum class AiProvider(val storageValue: String, val displayName: String) {
    ANTHROPIC("anthropic", "Claude");

    companion object {
        fun fromStorage(value: String?): AiProvider =
            entries.find { it.storageValue.equals(value, ignoreCase = true) } ?: ANTHROPIC
    }
}

/**
 * How the phone is let in to a provider, for the badge every AI picker shows next to it:
 * signed in to an account (a plan's usage) or a pasted API key (billed per token).
 */
enum class AiAccess(val label: String) {
    ACCOUNT("Account"),
    API_KEY("API key"),
    NONE("Not set up"),
}
