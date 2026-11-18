package com.t3lluz.weave.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude's HTTP calls, for shortcuts made from a few words: one answer ([generate]) or the
 * same streamed ([stream]) with its thinking summarized for the live line. Signed in with a
 * Claude plan (OAuth) or an API key.
 */
object AiApiClient {
    private const val TAG = "AiApiClient"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"

    data class GenerateParams(
        val prompt: String,
        val base64Jpeg: String? = null,
        val temperature: Double = 0.2,
        val maxOutputTokens: Int = 1024,
        val jsonMode: Boolean = false,
        /** The Claude model to ask; blank means the default. */
        val model: String? = null,
        /** True when [apiKey] is a Claude subscription OAuth access token. */
        val anthropicOAuth: Boolean = false,
        /** Let Claude search the web (its web search tool) before it answers. */
        val webSearch: Boolean = false,
        /** Claude's `output_config.effort`; these calls are short, so `low` unless a caller needs more. */
        val effort: String = "low",
    )

    /** One call to the provider [auth] resolved, with its model, login and dashboard filled in. */
    suspend fun generate(httpClient: OkHttpClient, auth: ResolvedAiAuth, params: GenerateParams): String =
        generate(httpClient, auth.provider, auth.secret, params.forAuth(auth))

    fun GenerateParams.forAuth(auth: ResolvedAiAuth): GenerateParams = copy(
        model = model?.takeIf { it.isNotBlank() } ?: auth.model,
        anthropicOAuth = auth.anthropicOAuth,
        effort = auth.effort.ifBlank { effort },
    )

    suspend fun generate(
        httpClient: OkHttpClient,
        provider: AiProvider,
        apiKey: String,
        params: GenerateParams,
    ): String {
        require(apiKey.isNotBlank()) { "API key is blank" }
        val client = longReads(httpClient)
        return when (provider) {
            AiProvider.ANTHROPIC -> generateAnthropic(client, apiKey, params)
        }
    }

    /**
     * The shared client waits 30 s between bytes; a long answer that arrives in one piece
     * (a long shortcut answer) can take longer than that before its first byte. Debug logging at
     * BODY level would also hold a stream back until it ends, so it is dropped here.
     */
    internal fun longReads(httpClient: OkHttpClient): OkHttpClient {
        cachedLong?.let { (base, long) -> if (base === httpClient) return long }
        val long = httpClient.newBuilder()
            .readTimeout(LONG_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .apply { interceptors().removeAll { it is HttpLoggingInterceptor } }
            .build()
        cachedLong = httpClient to long
        return long
    }

    @Volatile
    private var cachedLong: Pair<OkHttpClient, OkHttpClient>? = null

    // ── Anthropic (Claude) ──────────────────────────────────────────────────

    /**
     * One user turn's content: a bare string, or a block array when an image rides
     * along. The image goes *before* the text — Claude reads the picture first and
     * then the instruction about it.
     */
    fun anthropicUserContent(text: String, base64Jpeg: String?): Any {
        if (base64Jpeg == null) return text
        return JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64Jpeg),
                    ),
            )
            .put(JSONObject().put("type", "text").put("text", text))
    }

    /**
     * The single place that knows Claude's request shape, shared by the one-shot
     * path here and by [AiChatClient]'s streaming path.
     *
     * Three parameters that look harmless are hard 400s on Opus 5 / Sonnet 5, so
     * they are gated rather than always sent:
     *  - `temperature` / `top_p` / `top_k` were removed (see [AnthropicModels.acceptsSamplingParams]).
     *  - `budget_tokens` was removed; thinking is adaptive and on by default, so
     *    `thinking` is simply never sent and depth is steered by `output_config.effort`.
     *  - an assistant prefill as the final turn is rejected; callers must not add one.
     *
     * [cacheSystemPrefix] puts a cache breakpoint on the *first* system block, so a
     * large static prompt is billed at cache-read rates while volatile blocks after
     * it (a live server snapshot) stay outside the cached prefix.
     */
    fun buildAnthropicBody(
        model: String,
        systemBlocks: List<String>,
        messages: JSONArray,
        maxTokens: Int,
        temperature: Double? = null,
        effort: String? = null,
        stream: Boolean = false,
        cacheSystemPrefix: Boolean = false,
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", messages)

        val blocks = systemBlocks.filter { it.isNotBlank() }
        if (blocks.isNotEmpty()) {
            val systemArray = JSONArray()
            blocks.forEachIndexed { index, text ->
                val block = JSONObject().put("type", "text").put("text", text)
                // Breakpoint on the first block only: everything after it is volatile.
                if (cacheSystemPrefix && index == 0) {
                    block.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
                systemArray.put(block)
            }
            body.put("system", systemArray)
        }

        if (temperature != null && AnthropicModels.acceptsSamplingParams(model)) {
            body.put("temperature", temperature)
        }
        if (effort != null && AnthropicModels.acceptsEffort(model)) {
            body.put("output_config", JSONObject().put("effort", effort))
        }
        if (stream) body.put("stream", true)
        return body
    }

    private const val MAX_PAUSE_CONTINUATIONS = 2

    /**
     * Claude's server-side web search: the dynamic-filtering version on Opus and Sonnet, the
     * basic one on Haiku and Fable ([AnthropicModels.webSearchToolType]).
     */
    fun anthropicWebSearchTool(model: String): JSONObject = JSONObject()
        .put("type", AnthropicModels.webSearchToolType(model))
        .put("name", "web_search")
        .put("max_uses", 4)

    fun anthropicHeaders(apiKey: String, oauth: Boolean = false): Map<String, String> =
        if (oauth) {
            ClaudeOAuth.authorizationHeaders(apiKey)
        } else {
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            )
        }

    private suspend fun generateAnthropic(
        httpClient: OkHttpClient,
        apiKey: String,
        params: GenerateParams,
    ): String {
        val model = AnthropicModels.resolveId(params.model)

        // Claude has no `response_format: json_object`. Structured outputs exist, but
        // the callers here already run a lenient parser over the reply (the Gemini
        // unstructured retry relies on it), so an explicit instruction is both
        // sufficient and one less request shape to get wrong.
        val systemBlocks = buildList {
            if (params.jsonMode) {
                add("Reply with a single raw JSON object and nothing else. No prose, no markdown fences.")
            }
        }.let { blocks ->
            if (params.anthropicOAuth) ClaudeOAuth.prependRequiredSystem(blocks) else blocks
        }

        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", anthropicUserContent(params.prompt, params.base64Jpeg)),
        )

        var lastError = ""
        var lastCode = 0
        var continuations = 0

        var attempt = 0
        while (attempt <= 1) {
            if (attempt > 0) delay(2000)

            val body = buildAnthropicBody(
                model = model,
                systemBlocks = systemBlocks,
                messages = messages,
                maxTokens = params.maxOutputTokens,
                temperature = params.temperature,
                // Most callers are short extractions, not reasoning problems — don't pay for depth.
                effort = params.effort,
            )
            if (params.webSearch) body.put("tools", JSONArray().put(anthropicWebSearchTool(model)))

            Log.d(TAG, "→ Claude $model | json=${params.jsonMode} | attempt=$attempt")

            try {
                val (code, responseBody) = doPost(
                    httpClient,
                    ANTHROPIC_URL,
                    body.toString(),
                    extraHeaders = anthropicHeaders(apiKey, oauth = params.anthropicOAuth),
                )
                Log.d(TAG, "← Claude $model | $code | ${responseBody.take(200)}")

                // A long web search can pause the turn: send it back as it stands and the
                // server picks up where it stopped (no "continue" message).
                if (code in 200..299 && continuations < MAX_PAUSE_CONTINUATIONS &&
                    runCatching { JSONObject(responseBody).optString("stop_reason") }.getOrNull() == "pause_turn"
                ) {
                    val content = JSONObject(responseBody).optJSONArray("content") ?: JSONArray()
                    val last = messages.optJSONObject(messages.length() - 1)
                    if (last?.optString("role") == "assistant") {
                        val sofar = last.optJSONArray("content") ?: JSONArray()
                        for (k in 0 until content.length()) sofar.put(content.get(k))
                        last.put("content", sofar)
                    } else {
                        messages.put(JSONObject().put("role", "assistant").put("content", content))
                    }
                    continuations++
                    continue
                }
                if (code in 200..299) {
                    val text = extractAnthropicText(responseBody)
                    if (text.isNotBlank()) return text
                }

                lastCode = code
                lastError = responseBody

                if (isApiKeyError(code, responseBody)) {
                    throw Exception(
                        if (params.anthropicOAuth) {
                            "Claude's sign-in expired or was refused. Sign in again in Settings → AI."
                        } else {
                            "Claude API key is invalid or unauthorized. Check Settings → AI."
                        },
                    )
                }
                if (code == 429 || isRateLimitError(responseBody)) {
                    if (attempt == 0) {
                        attempt++
                        continue
                    }
                    break
                }
                // 400 here means a malformed request, not a transient fault — retrying
                // an identical body would only burn another round trip.
                if (code == 400 || code == 404) break
            } catch (e: Exception) {
                if (e.message?.contains("API key") == true) throw e
                Log.e(TAG, "Claude exception ($model): ${e.message}")
                lastError = e.message ?: "Unknown error"
            }
            attempt++
        }

        if (isRateLimitError(lastError) || lastCode == 429) {
            throw Exception("Claude rate limit reached — wait a moment and try again.")
        }
        val snippet = anthropicErrorMessage(lastError).take(200).trim()
        throw Exception(
            "AI request failed ($lastCode): $snippet".ifBlank {
                "AI request failed. Check your API key and internet connection."
            },
        )
    }

    /** Joins every `text` block in a Claude reply; thinking blocks are skipped. */
    fun extractAnthropicText(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            if (json.optString("stop_reason") == "max_tokens") {
                throw Exception("AI response was truncated. Try a shorter description.")
            }
            val content = json.optJSONArray("content") ?: return ""
            buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") != "text") continue
                    val text = block.optString("text", "")
                    if (text.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(text)
                    }
                }
            }.trim()
        } catch (e: Exception) {
            if (e.message?.contains("truncated") == true) throw e
            Log.e(TAG, "Failed to parse Claude response: ${e.message}")
            ""
        }
    }

    /** Unwraps `{"error": {"message": "…"}}` so the UI shows the reason, not the envelope. */
    fun anthropicErrorMessage(responseBody: String): String = try {
        JSONObject(responseBody).optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { responseBody }
    } catch (_: Exception) {
        responseBody
    }

    // ── Streaming (the shortcut sheet's live line) ───────────────────────────────────

    /** A piece of a streamed answer: the model's (summarized) thinking, or its words. */
    sealed interface StreamEvent {
        data class Thinking(val text: String) : StreamEvent
        data class Text(val text: String) : StreamEvent
    }

    private class StreamRejected(val code: Int) : Exception("HTTP $code")

    /**
     * [generate], streamed: [onEvent] sees the thinking (summarized) and the answer as they
     * arrive over SSE, and the whole answer comes back. Web searches make one [generate] call
     * and hand the answer over in one piece. A stream that fails before its first word is
     * retried as a plain [generate], which has the retries and the friendly errors.
     */
    suspend fun stream(
        httpClient: OkHttpClient,
        auth: ResolvedAiAuth,
        params: GenerateParams,
        onEvent: (StreamEvent) -> Unit,
    ): String {
        val p = params.forAuth(auth)
        val provider = auth.provider
        require(auth.secret.isNotBlank()) { "API key is blank" }
        if (p.webSearch) {
            return generate(httpClient, provider, auth.secret, p).also { onEvent(StreamEvent.Text(it)) }
        }
        val client = longReads(httpClient)
        val out = StringBuilder()
        var stop = ""
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val request = anthropicStreamRequest(auth.secret, p)
                val call = client.newCall(request)
                val handle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null) throw StreamRejected(response.code)
                        val source = body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            if (payload.isEmpty()) continue
                            val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                            event.optJSONObject("error")?.let { err ->
                                throw Exception(err.optString("message").ifBlank { "The AI stopped unexpectedly." })
                            }
                            when (event.optString("type")) {
                                "content_block_delta" -> {
                                    val d = event.optJSONObject("delta") ?: continue
                                    when (d.optString("type")) {
                                        "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let { out.append(it); onEvent(StreamEvent.Text(it)) }
                                        "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Thinking(it)) }
                                    }
                                }
                                "message_delta" -> event.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let { stop = it }
                            }
                        }
                    }
                } finally {
                    handle?.dispose()
                }
            }
        }
        result.exceptionOrNull()?.let { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Nothing arrived: one plain call, with its retries and its own wording of the error.
            if (out.isEmpty()) return generate(httpClient, provider, auth.secret, p).also { onEvent(StreamEvent.Text(it)) }
            throw e
        }
        if (stop == "max_tokens" || stop == "length") throw Exception("AI response was truncated. Try a shorter description.")
        if (stop == "refusal") throw Exception("The AI declined that one. Try saying it another way.")
        if (out.isBlank()) return generate(httpClient, provider, auth.secret, p).also { onEvent(StreamEvent.Text(it)) }
        return out.toString().trim()
    }

    private fun sseRequest(url: String, body: JSONObject, headers: Map<String, String>): Request {
        val b = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private fun anthropicStreamRequest(apiKey: String, p: GenerateParams): Request {
        val model = AnthropicModels.resolveId(p.model)
        val system = buildList {
            if (p.jsonMode) add("Reply with a single raw JSON object and nothing else. No prose, no markdown fences.")
        }.let { if (p.anthropicOAuth) ClaudeOAuth.prependRequiredSystem(it) else it }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", anthropicUserContent(p.prompt, p.base64Jpeg)))
        val body = buildAnthropicBody(
            model = model,
            systemBlocks = system,
            messages = messages,
            // Thinking shares the budget; a stream can't time out, so give it room.
            maxTokens = p.maxOutputTokens * 2,
            temperature = p.temperature,
            effort = p.effort,
            stream = true,
        )
        // The summary is what the studio's live line shows while Claude works.
        if (AnthropicModels.acceptsAdaptiveThinking(model)) {
            body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
        }
        return sseRequest(ANTHROPIC_URL, body, anthropicHeaders(apiKey, oauth = p.anthropicOAuth))
    }

    // ── Shared HTTP / errors ────────────────────────────────────────────────

    private suspend fun doPost(
        httpClient: OkHttpClient,
        url: String,
        jsonBody: String,
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        for ((name, value) in extraHeaders) {
            builder.header(name, value)
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            Pair(response.code, response.body?.string() ?: "")
        }
    }

    fun isApiKeyError(code: Int, body: String): Boolean {
        if (code == 401 || code == 403) return true
        val lower = body.lowercase()
        return lower.contains("api_key_invalid") ||
            lower.contains("api key invalid") ||
            lower.contains("api key not valid") ||
            lower.contains("api key expired") ||
            lower.contains("invalid_api_key") ||
            lower.contains("incorrect api key") ||
            lower.contains("invalid x-api-key") ||
            lower.contains("authentication_error")
    }

    fun isRateLimitError(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("resource_exhausted") ||
            lower.contains("quota") ||
            lower.contains("rate limit") ||
            lower.contains("too many requests") ||
            lower.contains("rate_limit_exceeded") ||
            lower.contains("rate_limit_error") ||
            lower.contains("overloaded_error")
    }

    fun modelLabel(model: String? = null): String = AnthropicModels.find(model).displayName

    /** Where to get a key, for the key field. */
    fun keyUrl(@Suppress("UNUSED_PARAMETER") provider: AiProvider): String? = "https://console.anthropic.com/settings/keys"

    fun keyPlaceholder(@Suppress("UNUSED_PARAMETER") provider: AiProvider): String = "sk-ant-…"

    /** Soft format check for the settings field (warning only). */
    fun looksLikeValidKey(@Suppress("UNUSED_PARAMETER") provider: AiProvider, key: String): Boolean =
        key.isBlank() || key.trim().startsWith("sk-ant-")

    private const val LONG_READ_TIMEOUT_S = 180L
}
