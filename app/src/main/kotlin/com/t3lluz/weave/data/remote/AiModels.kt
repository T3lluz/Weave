package com.t3lluz.weave.data.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** A model as the AI pickers show it. [note] is the short line under its name. */
data class AiModel(val id: String, val name: String, val note: String? = null) {
    /** An older generation, folded under "Older". */
    val old: Boolean get() = AnthropicModels.isOld(id)
}

/**
 * Claude's models for the pickers: the account's own list from Anthropic's Models API, kept
 * for [STALE_MS], else [AnthropicModels.options].
 */
object AiModels {
    private const val PREFS = "ai_model_lists"
    private const val KEY = "anthropic"
    const val STALE_MS = 12 * 60 * 60 * 1000L
    private const val MODELS_URL = "https://api.anthropic.com/v1/models?limit=100"

    @Suppress("UNUSED_PARAMETER")
    fun defaultFor(provider: AiProvider): String = AnthropicModels.DEFAULT_ID

    private fun builtIn() = AnthropicModels.options.map { AiModel(it.id, it.displayName, it.blurb) }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The account's last read list, else the built-in one. */
    @Suppress("UNUSED_PARAMETER")
    fun known(context: Context, provider: AiProvider): List<AiModel> {
        val raw = prefs(context).getString(KEY, null) ?: return builtIn()
        return runCatching {
            val a = JSONObject(raw).getJSONArray("models")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AiModel(o.getString("id"), o.optString("name").ifBlank { o.getString("id") }, AnthropicModels.blurbFor(o.getString("id")).ifBlank { null })
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: builtIn()
    }

    fun lastFetched(context: Context): Long =
        prefs(context).getString(KEY, null)?.let { runCatching { JSONObject(it).optLong("at") }.getOrNull() } ?: 0L

    fun isStale(context: Context): Boolean = System.currentTimeMillis() - lastFetched(context) > STALE_MS

    /** The name to show for [id]. */
    fun label(context: Context?, id: String): String =
        (context?.let { known(it, AiProvider.ANTHROPIC) } ?: builtIn()).firstOrNull { it.id == id }?.name
            ?: AnthropicModels.find(id).displayName

    /**
     * Reads the account's models from the Models API and keeps them, newest first as the API
     * lists them. [secret] is the Claude sign-in's access token ([oauth]) or an API key.
     * Throws with a short reason when it can't.
     */
    suspend fun refresh(context: Context, httpClient: OkHttpClient, secret: String, oauth: Boolean): List<AiModel> =
        withContext(Dispatchers.IO) {
            require(secret.isNotBlank()) { "Sign in to Claude first" }
            val b = Request.Builder().url(MODELS_URL).get()
                .header("anthropic-version", AiApiClient.ANTHROPIC_VERSION)
            if (oauth) {
                b.header("Authorization", "Bearer $secret").header("anthropic-beta", ClaudeOAuth.API_BETA)
            } else {
                b.header("x-api-key", secret)
            }
            val models = httpClient.newCall(b.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) throw IllegalStateException("Claude turned the sign-in down")
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val a = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).mapNotNull { i ->
                    val m = a.optJSONObject(i) ?: return@mapNotNull null
                    val id = m.optString("id").trim()
                    if (!id.startsWith("claude-")) return@mapNotNull null
                    AiModel(id, m.optString("display_name").ifBlank { AnthropicModels.prettyName(id) }, AnthropicModels.blurbFor(id).ifBlank { null })
                }
            }
            if (models.isEmpty()) throw IllegalStateException("Claude listed no models")
            val a = JSONArray()
            models.forEach { m -> a.put(JSONObject().put("id", m.id).put("name", m.name)) }
            prefs(context).edit { putString(KEY, JSONObject().put("at", System.currentTimeMillis()).put("models", a).toString()) }
            models
        }
}
