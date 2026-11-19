package com.t3lluz.weave.roam

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkOneShot
import com.t3lluz.weave.data.link.LinkPrefs
import com.t3lluz.weave.data.link.LinkProtocol
import com.t3lluz.weave.data.link.isLocalNetwork
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A line to the computer for the earbuds' business, beside the remote's own: the computer's
 * sound for the player ([play]), or only where the earbuds are, for the Labs screen. It says so
 * in its hello, and the computer keeps it apart from the remote, so neither ends the other.
 *
 * It keeps itself up: a line that drops is made again, at once and then a little slower, for
 * as long as it is wanted. Once sound flows, a dead network gets three seconds: sound never
 * stops for that long on a live one.
 */
class RoamLine(
    private val context: Context,
    private val prefs: LinkPrefs,
    private val roam: RoamPrefs,
    private val play: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** The computer let this line in, or the line dropped. */
        fun online(on: Boolean) {}

        /** Where the earbuds are; [release] when the computer is about to take them back. */
        fun status(status: RoamStatus, release: Boolean) {}

        /** A piece of the computer's sound, as the remote's line carries it. */
        fun sound(flags: Int, ptsMs: Long, data: ByteArray, offset: Int, length: Int) {}

        /** Something the computer wants said, such as why it couldn't do what was asked. */
        fun said(text: String) {}

        /** The computer won't have this phone, or none is picked: nothing more is tried. */
        fun refused(why: String) {}
    }

    @Volatile private var wanted = false
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var outbox: LinkedBlockingQueue<ByteArray>? = null
    @Volatile var isOnline = false
        private set

    fun start() {
        if (wanted) return
        wanted = true
        val mine = ++generation
        Thread({ run(mine) }, if (play) "roam-play" else "roam-watch").apply { isDaemon = true }.start()
    }

    fun stop() {
        wanted = false
        generation++
        closeSocket()
    }

    /** Ends the connection there is, so the next is made over whatever network the phone has now. */
    fun kick() = closeSocket()

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    /** Whether it went on its way: not while there is no connection. */
    fun send(message: JSONObject): Boolean {
        if (!isOnline) return false
        return outbox?.offer(LinkOneShot.frame(message)) == true
    }

    private fun run(mine: Int) {
        var wait = FIRST_WAIT_MS
        while (wanted && generation == mine) {
            val cfg = roam.computer(prefs)
            if (cfg == null) {
                listener.refused("Pick a computer in Weave first, then turn this on.")
                return
            }
            var writer: Thread? = null
            var refused: String? = null
            try {
                val s = connect(cfg, mine)
                socket = s
                s.soTimeout = HELLO_TIMEOUT_MS
                val queue = LinkedBlockingQueue<ByteArray>()
                queue.offer(
                    LinkOneShot.frame(
                        JSONObject().put("t", "hello").put("token", prefs.tokenFor(cfg.key)).put("ticket", cfg.ticket)
                            .put("name", Build.MODEL).put("ver", 3).put("roam", if (play) "play" else "watch").put("net", network())
                            // a computer whose Weave knows nothing of this takes the line for a shortcut's:
                            // no "connected" notice there, and the remote's own line is left alone
                            .put("quiet", true),
                    ),
                )
                outbox = queue
                val out = s.getOutputStream()
                writer = Thread({ write(out, queue) }, "roam-out").apply { isDaemon = true; start() }
                val input = DataInputStream(s.getInputStream().buffered(1 shl 15))
                var buffer = ByteArray(1 shl 14)
                var flowing = false
                while (true) {
                    val kind = input.readUnsignedByte()
                    val size = input.readInt()
                    if (size < 0 || size > 4_000_000) throw IOException("Bad frame")
                    if (size > buffer.size) buffer = ByteArray(size + (size shr 2))
                    input.readFully(buffer, 0, size)
                    if (generation != mine) throw IOException("Stopped")
                    when (kind) {
                        LinkProtocol.JSON -> {
                            val m = JSONObject(String(buffer, 0, size))
                            when (m.optString("t")) {
                                "welcome" -> {
                                    if (!m.has("roam")) {
                                        refused = "This computer's Weave can't do this yet. It needs Weave on Linux, up to date."
                                        throw IOException(refused)
                                    }
                                    s.soTimeout = QUIET_TIMEOUT_MS
                                    wait = FIRST_WAIT_MS
                                    isOnline = true
                                    listener.online(true)
                                    m.optJSONObject("roam")?.let { listener.status(RoamStatus.from(it), false) }
                                }
                                "roam" -> listener.status(RoamStatus.from(m), m.optBoolean("release"))
                                "toast" -> listener.said(m.optString("msg"))
                                "pending" -> refused = "Open Weave once so the computer can let this phone in."
                                "denied" -> refused = "The computer didn't let this phone in."
                            }
                            if (refused != null) throw IOException(refused)
                        }
                        LinkProtocol.AUDIO -> if (size > 9) {
                            if (!flowing) {
                                // from here the sound never stops: a line gone quiet has died
                                flowing = true
                                s.soTimeout = PLAY_TIMEOUT_MS
                            }
                            listener.sound(buffer[0].toInt(), pts(buffer), buffer, 9, size - 9)
                        }
                    }
                }
            } catch (_: IOException) {
            } catch (_: Exception) {
                // a frame that made no sense: this connection is over, the app is not
            } finally {
                outbox = null
                closeSocket()
                writer?.interrupt()
                if (isOnline) {
                    isOnline = false
                    if (generation == mine) listener.online(false)
                }
            }
            if (!wanted || generation != mine) return
            refused?.let {
                wanted = false
                listener.refused(it)
                return
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
            wait = (wait * 2).coerceAtMost(LAST_WAIT_MS)
        }
    }

    /**
     * The first of the computer's addresses that answers. With Tailscale up its tailnet address
     * goes first: that connection lives on when the phone leaves the Wi-Fi for mobile data, where
     * one to the computer's own network address would die at the door.
     */
    private fun connect(cfg: LinkConfig, mine: Int): Socket {
        val all = cfg.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val (near, far) = all.partition { isLocalNetwork(it) }
        val ordered = if (play && vpnUp()) far + near else near + far
        var last: IOException? = null
        for ((i, address) in ordered.withIndex()) {
            if (generation != mine) throw IOException("Stopped")
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(address, cfg.port), if (i == ordered.lastIndex) 6_000 else 3_000)
                return s
            } catch (e: IOException) {
                last = e
                try {
                    s.close()
                } catch (_: IOException) {
                }
            }
        }
        throw last ?: IOException("No address for the computer")
    }

    private fun write(out: OutputStream, queue: LinkedBlockingQueue<ByteArray>) {
        val ping = LinkOneShot.frame(JSONObject().put("t", "ping"))
        try {
            while (true) {
                // a quiet line still says something every few seconds, so each end knows the other is there
                out.write(queue.poll(PING_MS, TimeUnit.MILLISECONDS) ?: ping)
                while (true) out.write(queue.poll() ?: break)
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun pts(b: ByteArray): Long {
        var v = 0L
        for (i in 1..8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun vpnUp(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Exception) {
        false
    }

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

    /** Whether the phone is on mobile data and nothing better: the player keeps more sound in hand there. */
    fun onCell(): Boolean = network() == "cell"

    private companion object {
        const val FIRST_WAIT_MS = 200L
        const val LAST_WAIT_MS = 3_000L
        const val HELLO_TIMEOUT_MS = 6_000
        const val PLAY_TIMEOUT_MS = 3_000
        const val QUIET_TIMEOUT_MS = 12_000
        const val PING_MS = 4_000L
    }
}
