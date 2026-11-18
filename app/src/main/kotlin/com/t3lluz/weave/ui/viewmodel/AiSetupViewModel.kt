package com.t3lluz.weave.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t3lluz.weave.data.local.SettingsRepository
import com.t3lluz.weave.data.remote.AiAccess
import com.t3lluz.weave.data.remote.AiApiClient
import com.t3lluz.weave.data.remote.AiCredentialResolver
import com.t3lluz.weave.data.remote.AiModel
import com.t3lluz.weave.data.remote.AiModels
import com.t3lluz.weave.data.remote.AiProvider
import com.t3lluz.weave.data.remote.AnthropicModels
import com.t3lluz.weave.data.remote.ClaudeAuthClient
import com.t3lluz.weave.data.remote.ClaudeAuthOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject

/** One provider as every AI picker shows it. */
data class AiProviderStatus(
    val provider: AiProvider,
    val access: AiAccess,
    /** "Signed in as you@x · Max", "Key saved", "Through your dashboard", "Add an API key". */
    val detail: String,
    val model: String,
    val modelLabel: String,
) {
    val ready: Boolean get() = access != AiAccess.NONE
}

/** Claude's sign-in, as Settings walks it: open the page, paste the code back, done. */
data class ClaudeLogin(
    val connected: Boolean = false,
    val email: String? = null,
    val plan: String? = null,
    val awaitingCode: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

sealed interface AiTest {
    data object Running : AiTest
    data class Passed(val provider: AiProvider, val seconds: Double) : AiTest
    data class Failed(val message: String) : AiTest
}

data class AiUiState(
    val selected: AiProvider = AiProvider.ANTHROPIC,
    val providers: List<AiProviderStatus> = emptyList(),
    val models: Map<AiProvider, List<AiModel>> = emptyMap(),
    val loadingModels: Set<AiProvider> = emptySet(),
    val modelErrors: Map<AiProvider, String> = emptyMap(),
    /** Saved keys, for the key fields. */
    val keys: Map<AiProvider, String> = emptyMap(),
    val claude: ClaudeLogin = ClaudeLogin(),
    /** Claude's depth, one of [AnthropicModels.EFFORTS]. */
    val effort: String = AnthropicModels.DEFAULT_EFFORT,
    val test: AiTest? = null,
) {
    val current: AiProviderStatus? get() = providers.firstOrNull { it.provider == selected }
    fun status(p: AiProvider): AiProviderStatus? = providers.firstOrNull { it.provider == p }
}

/**
 * The phone's AI, the same way everywhere it is picked (Settings → AI, the shortcut sheet):
 * Claude, how it is let in (your plan or a key), its model, the sign-in, and a test.
 */
@HiltViewModel
class AiSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val credentials: AiCredentialResolver,
    private val claudeAuth: ClaudeAuthClient,
    private val httpClient: OkHttpClient,
) : ViewModel() {

    private val extra = MutableStateFlow(Extra())

    private data class Extra(
        val models: Map<AiProvider, List<AiModel>> = emptyMap(),
        val loading: Set<AiProvider> = emptySet(),
        val errors: Map<AiProvider, String> = emptyMap(),
        val claudeBusy: Boolean = false,
        val claudeMessage: String? = null,
        val test: AiTest? = null,
        /** Bumped when something the flows don't carry changes (a key cleared, a sign-out). */
        val tick: Int = 0,
    )

    private val inputs = combine(
        settings.aiProvider,
        settings.anthropicModelId,
        settings.anthropicApiKey,
        settings.anthropicEffort,
    ) { provider, _, key, effort -> Triple(provider, mapOf(AiProvider.ANTHROPIC to key), effort) }

    val state: StateFlow<AiUiState> = combine(
        inputs,
        combine(claudeAuth.isConnected, claudeAuth.accountEmail, claudeAuth.subscriptionLabel, claudeAuth.isAwaitingCode) { c, e, p, w ->
            ClaudeLogin(connected = c, email = e, plan = p, awaitingCode = w)
        },
        extra,
    ) { (provider, keys, effort), claude, x ->
        AiUiState(
            selected = provider,
            providers = AiProvider.entries.map { statusOf(it, claude) },
            models = AiProvider.entries.associateWith { x.models[it] ?: AiModels.known(context, it) },
            loadingModels = x.loading,
            modelErrors = x.errors,
            keys = keys,
            claude = claude.copy(busy = x.claudeBusy, message = x.claudeMessage),
            test = x.test,
            effort = effort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiUiState(selected = settings.getAiProvider()))

    private fun statusOf(p: AiProvider, claude: ClaudeLogin): AiProviderStatus {
        val access = credentials.access(p)
        val model = settings.modelFor(p)
        val detail = when {
            access == AiAccess.ACCOUNT -> listOfNotNull(claude.email ?: "Signed in", claude.plan?.let { "$it plan" }).joinToString(" · ")
            access == AiAccess.API_KEY -> "API key · billed per token"
            else -> "Sign in with your Claude plan, or add a key"
        }
        return AiProviderStatus(p, access, detail, model, AiModels.label(context, model))
    }

    private fun bump() = extra.value.let { extra.value = it.copy(tick = it.tick + 1) }

    fun select(provider: AiProvider) {
        settings.setAiProvider(provider)
        extra.value = extra.value.copy(test = null)
    }

    fun setModel(provider: AiProvider, id: String) {
        settings.setModel(provider, id)
        extra.value = extra.value.copy(test = null)
    }

    fun saveKey(provider: AiProvider, key: String) {
        settings.saveApiKeyForProvider(provider, key)
        extra.value = extra.value.copy(test = null)
    }

    fun setEffort(effort: String) {
        settings.setEffort(effort)
        extra.value = extra.value.copy(test = null)
    }

    /** Reads the account's models from Anthropic when the kept list is stale (or [force]). */
    fun refreshModels(provider: AiProvider, force: Boolean = false) {
        if (provider in extra.value.loading) return
        if (!force && !AiModels.isStale(context)) return
        if (credentials.access(provider) == AiAccess.NONE) return
        extra.value = extra.value.copy(loading = extra.value.loading + provider)
        viewModelScope.launch {
            val result = runCatching {
                val auth = credentials.resolve(provider)
                AiModels.refresh(context, httpClient, auth.secret, auth.anthropicOAuth)
            }
            val x = extra.value
            extra.value = x.copy(
                loading = x.loading - provider,
                models = result.getOrNull()?.let { x.models + (provider to it) } ?: x.models,
                errors = result.exceptionOrNull()?.let { x.errors + (provider to (it.message ?: "Couldn't load the list")) } ?: (x.errors - provider),
                tick = x.tick + 1,
            )
        }
    }

    private var testJob: Job? = null

    /** One tiny call to the provider in use, to show it answers and how fast. */
    fun test() {
        testJob?.cancel()
        extra.value = extra.value.copy(test = AiTest.Running)
        testJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                val auth = credentials.resolve()
                require(!auth.isBlank) { "Set it up first" }
                withTimeoutOrNull(60_000L) {
                    AiApiClient.generate(
                        httpClient,
                        auth,
                        AiApiClient.GenerateParams(prompt = "Reply with exactly: OK", maxOutputTokens = 64, temperature = 0.0),
                    )
                } ?: throw IllegalStateException("No answer within a minute")
            }
            extra.value = extra.value.copy(
                test = outcome.fold(
                    onSuccess = { AiTest.Passed(settings.getAiProvider(), (System.currentTimeMillis() - started) / 1000.0) },
                    onFailure = { AiTest.Failed(it.message?.take(200) ?: "It didn't answer") },
                ),
            )
        }
    }

    fun dismissTest() {
        extra.value = extra.value.copy(test = null)
    }

    fun startClaudeLogin() {
        extra.value = extra.value.copy(claudeMessage = null)
        if (!claudeAuth.startLogin()) extra.value = extra.value.copy(claudeMessage = "Couldn't open Claude's sign-in page")
    }

    fun cancelClaudeLogin() {
        claudeAuth.cancelLogin()
        extra.value = extra.value.copy(claudeMessage = null)
    }

    fun finishClaudeLogin(code: String) {
        if (extra.value.claudeBusy) return
        viewModelScope.launch {
            extra.value = extra.value.copy(claudeBusy = true, claudeMessage = null)
            val message = when (val outcome = claudeAuth.finishLogin(code)) {
                is ClaudeAuthOutcome.Success -> {
                    settings.setAiProvider(AiProvider.ANTHROPIC)
                    if (outcome.email.isNullOrBlank()) "Signed in" else "Signed in as ${outcome.email}"
                }
                is ClaudeAuthOutcome.Failed -> outcome.message
            }
            extra.value = extra.value.copy(claudeBusy = false, claudeMessage = message)
        }
    }

    fun signOutClaude() {
        claudeAuth.disconnect()
        extra.value = extra.value.copy(claudeMessage = "Signed out of Claude")
        bump()
    }
}
