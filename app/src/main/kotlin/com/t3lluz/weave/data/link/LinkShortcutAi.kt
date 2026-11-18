package com.t3lluz.weave.data.link

import com.t3lluz.weave.data.local.SettingsRepository
import com.t3lluz.weave.data.remote.AiApiClient
import com.t3lluz.weave.data.remote.AiCredentialResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Workspace 3", "deafen me on Discord", "open youtube.com": a shortcut out of a few words. The AI
 * is shown the laptop's own bindings and apps, so it picks real ones before making up keys. It
 * answers with the shortcut in JSON ([parseShortcutAnswer] checks it): its steps, in order (bring
 * Discord to the front, wait, press Ctrl + Shift + D), an icon from Iconify in a fitting colour,
 * and for something that flips on and off, a second look. A follow-up changes what it made
 * ("make it workspace 4"). The icons it names are fetched here; one that doesn't exist is
 * looked up by its search words instead.
 */
@Singleton
class LinkShortcutAi @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settings: SettingsRepository,
    private val credentials: AiCredentialResolver,
    private val store: LinkShortcutStore,
) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether an AI is set up to ask. */
    fun ready(): Boolean = credentials.hasCredentials(settings.getAiProvider())

    /**
     * Asks for one shortcut. [current] is the one the sheet holds, which the ask may change;
     * [turns] are the asks before this one. [onProgress] hears what the AI is doing as it streams
     * ("Picking the keys"), from any thread, and never after this returns.
     */
    suspend fun make(
        ask: String,
        catalog: LinkCatalog?,
        laptop: String,
        current: LinkShortcut? = null,
        turns: List<ShortcutTurn> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): ShortcutAnswer {
        val provider = settings.getAiProvider()
        if (!credentials.hasCredentials(provider)) return ShortcutAnswer.Refused("Pick an AI first.")
        val timeout = 75_000L
        val live = AtomicBoolean(true)
        fun tell(line: String) { if (live.get()) onProgress(line) }
        val binds = catalog?.binds?.size ?: 0
        val apps = catalog?.apps?.size ?: 0
        tell(if (binds + apps > 0) "Reading $laptop's $binds bindings and $apps apps" else "Thinking")
        // the provider calls block in OkHttp and don't stop when cancelled, so the call runs on its
        // own and only the wait for it is timed (and given up when the sheet closes)
        val call = background.async {
            val auth = credentials.resolve(provider)
            val thinking = StringBuilder()
            val text = StringBuilder()
            AiApiClient.stream(
                httpClient = httpClient,
                auth = auth,
                params = AiApiClient.GenerateParams(
                    prompt = shortcutPrompt(ask, catalog, laptop, current, turns),
                    temperature = 0.1,
                    // room for a model that thinks inside the same budget (Gemini 2.5)
                    maxOutputTokens = 2400,
                    jsonMode = true,
                ),
                onEvent = { e ->
                    when (e) {
                        is AiApiClient.StreamEvent.Thinking -> {
                            thinking.append(e.text)
                            if (text.isEmpty()) ThinkingLine.of(thinking)?.let(::tell)
                        }
                        is AiApiClient.StreamEvent.Text -> {
                            text.append(e.text)
                            shortcutProgress(text)?.let(::tell)
                        }
                    }
                },
            )
        }
        // the provider's own words when it failed ("Claude rate limit reached"), if they are short
        var failure: String? = null
        val answer = try {
            withTimeoutOrNull(timeout) { call.await() }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            failure = e.message?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 140 }
            null
        } finally {
            live.set(false)
        }
        if (answer == null) {
            call.cancel()
            return ShortcutAnswer.Refused(failure ?: "The AI didn't answer. Try again, or make it by hand below.")
        }
        val parsed = parseShortcutAnswer(answer, catalog)
        if (parsed !is ShortcutAnswer.Made) return parsed
        // the pictures it named, made real: fetched once, or found by their search words
        val made = parsed.shortcut
        if (made.glyph == null && parsed.iconQuery.isBlank() && made.toggle == null) return parsed
        tell("Fetching its icons")
        val glyph = store.resolveGlyph(made.glyph, parsed.iconQuery)
        val toggle = made.toggle?.let { t -> t.copy(glyph = store.resolveGlyph(t.glyph, parsed.toggleQuery) ?: glyph) }
        return parsed.copy(shortcut = made.copy(glyph = glyph, toggle = toggle))
    }
}

/** What the AI is told: what a shortcut can be, what this laptop has, what was asked before, and the ask. */
internal fun shortcutPrompt(
    ask: String,
    catalog: LinkCatalog?,
    laptop: String,
    current: LinkShortcut? = null,
    turns: List<ShortcutTurn> = emptyList(),
): String = buildString {
    val hasApps = !catalog?.apps.isNullOrEmpty()
    appendLine("You make one shortcut button for a phone that remote-controls a Linux laptop called \"$laptop\" (Hyprland, Omarchy).")
    appendLine("A tap runs the button's steps in order. Answer with one JSON object:")
    appendLine("""{"label":NAME,"steps":[STEP,...],"icon":ICON,"toggle":{"label":NAME,"icon":ICON},"reply":REPLY}""")
    appendLine()
    appendLine("STEP is one of:")
    appendLine("""- {"kind":"keys","key":KEY,"mods":[MODS]}: presses one key combination on whatever window has focus.""")
    // a laptop that can't list its apps gets no app steps: the AI would have to guess an id
    if (hasApps) {
        appendLine("""- {"kind":"app","app":APP_ID}: opens one of the laptop's apps (ids below). When it is already open, this brings its window to the front and focuses it, for apps that keep one window (Discord, Vesktop, Spotify, Slack, Steam, Obsidian, browsers).""")
    }
    appendLine("""- {"kind":"site","url":"https://…"}: opens one web page in the laptop's browser.""")
    appendLine("""- {"kind":"text","text":WORDS}: types some words, on one line.""")
    appendLine("""- {"kind":"wait","ms":MS}: waits 100 to 5000 ms before the next step.""")
    appendLine("MODS are any of ctrl, alt, shift, super. KEY is a lowercase letter or digit, or one of: ${LinkKeys.SPECIAL.joinToString(", ")}.")
    appendLine("Use as few steps as do the job, at most ${LinkShortcut.STEPS_MAX}. Keys meant for one app (Discord's Ctrl+Shift+D to deafen) need that app focused first: an app step, then the keys (the phone waits for the app to come to the front by itself; add a wait only when the app is slow to start, about 2000 ms). Prefer one of the laptop's own bindings below when one does what is asked: use its key and mods exactly.")
    appendLine("Use the app's real default shortcuts (Discord and Vesktop: ctrl+shift+m mutes the mic, ctrl+shift+d deafens; Spotify: space plays or pauses).")
    appendLine()
    appendLine("NAME is what the button says: at most 14 characters, plain words, like \"Deafen\" or \"Workspace 3\".")
    appendLine("""ICON is {"id":ICON_ID,"query":WORDS,"color":"#RRGGBB"}: ICON_ID is an Iconify icon id from mdi, material-symbols, lucide, tabler or simple-icons (brand logos), like "mdi:headphones", "mdi:headphones-off", "mdi:microphone-off", "lucide:monitor", "simple-icons:youtube". WORDS are two or three search words for a fitting icon, used if the id doesn't exist. Pick an icon that shows what the button does (Discord's deafen is headphones; deafened is headphones struck out), not just the app's logo, unless the button only opens the app or page. color is the app's brand colour when the button is about one app (Discord #5865F2, Spotify #1DB954, YouTube #FF0000), else leave it out.""")
    appendLine("""toggle: only for a button that flips something on and off (mute and unmute, deafen and undeafen, play and pause, fullscreen, do not disturb). "icon" and "label" are its look while off (what a tap will do), toggle's are its look while on (deafened: "Undeafen" with headphones struck out, often in red #ED4245). Leave toggle out for everything else.""")
    appendLine("REPLY is one short sentence to the person, at most 110 characters: what the button does, naming the laptop's binding when it uses one.")
    appendLine("""If the ask is not something a button can do, answer {"error":"<one short sentence why>","suggestions":[up to 3 short asks a button can do that come close]}.""")
    appendLine("Answer with the JSON object only.")
    val binds = catalog?.binds.orEmpty()
    if (binds.isNotEmpty()) {
        appendLine()
        appendLine("The laptop's bindings (what it does: key, mods):")
        binds.take(260).forEach { b -> appendLine("${b.label}: ${b.key}${if (b.mods.isEmpty()) "" else ", " + b.mods.joinToString("+")}") }
    }
    val apps = catalog?.apps.orEmpty()
    if (apps.isNotEmpty()) {
        appendLine()
        appendLine("The laptop's apps (name: id):")
        apps.take(200).forEach { a -> appendLine("${a.name}: ${a.id}") }
    }
    if (turns.isNotEmpty()) {
        appendLine()
        appendLine("Asked before, and what you said:")
        turns.takeLast(4).forEach { t -> appendLine("- \"${t.ask.trim().take(200)}\" → ${t.reply.take(160)}") }
    }
    if (current != null && current.complete) {
        appendLine()
        appendLine("The button so far: ${buttonJson(current)}")
        appendLine("The ask may change it (\"make it workspace 4\", \"call it Music\", \"focus Discord first\"): keep what the ask doesn't change, and answer with the whole button.")
    }
    appendLine()
    append("The ask: ").append(ask.trim().take(300))
}

/** The button the sheet holds, in the answer's own shape. */
private fun buttonJson(s: LinkShortcut): String {
    fun icon(g: LinkGlyph?) = g?.let { org.json.JSONObject().put("id", it.id).apply { it.color?.let { c -> put("color", "#%06X".format(c and 0xFFFFFF)) } } }
    val o = org.json.JSONObject().put("label", s.label)
        .put("steps", org.json.JSONArray(s.steps.map { st -> st.toJson().apply { remove("cap") } }))
    icon(s.glyph)?.let { o.put("icon", it) }
    s.toggle?.let { t -> o.put("toggle", org.json.JSONObject().put("label", t.label).apply { icon(t.glyph)?.let { put("icon", it) } }) }
    return o.toString()
}
