package com.t3lluz.weave.data.local

import android.content.Context
import androidx.core.content.edit
import com.t3lluz.weave.data.remote.AiProvider
import com.t3lluz.weave.data.remote.AnthropicModels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own settings: the dashboard whose bridge lists the laptops, and the Claude model
 * and key that make shortcuts from words. The remote's settings are in
 * [com.t3lluz.weave.data.link.LinkPrefs].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("weave_settings", Context.MODE_PRIVATE)

    /** Only Claude for now; kept as a flow so the pickers read the way DailyDash's do. */
    val aiProvider: StateFlow<AiProvider> = MutableStateFlow(AiProvider.ANTHROPIC)

    private val _anthropicApiKey = MutableStateFlow(prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "")
    val anthropicApiKey: StateFlow<String> = _anthropicApiKey

    private val _anthropicModelId = MutableStateFlow(
        AnthropicModels.resolveId(prefs.getString(KEY_ANTHROPIC_MODEL, null)),
    )
    val anthropicModelId: StateFlow<String> = _anthropicModelId

    /** How hard Claude thinks (`output_config.effort`): one of [AnthropicModels.EFFORTS]. */
    private val _anthropicEffort = MutableStateFlow(
        prefs.getString(KEY_ANTHROPIC_EFFORT, null)?.takeIf { it in AnthropicModels.EFFORTS } ?: AnthropicModels.DEFAULT_EFFORT,
    )
    val anthropicEffort: StateFlow<String> = _anthropicEffort

    fun setEffort(effort: String) {
        if (effort !in AnthropicModels.EFFORTS) return
        prefs.edit { putString(KEY_ANTHROPIC_EFFORT, effort) }
        _anthropicEffort.value = effort
    }

    fun effort(): String = _anthropicEffort.value

    /** The dashboard whose bridge (`/_api/link`) the laptops announce themselves to. */
    private val _dashboardServerUrl = MutableStateFlow(
        prefs.getString(KEY_DASHBOARD_SERVER_URL, DEFAULT_DASHBOARD_SERVER_URL) ?: DEFAULT_DASHBOARD_SERVER_URL,
    )
    val dashboardServerUrl: StateFlow<String> = _dashboardServerUrl

    fun setDashboardServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_DASHBOARD_SERVER_URL, trimmed) }
        _dashboardServerUrl.value = trimmed
    }

    @Suppress("UNUSED_PARAMETER")
    fun setAiProvider(provider: AiProvider) = Unit

    fun getAiProvider(): AiProvider = AiProvider.ANTHROPIC

    @Suppress("UNUSED_PARAMETER")
    fun modelFor(provider: AiProvider = getAiProvider()): String = _anthropicModelId.value

    /** Picks [modelId]; an unknown one goes back to the default. */
    @Suppress("UNUSED_PARAMETER")
    fun setModel(provider: AiProvider, modelId: String) {
        val resolved = AnthropicModels.resolveId(modelId)
        prefs.edit { putString(KEY_ANTHROPIC_MODEL, resolved) }
        _anthropicModelId.value = resolved
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveApiKeyForProvider(provider: AiProvider, key: String) {
        prefs.edit { putString(KEY_ANTHROPIC_API_KEY, key.trim()) }
        _anthropicApiKey.value = key.trim()
    }

    @Suppress("UNUSED_PARAMETER")
    fun getApiKeyForProvider(provider: AiProvider = getAiProvider()): String = _anthropicApiKey.value

    companion object {
        const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        const val KEY_ANTHROPIC_MODEL = "anthropic_model"
        const val KEY_ANTHROPIC_EFFORT = "anthropic_effort"
        const val KEY_DASHBOARD_SERVER_URL = "dashboard_server_url"
        const val DEFAULT_DASHBOARD_SERVER_URL = "https://t3lluz.com"
    }
}
