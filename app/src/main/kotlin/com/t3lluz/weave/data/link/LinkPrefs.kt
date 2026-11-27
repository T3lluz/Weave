package com.t3lluz.weave.data.link

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** How the remote behaves under the finger, and which laptop it reaches. */
data class LinkConfig(
    /** Where the laptop listens: one address, or several separated by commas, tried in order. Blank until one is picked. */
    val host: String = "",
    val port: Int = LinkProtocol.PORT,
    /** What the laptop calls itself, for the screen. */
    val name: String = "",
    /** The dashboard's ticket for this laptop: shown on connecting, it lets this phone in without a prompt there. */
    val ticket: String = "",
    /** `low`, `mid` or `high`: the laptop's presets for size, sharpness and frame rate. */
    val quality: String = "mid",
    /** Pointer travel per finger travel, before acceleration. */
    val speed: Float = 1.6f,
    /** Two fingers move the page with them, as a phone scrolls. */
    val naturalScroll: Boolean = true,
    /** A strip along one edge of the trackpad that scrolls under one finger, as a mouse's wheel. */
    val scrollStrip: Boolean = true,
    /** The strip is on the pad's left edge; otherwise its right. */
    val scrollStripLeft: Boolean = false,
    /** Notches of the wheel per finger travel on the strip, 1 being two fingers' rate. */
    val scrollStripSpeed: Float = 1f,
    /** A flick along the strip keeps the page rolling after the finger lifts. */
    val scrollStripRoll: Boolean = true,
    /** The strip ticks under the finger at each notch, as a wheel with detents. */
    val scrollStripTicks: Boolean = true,
    /** Raise the keyboard when a click on the laptop lands in a text field. */
    val autoKeyboard: Boolean = true,
    /** Bring the laptop's sound up to a phone's level: a mix set low for headphones is a whisper from a speaker. */
    val levelSound: Boolean = true,
    /** The preview starts zoomed so the laptop's screen fills its height, following the pointer. */
    val fillPreview: Boolean = true,
    /** The laptop's screen is shown. Off, the remote is a trackpad and keyboard, and the laptop sends no picture. */
    val showPicture: Boolean = true,
    /** The shortcuts over the trackpad are shown. Off, the trackpad has their room. */
    val showShortcuts: Boolean = true,
    /** The pointer stops at the edge between two monitors, and a little more push takes it across, with a peek of the next ([MonitorEdge]). */
    val edgeHold: Boolean = true,
    /** On a laptop with one screen, its left and right sides lead to the next workspace the same way, picture shown or not. */
    val edgeSpaces: Boolean = true,
    /** The phone draws the pointer itself, where the finger puts it, and the laptop's is left out of the picture. */
    val localPointer: Boolean = true,
    /** The phone's keyboard works as in any app: suggestions, autocorrect, swiping. Plain keys in a terminal. */
    val nativeKeyboard: Boolean = true,
    /** The drawn pointer's size, 1 being the laptop's own. */
    val pointerSize: Float = 1f,
    /** The picked laptop's network card and its network's broadcast address, to wake it from sleep. */
    val wakeMac: String = "",
    val wakeBroadcast: String = "",
    /** What is copied on either side is there to paste on the other. */
    val clipSync: Boolean = false,
    /** What the picked computer's token is made from ([LinkPrefs.tokenFor]): given once, when it is first picked. */
    val key: String = "",
    /**
     * The dashboard's own id for the picked computer, when it was picked from the dashboard's list.
     * Only an entry with this id may change where the phone looks for it; a name or an address
     * in the dashboard's list never does.
     */
    val board: String = "",
    /** The picked computer's own id, from its welcome: how a code scanned later is known to be for it. */
    val ident: String = "",
)

/**
 * The remote's settings, apart from [com.t3lluz.weave.data.local.SettingsRepository] because
 * nothing else reads them. The token is made here once; the laptop asks its owner the first
 * time it sees it and remembers the answer.
 */
@Singleton
class LinkPrefs @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<LinkConfig> = _config

    /**
     * This phone's own secret. It never leaves the phone: each computer gets a token made from it
     * ([tokenFor]). It is a new one, under a new name: builds before this sent the old value
     * itself to every computer (and to anything a link pointed at), so the old one is thrown away.
     */
    private val secret: String by lazy {
        prefs.getString(KEY_SECRET, null) ?: randomHex(32).also { fresh ->
            prefs.edit {
                putString(KEY_SECRET, fresh)
                remove(KEY_OLD_TOKEN)
            }
        }
    }

    /**
     * What this phone shows a computer to be known by, from that computer's [key]: a random name
     * the phone gave it when it was first picked, kept for as long as the phone knows it. Each
     * computer gets its own token, so one handed to the wrong machine (a link that pointed
     * somewhere else, a stranger on the Wi-Fi) opens no other. And it stays the same when the
     * computer's addresses change, so the computer doesn't ask twice.
     */
    fun tokenFor(key: String): String = deriveToken(secret, key)

    /** A key for a computer this phone hasn't picked before. */
    fun newKey(): String = randomHex(16)

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private val _known = MutableStateFlow(readKnown())
    /** The computers this phone has been let into, newest first: the list is made of these. */
    val known: StateFlow<List<KnownComputer>> = _known

    /**
     * The computer just connected to goes to the top of [known]. It takes the place of its own
     * older entry (the same key: its addresses may have changed since), of any other entry at
     * exactly the same addresses, and of any with the same id of its own that shares an address
     * with it (the same computer, paired again: one machine is one entry). An id alone takes no
     * entry's place: a computer can claim any.
     */
    fun remember(cfg: LinkConfig) {
        if (cfg.host.isBlank() || cfg.key.isBlank()) return
        val entry = KnownComputer(cfg.name.ifBlank { cfg.host.substringBefore(',') }, cfg.host, cfg.port, cfg.ticket, cfg.wakeMac, cfg.wakeBroadcast, cfg.key, cfg.board, cfg.ident)
        val next = (
            listOf(entry) + _known.value.filterNot {
                it.key == entry.key || (it.host == entry.host && it.port == entry.port) ||
                    (entry.ident.isNotBlank() && it.ident == entry.ident && it.port == entry.port && sharesAddress(it.host, entry.host))
            }
            ).take(KNOWN_MAX)
        if (next == _known.value) return
        _known.value = next
        prefs.edit { putString(KEY_KNOWN, JSONArray(next.map { it.toJson() }).toString()) }
    }

    /** Takes a computer off the list, by its key: whatever its addresses have become. */
    fun forget(key: String) {
        val next = _known.value.filterNot { it.key == key }
        _known.value = next
        prefs.edit { putString(KEY_KNOWN, JSONArray(next.map { it.toJson() }).toString()) }
    }

    private fun readKnown(): List<KnownComputer> = runCatching {
        val a = JSONArray(prefs.getString(KEY_KNOWN, "[]"))
        val read = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> KnownComputer.fromJson(o, ::newKey) } }
        // one saved by a build before keys got one just now: written back, so it keeps it
        if ((0 until a.length()).any { a.optJSONObject(it)?.optString("key").isNullOrBlank() }) {
            prefs.edit { putString(KEY_KNOWN, JSONArray(read.map { it.toJson() }).toString()) }
        }
        read
    }.getOrDefault(emptyList())

    /**
     * When the clip last looked at was made ([android.content.ClipDescription.getTimestamp]): a
     * clip made at another time is new, whichever app it was copied in and however long ago
     * Weave was last open. -1 before any was looked at.
     */
    var clipSeen: Long
        get() = prefs.getLong("clipSeen", -1L)
        set(value) = prefs.edit { putLong("clipSeen", value) }

    /**
     * Where the full-screen panel's bubble was left: how far across and down the screen, 0 to 1
     * each. The top right corner until it is moved.
     */
    var panelSpot: Pair<Float, Float>
        get() = prefs.getFloat("panelX", 1f) to prefs.getFloat("panelY", 0f)
        set(value) = prefs.edit {
            putFloat("panelX", value.first)
            putFloat("panelY", value.second)
        }

    fun update(change: (LinkConfig) -> LinkConfig) {
        val next = change(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit {
            putString("host", next.host)
            putInt("port", next.port)
            putString("name", next.name)
            putString("ticket", next.ticket)
            putString("quality", next.quality)
            putFloat("speed", next.speed)
            putBoolean("naturalScroll", next.naturalScroll)
            putBoolean("scrollStrip", next.scrollStrip)
            putBoolean("scrollStripLeft", next.scrollStripLeft)
            putFloat("scrollStripSpeed", next.scrollStripSpeed)
            putBoolean("scrollStripRoll", next.scrollStripRoll)
            putBoolean("scrollStripTicks", next.scrollStripTicks)
            putBoolean("autoKeyboard", next.autoKeyboard)
            putBoolean("fillPreview", next.fillPreview)
            putBoolean("levelSound", next.levelSound)
            putBoolean("showPicture", next.showPicture)
            putBoolean("showShortcuts", next.showShortcuts)
            putBoolean("localPointer", next.localPointer)
            putBoolean("edgeHold", next.edgeHold)
            putBoolean("edgeSpaces", next.edgeSpaces)
            putBoolean("nativeKeyboard", next.nativeKeyboard)
            putFloat("pointerSize", next.pointerSize)
            putString("wakeMac", next.wakeMac)
            putString("wakeBroadcast", next.wakeBroadcast)
            putBoolean("clipSync", next.clipSync)
            putString("key", next.key)
            putString("board", next.board)
            putString("ident", next.ident)
        }
    }

    private fun read() = LinkConfig().let { d ->
        LinkConfig(
            host = prefs.getString("host", d.host).orEmpty(),
            port = prefs.getInt("port", d.port),
            name = prefs.getString("name", d.name).orEmpty(),
            ticket = prefs.getString("ticket", d.ticket).orEmpty(),
            quality = prefs.getString("quality", d.quality) ?: d.quality,
            speed = prefs.getFloat("speed", d.speed),
            naturalScroll = prefs.getBoolean("naturalScroll", d.naturalScroll),
            scrollStrip = prefs.getBoolean("scrollStrip", d.scrollStrip),
            scrollStripLeft = prefs.getBoolean("scrollStripLeft", d.scrollStripLeft),
            scrollStripSpeed = prefs.getFloat("scrollStripSpeed", d.scrollStripSpeed),
            scrollStripRoll = prefs.getBoolean("scrollStripRoll", d.scrollStripRoll),
            scrollStripTicks = prefs.getBoolean("scrollStripTicks", d.scrollStripTicks),
            autoKeyboard = prefs.getBoolean("autoKeyboard", d.autoKeyboard),
            fillPreview = prefs.getBoolean("fillPreview", d.fillPreview),
            levelSound = prefs.getBoolean("levelSound", d.levelSound),
            showPicture = prefs.getBoolean("showPicture", d.showPicture),
            showShortcuts = prefs.getBoolean("showShortcuts", d.showShortcuts),
            localPointer = prefs.getBoolean("localPointer", d.localPointer),
            edgeHold = prefs.getBoolean("edgeHold", d.edgeHold),
            edgeSpaces = prefs.getBoolean("edgeSpaces", d.edgeSpaces),
            nativeKeyboard = prefs.getBoolean("nativeKeyboard", d.nativeKeyboard),
            pointerSize = prefs.getFloat("pointerSize", d.pointerSize),
            wakeMac = prefs.getString("wakeMac", d.wakeMac).orEmpty(),
            wakeBroadcast = prefs.getString("wakeBroadcast", d.wakeBroadcast).orEmpty(),
            clipSync = prefs.getBoolean("clipSync", d.clipSync),
            board = prefs.getString("board", "").orEmpty(),
            ident = prefs.getString("ident", "").orEmpty(),
            // a computer picked by a build before keys gets one now; it will ask once more
            key = prefs.getString("key", "").orEmpty().ifBlank {
                if (prefs.getString("host", "").isNullOrBlank()) "" else newKey().also { made -> prefs.edit { putString("key", made) } }
            },
        )
    }

    private companion object {
        const val KEY_SECRET = "secret_v2"
        const val KEY_OLD_TOKEN = "token"
        const val KEY_KNOWN = "known"
        const val KNOWN_MAX = 8
    }
}

/** A computer this phone has been let into: where it is reached, and what it takes to wake it. */
data class KnownComputer(
    val name: String, val host: String, val port: Int, val ticket: String = "", val wakeMac: String = "", val wakeBroadcast: String = "",
    /** What its token is made from ([LinkPrefs.tokenFor]). */
    val key: String = "",
    /** The dashboard's id for it, when it was picked from the dashboard's list ([LinkConfig.board]). */
    val board: String = "",
    /** Its own id, from its welcome ([LinkConfig.ident]); blank for one that never said. */
    val ident: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("host", host).put("port", port).put("ticket", ticket)
        .put("wakeMac", wakeMac).put("wakeBroadcast", wakeBroadcast).put("key", key).put("board", board).put("ident", ident)

    companion object {
        /**
         * One saved by a build before keys gets one from [newKey]: it stays on the list with
         * what wakes it, and its computer asks once more.
         */
        fun fromJson(o: JSONObject, newKey: () -> String): KnownComputer? {
            val host = o.optString("host")
            if (host.isBlank()) return null
            return KnownComputer(o.optString("name").ifBlank { host.substringBefore(',') }, host, o.optInt("port", LinkProtocol.PORT),
                o.optString("ticket"), o.optString("wakeMac"), o.optString("wakeBroadcast"), o.optString("key").ifBlank { newKey() },
                o.optString("board"), o.optString("ident"))
        }
    }
}

/** Whether two saved lists of addresses ("a,b,c") have one in common. */
internal fun sharesAddress(one: String, other: String): Boolean {
    val theirs = other.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return one.split(',').map { it.trim() }.any { it.isNotEmpty() && it in theirs }
}

/** The token for one computer: an HMAC of the key the phone gave it, keyed by the phone's secret. */
internal fun deriveToken(secret: String, key: String): String {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal("weave-token-v3|$key".toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The wire format shared with the laptop's daemon (tandem/server.py): kind, length, payload. */
object LinkProtocol {
    const val PORT = 47600
    const val JSON = 1
    const val VIDEO = 2
    const val AUDIO = 3

    /** Frame flags. */
    const val KEY = 1
    const val CONFIG = 2
    /** Sent only to catch the decoder up (a buffered monitor switch): decoded, never shown. */
    const val CATCHUP = 4
}
