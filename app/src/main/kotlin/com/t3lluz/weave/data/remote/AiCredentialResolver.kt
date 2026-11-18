package com.t3lluz.weave.data.remote

import com.t3lluz.weave.BuildConfig
import com.t3lluz.weave.data.local.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a call needs to reach Claude: the secret (an API key or a Claude account's
 * access token), how it was let in, and the model. Built by [AiCredentialResolver.resolve];
 * handed to `AiApiClient.generate(httpClient, auth, params)`.
 */
data class ResolvedAiAuth(
    val secret: String,
    val anthropicOAuth: Boolean = false,
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val access: AiAccess = AiAccess.NONE,
    val model: String = "",
    /** The depth picked for Claude; blank leaves the call's own. */
    val effort: String = "",
) {
    val isBlank: Boolean get() = secret.isBlank()
}

/** The Claude account session, else a saved key, else one baked into the build. */
@Singleton
class AiCredentialResolver @Inject constructor(
    private val settings: SettingsRepository,
    private val claudeAuth: ClaudeAuthClient,
) {
    fun hasCredentials(provider: AiProvider = settings.getAiProvider()): Boolean = access(provider) != AiAccess.NONE

    /** How the phone is let in right now. Claude's account wins over its key, as it does when calling. */
    @Suppress("UNUSED_PARAMETER")
    fun access(provider: AiProvider = settings.getAiProvider()): AiAccess = when {
        claudeAuth.hasSession() -> AiAccess.ACCOUNT
        storedOrBuildKey().isNotBlank() -> AiAccess.API_KEY
        else -> AiAccess.NONE
    }

    suspend fun resolve(provider: AiProvider = settings.getAiProvider()): ResolvedAiAuth {
        val base = ResolvedAiAuth(secret = "", provider = provider, model = settings.modelFor(provider), effort = settings.effort())
        val access = claudeAuth.validAccessToken()
        if (!access.isNullOrBlank()) {
            return base.copy(secret = access, anthropicOAuth = true, access = AiAccess.ACCOUNT)
        }
        val key = storedOrBuildKey()
        return base.copy(secret = key, access = if (key.isBlank()) AiAccess.NONE else AiAccess.API_KEY)
    }

    private fun storedOrBuildKey(): String =
        settings.getApiKeyForProvider(AiProvider.ANTHROPIC).trim().ifBlank { BuildConfig.ANTHROPIC_API_KEY.trim() }
}
