package com.t3lluz.weave.data.link

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One of the laptop's monitors: its size in pixels, and where it sits in the desktop's layout
 * ([x], [y], [layoutWidth], [layoutHeight], in the compositor's logical pixels; zero from an
 * older tandem that did not say).
 */
data class LinkMonitor(
    val name: String,
    val label: String,
    val width: Int,
    val height: Int,
    val x: Int = 0,
    val y: Int = 0,
    val layoutWidth: Int = 0,
    val layoutHeight: Int = 0,
)

/**
 * Which way the picture moves when the stream goes from [from] to [to]: the side the new
 * monitor lies on, as -1, 0 or 1 across and down. The same monitor (a new quality) is 0, 0.
 * Monitors side by side move across; stacked ones move up or down.
 */
internal fun switchDirection(from: LinkMonitor?, to: LinkMonitor?): Pair<Int, Int> {
    if (from == null || to == null || from.name == to.name) return 0 to 0
    if (from.layoutWidth <= 0 || to.layoutWidth <= 0) return 1 to 0
    val dx = (to.x + to.layoutWidth / 2f) - (from.x + from.layoutWidth / 2f)
    val dy = (to.y + to.layoutHeight / 2f) - (from.y + from.layoutHeight / 2f)
    // a monitor beside this one is across, even when their tops are not level
    val overlapX = minOf(from.x + from.layoutWidth, to.x + to.layoutWidth) - maxOf(from.x, to.x)
    return if (overlapX > 0 && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 0.5f) {
        0 to if (dy > 0) 1 else -1
    } else {
        (if (dx >= 0) 1 else -1) to 0
    }
}

/**
 * Where the link is. [Reconnecting] is a link that was up and dropped: the remote stays on
 * screen while it comes back, for up to [LinkSession.RECONNECT_GRACE_MS], before it counts as [Failed].
 */
enum class LinkStatus { Idle, Connecting, Pending, Online, Reconnecting, Denied, Failed }

data class LinkState(
    val status: LinkStatus = LinkStatus.Idle,
    /** The laptop's own name, once it has answered. */
    val laptop: String = "",
    val failure: String? = null,
    val monitors: List<LinkMonitor> = emptyList(),
    /** The monitor the stream is on, and whether it follows the pointer between monitors. */
    val monitor: String? = null,
    /** The monitor the picture shows: during a switch, the old one until the new picture lands. */
    val shown: String? = null,
    val followPointer: Boolean = true,
    /** The picture's quality now: the saved pick, or lighter when the laptop stepped it down on a slow link. */
    val quality: String? = null,
    val videoError: String? = null,
    /** The laptop's sound plays on this phone. */
    val laptopSound: Boolean = false,
    /** This phone's sound plays on the laptop. */
    val phoneAudio: Boolean = false,
    val pingMs: Int? = null,
    /** The last try failed and this phone has no VPN up: Tailscale is off, so the laptop can't be reached. */
    val tailscaleOff: Boolean = false,
    /** What the laptop's tandem can do beyond the first version: `catalog`, `launch`, `pointer`, `warp`, `sound`. */
    val caps: Set<String> = emptySet(),
) {
    /** The laptop can go without its picture: it reports the pointer and sends the sound alone. */
    val canHidePicture: Boolean get() = "pointer" in caps && "sound" in caps

    val shownMonitor: LinkMonitor? get() = monitors.firstOrNull { it.name == (shown ?: monitor) }
}

/** Where the laptop's pointer is on its desk, in the layout's pixels (see [LinkMonitor]). */
data class LinkPointer(val x: Float, val y: Float)

/** The monitor of [monitors] that [p] is on, if any. */
internal fun monitorUnder(monitors: List<LinkMonitor>, p: LinkPointer?): LinkMonitor? = p?.let {
    monitors.firstOrNull { m -> m.layoutWidth > 0 && p.x >= m.x && p.x < m.x + m.layoutWidth && p.y >= m.y && p.y < m.y + m.layoutHeight }
}

sealed interface LinkEvent {
    data class Toast(val message: String) : LinkEvent
    /** A click on the laptop landed in (or left) something you type into; [terminal] when it is a terminal. */
    data class TextFocus(val focused: Boolean, val terminal: Boolean = false) : LinkEvent
    /** The laptop's clipboard, asked for, or sent by itself while the two are kept in step ([sync]). */
    data class Clipboard(val text: String, val sync: Boolean = false) : LinkEvent
}

/**
 * The phone's end of the remote: one TCP connection to the laptop's daemon over the tailnet
 * (Tailscale encrypts it), carrying the laptop's screen and sound one way and touches, keys and
 * commands the other. It runs only while the remote screen is in front.
 */
@Singleton
class LinkSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: LinkPrefs,
) {
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val video = LinkVideo().also { v ->
        // the status pill names the new monitor when its picture lands, with the swipe
        v.onSettled = {
            _state.update { it.copy(shown = it.monitor) }
            shownChanged()
        }
    }
    private val audio = LinkAudio()

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state

    /** What the laptop offers to make a shortcut of, once asked for ([requestCatalog]). */
    private val _catalog = MutableStateFlow<LinkCatalog?>(null)
    val catalog: StateFlow<LinkCatalog?> = _catalog
    @Volatile private var catalogAsked = 0L

    /** The laptop's pointer on its desk, while the picture is off and the laptop reports it. */
    /**
     * Tells [localPointer] which monitor the laptop's reports are about, and its size, each time
     * that may have changed. That is the monitor the laptop named in its last "video" message,
     * not the one on screen: a switch lands on screen a little later, and the first report about
     * the new monitor comes before it does.
     */
    private fun shownChanged() {
        val st = _state.value
        val m = st.monitors.firstOrNull { it.name == st.monitor } ?: st.shownMonitor
        localPointer.onMonitor(m?.name, (m?.layoutWidth?.takeIf { it > 0 } ?: m?.width ?: 1).toFloat(), (m?.layoutHeight?.takeIf { it > 0 } ?: m?.height ?: 1).toFloat())
    }

    private val _pointer = MutableStateFlow<LinkPointer?>(null)
    val pointer: StateFlow<LinkPointer?> = _pointer

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<LinkEvent> = _events

    /** Where the laptop's pointer is on the monitor shown, 0..1; read each frame by the preview. */
    /** The pointer the phone draws over the picture, ahead of it (see [LocalPointer]). */
    val localPointer = LocalPointer()

    /** Whether the phone draws the pointer: asked for, and this tandem leaves it out of the picture. */
    val drawsPointer: Boolean get() = prefs.config.value.localPointer && LOCAL_CURSOR in _state.value.caps

    @Volatile var cursorX = 0.5f
        private set
    @Volatile var cursorY = 0.5f
        private set

    @Volatile private var wanted = false
    @Volatile private var generation = 0

    /**
     * One connection: its own socket and its own queue of what to send. Each try at connecting
     * gets a new one, so what is meant for the computer picked now can never be written to the
     * one picked before, whose thread may still be working through its addresses.
     */
    private class Line(val generation: Int, /** The key of the computer it is to. */ val key: String) {
        @Volatile var socket: Socket? = null
        val outbox = LinkedBlockingQueue<ByteArray>()

        /**
         * Connected, with its hello first in the queue: only from here is anything else taken.
         * What is sent while an address is still being waited on (a ping, a finger on the pad)
         * is old by the time it could go, and a computer hangs up on a first word that isn't hello.
         */
        @Volatile var open = false
    }

    /** The connection of the computer picked now; what is sent goes to this one only. */
    @Volatile private var line: Line? = null
    private val lines = Any()
    private var pingSentAt = 0L

    fun start() {
        if (wanted) return
        if (prefs.config.value.host.isBlank()) return
        wanted = true
        val mine = synchronized(lines) { ++generation }
        Thread({ run(mine) }, "link").apply { isDaemon = true }.start()
    }

    fun stop() {
        wanted = false
        synchronized(lines) { generation++ }
        closeSocket()
        video.release()
        audio.stop()
        _state.update { LinkState(laptopSound = it.laptopSound) }
        _catalog.value = null
        catalogAsked = 0L
        _pointer.value = null
    }

    /** After the host changed, or to try again by hand. */
    fun reconnect() {
        stop()
        start()
    }

    /** Ends the connection of the computer picked now. */
    private fun closeSocket() {
        val was = synchronized(lines) { line.also { line = null } }
        close(was)
    }

    private fun close(ln: Line?) {
        try {
            ln?.socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun run(mine: Int) {
        var wait = 600L
        var droppedAt = 0L
        while (wanted && generation == mine) {
            val cfg = prefs.config.value
            val st = _state.value.status
            if (st != LinkStatus.Online && st != LinkStatus.Reconnecting) _state.update { it.copy(status = LinkStatus.Connecting) }
            var reason: String? = null
            var writer: Thread? = null
            // this try's own connection; it becomes the one to send to only while this is still the computer picked
            val ln = Line(mine, cfg.key)
            // The hello is the first thing in this connection's own queue, before anything else can
            // be put there: its token is this computer's and no other's.
            ln.outbox.offer(
                frame(
                    JSONObject().put("t", "hello").put("token", prefs.tokenFor(cfg.key)).put("ticket", cfg.ticket)
                        .put("name", Build.MODEL).put("ver", 3).put("net", network())
                        .put("cursor", if (cfg.localPointer) "local" else "laptop").toString(),
                ),
            )
            synchronized(lines) {
                if (generation != mine) return
                line = ln
            }
            try {
                val s = connect(cfg, ln)
                // a path that dies before the welcome is given up on in seconds too
                s.soTimeout = READ_TIMEOUT_MS
                val out = s.getOutputStream()
                ln.open = true
                writer = Thread({ write(out, ln.outbox) }, "link-out").apply { isDaemon = true; start() }
                read(DataInputStream(s.getInputStream().buffered(1 shl 16)), ln)
            } catch (e: SocketTimeoutException) {
                reason = if (_state.value.status == LinkStatus.Online) "The laptop stopped answering." else e.message
            } catch (e: IOException) {
                reason = e.message
            } catch (e: Exception) {
                // a frame that made no sense, a decoder that threw: the connection is over, the app is not.
                // Nothing here may escape: an uncaught exception on this thread would take the whole app down.
                reason = e.message ?: e.javaClass.simpleName
            } finally {
                // only this connection: the one after it may be open already
                synchronized(lines) { if (line === ln) line = null }
                close(ln)
                writer?.interrupt()
                // the picture and the sound are the session's: a try that ended after another
                // computer was picked must not take that one's away
                if (generation == mine) {
                    video.release()
                    audio.stop()
                }
            }
            if (!wanted || generation != mine) return
            if (_state.value.status == LinkStatus.Denied) {
                wanted = false
                return
            }
            // a link that was up comes back fast, and the remote stays on screen meanwhile
            val now = System.currentTimeMillis()
            if (_state.value.status == LinkStatus.Online) {
                wait = 300L
                droppedAt = now
            }
            // with the computer on this phone's own network, Tailscale has no part in reaching it
            val near = network() == "wifi" && cfg.host.split(',').any { isLocalNetwork(it) }
            val noVpn = !vpnUp() && !near
            val grace = droppedAt > 0 && now - droppedAt < RECONNECT_GRACE_MS && !noVpn
            _state.update {
                it.copy(
                    status = if (grace) LinkStatus.Reconnecting else LinkStatus.Failed,
                    failure = plain(reason, noVpn), tailscaleOff = noVpn,
                )
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
            wait = (wait * 2).coerceAtMost(4_000L)
        }
    }

    /** The first of the laptop's addresses that answers. */
    private fun connect(cfg: LinkConfig, ln: Line): Socket {
        val candidates = cfg.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var last: IOException? = null
        for ((i, address) in candidates.withIndex()) {
            // another computer was picked while an address was waited on: no more of this one's are tried
            if (generation != ln.generation) throw IOException("Another computer was picked")
            val s = Socket()
            ln.socket = s
            try {
                // (stop() closes the socket it finds: one put there just after it looked is closed here)
                if (generation != ln.generation) throw IOException("Another computer was picked")
                s.tcpNoDelay = true
                s.keepAlive = true
                // over mobile data the tailnet's first packet can take a few seconds to find its way
                s.connect(InetSocketAddress(address, cfg.port), if (i == candidates.lastIndex) 7_000 else 5_000)
                if (generation != ln.generation) {
                    s.close()
                    throw IOException("Another computer was picked")
                }
                return s
            } catch (e: IOException) {
                last = e
                try {
                    s.close()
                } catch (_: IOException) {
                }
            }
        }
        throw last ?: IOException("No address for the laptop")
    }

    private fun write(out: OutputStream, outbox: LinkedBlockingQueue<ByteArray>) {
        try {
            while (true) {
                out.write(outbox.take())
                // whatever else is queued goes in the same flush
                while (true) out.write(outbox.poll() ?: break)
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun read(input: DataInputStream, ln: Line) {
        val mine = ln.generation
        var buffer = ByteArray(1 shl 16)
        while (true) {
            val kind = input.readUnsignedByte()
            val size = input.readInt()
            if (size < 0 || size > 16_000_000) throw IOException("Bad frame")
            if (size > buffer.size) buffer = ByteArray(size + (size shr 2))
            input.readFully(buffer, 0, size)
            when (kind) {
                LinkProtocol.JSON -> {
                    // a message read just as another computer was picked is not that one's to act on
                    if (generation != mine) throw IOException("Another computer was picked")
                    onMessage(JSONObject(String(buffer, 0, size)), ln)
                }
                LinkProtocol.VIDEO -> if (size > 9) video.feed(buffer[0].toInt(), pts(buffer), buffer, 9, size - 9)
                LinkProtocol.AUDIO -> if (size > 9 && _state.value.laptopSound) audio.feed(buffer[0].toInt(), pts(buffer), buffer, 9, size - 9)
            }
        }
    }

    /** A socket error in words a person would use. */
    private fun plain(reason: String?, noVpn: Boolean): String = when {
        noVpn -> "Tailscale is off on this phone, and the computer isn't on this Wi-Fi."
        reason == null -> "The computer closed the connection."
        "ECONNREFUSED" in reason || "refused" in reason -> "The computer is on, but Weave isn't running on it."
        "timed out" in reason || "ETIMEDOUT" in reason || "EHOSTUNREACH" in reason || "unreachable" in reason ->
            "The computer didn't answer. Is it on, and on this Wi-Fi or your tailnet?"
        "Unable to resolve" in reason || "No address" in reason -> "That name doesn't lead anywhere. Is Tailscale up on this phone?"
        else -> reason
    }

    /** Whether any VPN is up: on Android, Tailscale is one. Without it the laptop's address leads nowhere. */
    private fun vpnUp(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Exception) {
        true
    }

    /** What this phone is on, for the laptop's dashboard: wifi, cell or other. */
    private fun network(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        when {
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "wifi"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> "cell"
            else -> "other"
        }
    } catch (_: Exception) {
        "other"
    }

    private fun pts(b: ByteArray): Long {
        var v = 0L
        for (i in 1..8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** [ln] is the connection the message came in on. */
    private fun onMessage(m: JSONObject, ln: Line) {
        when (m.optString("t")) {
            "pending" -> {
                // the laptop asks its owner and says nothing for up to two minutes meanwhile; one
                // that went to sleep never answers, and Android's keepalive would wait hours
                try {
                    line?.socket?.soTimeout = PENDING_TIMEOUT_MS
                } catch (_: IOException) {
                }
                _state.update { it.copy(status = LinkStatus.Pending, laptop = m.optString("host")) }
            }
            "denied" -> _state.update { it.copy(status = LinkStatus.Denied) }
            "welcome" -> {
                // A welcome read just as another computer was picked is not that one's: it must be
                // the connection of now, to the computer picked now, or nothing of it is taken.
                if (ln !== line || ln.key != prefs.config.value.key) return
                // From here a link that goes quiet (a new network, a laptop that slept) is given up
                // on in seconds: video and pongs never stop this long on a live one.
                try {
                    line?.socket?.soTimeout = READ_TIMEOUT_MS
                } catch (_: IOException) {
                }
                _state.update {
                    it.copy(
                        status = LinkStatus.Online, failure = null, tailscaleOff = false, laptop = m.optString("host"),
                        monitors = monitors(m.optJSONArray("monitors")),
                        phoneAudio = m.optBoolean("phoneAudio"),
                        caps = m.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty(),
                    )
                }
                // the picture, or with it off, where the pointer is (a tandem that can't say shows the picture)
                if (prefs.config.value.showPicture || !_state.value.canHidePicture) askForPicture() else send("""{"t":"pointer","on":true}""")
                catalogAsked = 0L
                // the laptop's setting "start with sound" turns it on from the first frame
                if (m.optBoolean("laptopSound") && !_state.value.laptopSound) _state.update { it.copy(laptopSound = true) }
                if (_state.value.laptopSound) {
                    send("""{"t":"audio","on":true}""")
                    readyToHear()
                }
                sendJson(JSONObject().put("t", "adb").put("ips", JSONArray(ownAddresses())))
                if (syncsClipboard) send("""{"t":"clip_sync","on":true}""")
                localPointer.laptopGain(m.optDouble("gain", Double.NaN).takeIf { !it.isNaN() }?.toFloat())
                // what wakes this laptop from sleep, kept for when it is asleep and can't say
                m.optJSONObject("wake")?.let { w ->
                    val mac = w.optString("mac")
                    // (written only onto the computer this connection is to, should another be picked meanwhile)
                    if (mac.isNotBlank()) {
                        prefs.update { if (it.key == ln.key) it.copy(wakeMac = mac, wakeBroadcast = w.optString("brd").takeIf { b -> b != "null" }.orEmpty()) else it }
                    }
                }
                // Its own id, for knowing it again when a code for it is scanned. Only the computer's
                // own word counts, said here; one that says none has none, whatever a code claimed.
                val id = m.optString("id").take(64)
                prefs.update { if (it.key == ln.key) it.copy(ident = id) else it }
                // let in: this computer is one of the phone's own from now on, listed without a dashboard
                prefs.config.value.takeIf { it.key == ln.key }?.let(prefs::remember)
                ping()
            }
            "video" -> {
                val on = m.optBoolean("on")
                val monitors = m.optJSONArray("monitors")?.let(::monitors) ?: _state.value.monitors
                when {
                    // the picture stopped (its recorder died): drop it, so the cover shows why and offers Try again
                    !on -> video.release()
                    m.optBoolean("same") -> Unit
                    // the laptop kept the old stream coming until this one was ready: slide across
                    m.has("switch") -> {
                        val from = monitors.firstOrNull { it.name == m.optString("switch") }
                        val to = monitors.firstOrNull { it.name == m.optString("monitor") }
                        val (dx, dy) = switchDirection(from, to)
                        video.announceSwitch(dx, dy)
                    }
                    else -> video.expect(m.optInt("w"), m.optInt("h"))
                }
                val name = if (on) m.optString("monitor") else null
                _state.update {
                    it.copy(
                        monitor = name ?: it.monitor,
                        // a switch names its monitor when the picture lands (onSettled); a picture that
                        // stopped names the one the cover is about; anything else at once
                        shown = when {
                            !on -> m.optString("monitor").ifBlank { null } ?: it.monitor
                            name != null && !video.isSwitching -> name
                            else -> it.shown
                        },
                        followPointer = if (on) m.optBoolean("auto", true) else it.followPointer,
                        quality = if (on) m.optString("quality").ifBlank { null } else it.quality,
                        monitors = monitors,
                        videoError = if (on) null else m.optString("error").ifBlank { null },
                    )
                }
                shownChanged()
            }
            "cur" -> {
                cursorX = m.optDouble("x", 0.5).toFloat()
                cursorY = m.optDouble("y", 0.5).toFloat()
                localPointer.report(cursorX, cursorY, android.os.SystemClock.uptimeMillis(), if (m.has("seq")) m.optLong("seq") else null)
            }
            "ptr" -> _pointer.value = LinkPointer(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
            "monitors" -> m.optJSONArray("monitors")?.let { a ->
                _state.update { it.copy(monitors = monitors(a)) }
                shownChanged()
            }
            // the list first, then the apps' icons a few at a time; both are read away from this
            // thread, which carries the picture, and dropped if the link changed meanwhile
            "catalog" -> offThread { mine -> catalogOf(m).let { c -> if (mine == generation) _catalog.value = c } }
            "icons" -> offThread { mine -> withIcons(m.optJSONObject("icons"), m.optBoolean("done")).let { c -> if (c != null && mine == generation) _catalog.value = c } }
            "state" -> {
                _state.update { it.copy(phoneAudio = m.optBoolean("phoneAudio")) }
                routeLaptopSound()
            }
            // the dashboard's laptop page flipped a switch that lives on this phone
            "set" -> if (m.has("laptopSound")) {
                val on = m.optBoolean("laptopSound")
                setLaptopSound(on)
                _events.tryEmit(LinkEvent.Toast(if (on) "The laptop's sound plays here, turned on from the dashboard" else "The laptop's sound was turned off from the dashboard"))
            }
            "focus" -> _events.tryEmit(LinkEvent.TextFocus(m.optBoolean("text"), m.optBoolean("terminal")))
            "clip" -> {
                val text = m.optString("text")
                val sync = m.optBoolean("sync")
                // The two crossed on the way: the laptop sent its copy before the phone's reached it.
                // The phone's wins there, so it wins here, or each would end with the other's.
                // (a relayed link's round trip can be longer than the shortest window)
                val crossed = maxOf(CLIP_CROSSED_MS, 2L * (_state.value.pingMs ?: 0))
                if (sync && android.os.SystemClock.uptimeMillis() - clipSentAt < crossed) return
                // kept, so the phone's copy of it isn't sent straight back
                if (sync) clipSynced = text
                _events.tryEmit(LinkEvent.Clipboard(text, sync))
            }
            "toast" -> _events.tryEmit(LinkEvent.Toast(m.optString("msg")))
            "pong" -> {
                val ms = (System.nanoTime() - pingSentAt) / 1_000_000
                _state.update { it.copy(pingMs = ms.toInt()) }
                localPointer.rttMs = ms.toInt()
            }
        }
    }

    private fun monitors(array: JSONArray?): List<LinkMonitor> = buildList {
        for (i in 0 until (array?.length() ?: 0)) {
            val o = array!!.optJSONObject(i) ?: continue
            add(
                LinkMonitor(
                    o.optString("name"), o.optString("label"), o.optInt("w"), o.optInt("h"),
                    x = o.optInt("x"), y = o.optInt("y"), layoutWidth = o.optInt("lw"), layoutHeight = o.optInt("lh"),
                ),
            )
        }
    }

    /** This phone's addresses, so the laptop knows where to look for it over adb. */
    private fun ownAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    /** Runs [block] on the one worker the catalog has, in the order the messages came, told which link it started on. */
    private fun offThread(block: (Int) -> Unit) {
        val mine = generation
        catalogWorker.execute { runCatching { block(mine) } }
    }

    private val catalogWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "link-catalog").apply { isDaemon = true } }

    private fun app(id: String, name: String, b64: String?, coming: Boolean = false): LinkApp {
        val png = b64?.takeIf { it.isNotEmpty() }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
        val bitmap = png?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        return LinkApp(id, name, png, bitmap, hasIcon = png != null || coming)
    }

    /** The catalog with the icons of [icons] (by app id) put on its apps; null when there is no catalog yet. */
    private fun withIcons(icons: JSONObject?, done: Boolean): LinkCatalog? {
        val have = _catalog.value ?: return null
        if ((icons == null || icons.length() == 0) && !done) return null
        // after the last batch, an app the list said had an icon but that never came was one the
        // laptop couldn't draw this time: it keeps hasIcon, so it is tried again another visit
        return LinkCatalog(
            have.apps.map { a -> if (icons != null && icons.has(a.id)) app(a.id, a.name, icons.optString(a.id), coming = true) else a },
            have.binds, have.at, iconsDone = have.iconsDone || done,
        )
    }

    private fun catalogOf(m: JSONObject): LinkCatalog {
        // a list asked for again keeps the icons it already has until their batches come back
        val before = _catalog.value?.apps.orEmpty().associateBy { it.id }
        val apps = m.optJSONArray("apps")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                when (val icon = o.opt("icon")) {
                    // this tandem sends the icons after the list: true says one is coming
                    is Boolean -> before[id]?.takeIf { icon && it.png != null }?.let { LinkApp(id, o.optString("name"), it.png, it.bitmap, true) }
                        ?: LinkApp(id, o.optString("name"), hasIcon = icon)
                    // an older one sends them with it
                    else -> app(id, o.optString("name"), o.optString("icon"))
                }
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.orEmpty()
        val binds = m.optJSONArray("binds")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val mods = o.optJSONArray("mods")?.let { ms -> (0 until ms.length()).map { ms.optString(it) } }.orEmpty()
                LinkBind(o.optString("label"), o.optString("key"), LinkKeys.ordered(mods), o.optString("cap"))
            }.filter { it.label.isNotBlank() && it.key.isNotBlank() }
        }.orEmpty()
        return LinkCatalog(apps, binds)
    }

    // ── out ──

    /** Whether it went on its way: not while there is no connection, or one still being made. */
    private fun send(json: String): Boolean {
        val ln = line ?: return false
        if (!ln.open) return false
        return ln.outbox.offer(frame(json))
    }

    private fun frame(json: String): ByteArray {
        val body = json.toByteArray()
        val frame = ByteArray(5 + body.size)
        frame[0] = LinkProtocol.JSON.toByte()
        frame[1] = (body.size ushr 24).toByte()
        frame[2] = (body.size ushr 16).toByte()
        frame[3] = (body.size ushr 8).toByte()
        frame[4] = body.size.toByte()
        body.copyInto(frame, 5)
        return frame
    }

    private fun sendJson(o: JSONObject) = send(o.toString())

    /** Measures the round trip; the screen calls it every few seconds while it is in front. The last one rides along, for the laptop's dashboard. */
    fun ping() {
        pingSentAt = System.nanoTime()
        val rtt = _state.value.pingMs
        send("""{"t":"ping"${if (rtt != null) ""","rtt":$rtt""" else ""},"net":"${network()}"}""")
    }

    /** Numbers the moves, so the laptop's pointer reports can say which they already show. */
    private val moveSeq = java.util.concurrent.atomic.AtomicLong(0)

    fun move(dx: Float, dy: Float) {
        val seq = moveSeq.incrementAndGet()
        send("""{"t":"m","dx":${round2(dx)},"dy":${round2(dy)},"s":$seq}""")
        if (drawsPointer) localPointer.sent(dx, dy, android.os.SystemClock.uptimeMillis(), seq)
    }
    fun scroll(dx: Float, dy: Float) = send("""{"t":"scroll","dx":${round2(dx)},"dy":${round2(dy)}}""")
    /**
     * Two fingers or more on the pad, for the computer's own touchpad ([TOUCHPAD]): each finger's
     * number and where it is in mm; none lifts them all. [natural] is the way the phone wants
     * scrolling to go, which the computer squares with its own.
     */
    fun touch(fingers: List<Triple<Long, Float, Float>>, natural: Boolean) {
        val f = fingers.joinToString(",") { "[${it.first},${round2(it.second)},${round2(it.third)}]" }
        send("""{"t":"touch","nat":$natural,"f":[$f]}""")
    }
    fun click(button: String = "left", count: Int = 1) = send("""{"t":"click","btn":"$button","n":$count}""")
    fun button(button: String, down: Boolean) = send("""{"t":"b","btn":"$button","down":$down}""")

    /** Puts the pointer at a point of the monitor shown (0..1), and clicks there if asked. */
    fun pointAt(x: Float, y: Float, click: Boolean) {
        send("""{"t":"abs","x":${round4(x)},"y":${round4(y)},"click":$click}""")
        if (drawsPointer) localPointer.jump(x, y)
    }

    fun key(name: String, mods: Collection<String> = emptyList()) =
        sendJson(JSONObject().put("t", "key").put("key", name).put("mods", JSONArray(mods)))

    fun text(value: String) = sendJson(JSONObject().put("t", "text").put("s", value))
    fun media(command: String) = send("""{"t":"media","cmd":"$command"}""")
    fun lock() = send("""{"t":"sys","cmd":"lock"}""")
    /** Whether it went: false between two tries at connecting, when it has to be sent again later. */
    fun copyToLaptop(text: String, quiet: Boolean = false): Boolean {
        if (!send(JSONObject().put("t", "clip_set").put("text", text).put("quiet", quiet).toString())) return false
        if (quiet) {
            clipSynced = text
            clipSentAt = android.os.SystemClock.uptimeMillis()
        }
        return true
    }

    /** The last text either clipboard had while the two are kept in step: the one not to send again. */
    @Volatile var clipSynced: String? = null

    /** When the phone last sent its own copy over while the two are kept in step. */
    @Volatile private var clipSentAt = 0L

    /** When the phone's clip last looked at was made; see [LinkPrefs.clipSeen]. */
    var clipSeen: Long
        get() = prefs.clipSeen
        set(value) { prefs.clipSeen = value }

    /** Whether the two clipboards are kept in step: asked for, and this tandem can. */
    val syncsClipboard: Boolean get() = prefs.config.value.clipSync && CLIP_SYNC in _state.value.caps

    /** Keeps the two clipboards in step, or stops. */
    fun setClipSync(on: Boolean) {
        prefs.update { it.copy(clipSync = on) }
        // (turned on by hand: only what is copied from here on goes over, on the laptop's side too)
        if (CLIP_SYNC in _state.value.caps) send("""{"t":"clip_sync","on":$on,"fresh":true}""")
    }
    fun askClipboard() = send("""{"t":"clip_get"}""")
    fun openOnLaptop(url: String) = sendJson(JSONObject().put("t", "open").put("url", url))

    /**
     * Shows one monitor, or null to follow the pointer. The quality stays as it is: the laptop
     * may be holding the picture lighter for a slow link, and steps it back up by itself.
     */
    fun showMonitor(name: String?) {
        send("""{"t":"video","on":true,"monitor":"${name ?: "auto"}"}""")
    }

    /** A quality picked here: saved, and what the laptop returns to after a slow spell. */
    fun setQuality(quality: String) {
        prefs.update { it.copy(quality = quality) }
        val s = _state.value
        send("""{"t":"video","on":true,"monitor":"${if (s.followPointer) "auto" else s.monitor ?: "auto"}","quality":"$quality"}""")
    }

    fun setLaptopSound(on: Boolean) {
        _state.update { it.copy(laptopSound = on) }
        send("""{"t":"audio","on":$on}""")
        if (on) readyToHear() else audio.stop()
    }

    /**
     * Turning the laptop's sound on means hearing it. A phone kept with its media volume at
     * zero would play it silently, so the volume comes up (with Android's own slider shown,
     * so it is plain what happened).
     */
    private fun readyToHear() {
        routeLaptopSound()
        audio.level = prefs.config.value.levelSound
        try {
            val am = audioManager
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.45f).toInt().coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
                _events.tryEmit(LinkEvent.Toast("Media volume was at zero. Raised it so you hear the laptop."))
            }
        } catch (_: SecurityException) {
            // Do not disturb can forbid it; the volume keys still work
        }
    }

    /**
     * While this phone's own sound is being sent to the laptop, Android routes everything the
     * phone plays there. The laptop's sound would go straight back to the laptop and never be
     * heard here, so it is pinned to the phone's speaker for as long as that lasts.
     */
    private fun routeLaptopSound() {
        audio.speaker = if (_state.value.phoneAudio) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            null
        }
    }

    /** Whether quiet sound from the laptop is brought up to a phone's level. */
    fun setLevelSound(on: Boolean) {
        prefs.update { it.copy(levelSound = on) }
        audio.level = on
    }

    /** Asks the laptop for its picture, at the saved quality; a monitor that was pinned stays pinned. */
    private fun askForPicture() {
        val pinned = _state.value.takeIf { !it.followPointer }?.monitor ?: "auto"
        send("""{"t":"video","on":true,"monitor":"$pinned","quality":"${prefs.config.value.quality}"}""")
    }

    /**
     * Shows the laptop's screen, or hides it: then the laptop sends no picture at all (the
     * sound still comes when it is on) and says where its pointer is instead, for the map.
     */
    fun setPicture(on: Boolean) {
        prefs.update { it.copy(showPicture = on) }
        if (on) {
            send("""{"t":"pointer","on":false}""")
            _state.update { it.copy(videoError = null) }
            askForPicture()
        } else {
            send("""{"t":"video","on":false}""")
            send("""{"t":"pointer","on":true}""")
            video.release()
        }
    }

    /** Asks for the laptop's apps and bindings, unless they came within the last two minutes. */
    fun requestCatalog() {
        if (CATALOG !in _state.value.caps) return
        val now = System.currentTimeMillis()
        val have = _catalog.value
        if (have != null && now - have.at < 120_000L) return
        if (now - catalogAsked < 10_000L) return
        catalogAsked = now
        send("""{"t":"catalog"}""")
    }

    /** Does one step on the laptop at once. A wait does nothing here: [runSteps] waits it out. */
    fun run(s: LinkStep) {
        stepMessage(s)?.let(::sendJson)
    }

    /** A shortcut's steps, in order and at their pace ([paceSteps]). */
    suspend fun runSteps(steps: List<LinkStep>) = paceSteps(steps, ::run)

    /** The picture stopped (its recorder died): ask for it again, as it was. */
    fun retryVideo() {
        // the error goes at once, so the cover shows the spinner while the laptop starts the picture
        _state.update { it.copy(videoError = null) }
        val s = _state.value
        showMonitor(if (s.followPointer) null else s.monitor)
    }

    fun setPhoneAudio(on: Boolean) = send("""{"t":"phone_audio","on":$on}""")

    private fun round2(v: Float) = Math.round(v * 100f) / 100.0
    private fun round4(v: Float) = Math.round(v * 10000f) / 10000.0

    companion object {
        /** How long an app gets to come to the front before the next step. */
        const val SETTLE_MS = 1_300L
        /** How long a page gets to open before the next step. */
        const val PAGE_SETTLE_MS = 900L
        /** Between two steps that need no waiting. */
        const val BEAT_MS = 60L
        private const val READ_TIMEOUT_MS = 12_000
        private const val PENDING_TIMEOUT_MS = 150_000
        const val RECONNECT_GRACE_MS = 20_000L
        const val CATALOG = "catalog"
        const val POINTER = "pointer"
        /** This tandem can leave the pointer out of the picture, for the phone to draw its own. */
        const val LOCAL_CURSOR = "localcursor"
        /** This tandem sends its clipboard as it changes, for the phone to keep the two in step. */
        const val CLIP_SYNC = "clipsync"
        /** A copy from the laptop this soon after the phone sent its own was sent before that one landed. */
        const val CLIP_CROSSED_MS = 400L
        /** This computer has a touchpad of its own for the phone's fingers ("touch"): it reads swipes and pinches itself. */
        const val TOUCHPAD = "touchpad"
    }
}

/** What a step asks of the laptop, as its message; null for a wait, which is the phone's to sit out. */
internal fun stepMessage(s: LinkStep): JSONObject? = when (s.kind) {
    LinkShortcutKind.APP -> JSONObject().put("t", "launch").put("app", s.app)
    LinkShortcutKind.SITE -> JSONObject().put("t", "open").put("url", s.url)
    LinkShortcutKind.KEYS -> JSONObject().put("t", "key").put("key", s.key).put("mods", JSONArray(s.mods))
    LinkShortcutKind.TEXT -> JSONObject().put("t", "text").put("s", s.text)
    LinkShortcutKind.WAIT -> null
}

/**
 * A shortcut's steps, in order, each handed to [send] when its time comes. A wait waits. A step
 * after an app or a page waits until that has had [LinkSession.SETTLE_MS] or
 * [LinkSession.PAGE_SETTLE_MS] to come to the front, waits in between counting toward it: Vesktop
 * takes 0.8 to 1 s to take focus on t3lluz, and keys sent before that land in whatever window had
 * it. Other steps get a beat between them, so two key presses never run together.
 */
internal suspend fun paceSteps(steps: List<LinkStep>, send: (LinkStep) -> Unit) {
    var readyAt = 0L
    for (step in steps) {
        if (step.kind == LinkShortcutKind.WAIT) {
            kotlinx.coroutines.delay(step.ms.toLong())
            continue
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (readyAt > now) kotlinx.coroutines.delay(readyAt - now)
        send(step)
        readyAt = android.os.SystemClock.uptimeMillis() + when (step.kind) {
            LinkShortcutKind.APP -> LinkSession.SETTLE_MS
            LinkShortcutKind.SITE -> LinkSession.PAGE_SETTLE_MS
            else -> LinkSession.BEAT_MS
        }
    }
}
