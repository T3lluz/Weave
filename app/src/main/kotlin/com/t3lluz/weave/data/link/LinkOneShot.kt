package com.t3lluz.weave.data.link

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** How a shortcut run from outside the remote ended. */
sealed interface RunOutcome {
    data object Done : RunOutcome
    data class Failed(val why: String) : RunOutcome
}

/**
 * Runs a shortcut without the remote being open (the home screen widget): through the remote's
 * own line when it is up, else over a line of its own that says hello, does the steps and
 * hangs up. No picture, no sound, and no "connected" notice on the laptop.
 */
@Singleton
class LinkOneShot @Inject constructor(
    private val prefs: LinkPrefs,
    private val session: LinkSession,
) {
    suspend fun run(steps: List<LinkStep>): RunOutcome {
        // The remote's own line, when it has one or is about to: a second line from the same phone
        // would make the computer drop the first, and let go of whatever it held.
        var waited = 0
        while (session.state.value.status in COMING_UP && waited < SESSION_WAIT_MS) {
            delay(100)
            waited += 100
        }
        if (session.state.value.status == LinkStatus.Online) {
            session.runSteps(steps)
            return RunOutcome.Done
        }
        val cfg = prefs.config.value
        if (cfg.host.isBlank() || cfg.key.isBlank()) return RunOutcome.Failed("Pick a computer in Weave first.")
        val name = cfg.name.ifBlank { "the computer" }
        // the remote is waiting for the owner's yes there: a second line would only get in its way
        if (session.state.value.status == LinkStatus.Pending) return RunOutcome.Failed("$name hasn't let this phone in yet. Answer its question first.")
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = connect(cfg)
                socket.soTimeout = ANSWER_MS
                val out = socket.getOutputStream()
                send(
                    out,
                    JSONObject().put("t", "hello").put("token", prefs.tokenFor(cfg.key)).put("ticket", cfg.ticket)
                        .put("name", Build.MODEL).put("ver", 3).put("cursor", "laptop").put("quiet", true),
                )
                when (val answer = awaitWelcome(DataInputStream(socket.getInputStream().buffered()))) {
                    "welcome" -> Unit
                    "pending" -> return@withContext RunOutcome.Failed("Open Weave once so $name can let this phone in.")
                    "denied" -> return@withContext RunOutcome.Failed("$name didn't let this phone in.")
                    else -> return@withContext RunOutcome.Failed(answer)
                }
                paceSteps(steps) { step -> stepMessage(step)?.let { send(out, it) } }
                // the laptop reads the last step before it sees the line close
                delay(LINGER_MS)
                RunOutcome.Done
            } catch (_: IOException) {
                RunOutcome.Failed("Couldn't reach $name.")
            } finally {
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /** The first of the laptop's addresses that answers; quicker to give up than the remote, a tap is waiting. */
    private fun connect(cfg: LinkConfig): Socket {
        val candidates = cfg.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var last: IOException? = null
        for (address in candidates) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, cfg.port), CONNECT_MS)
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

    private fun send(out: OutputStream, o: JSONObject) {
        out.write(frame(o))
        out.flush()
    }

    /** What the laptop says to the hello: "welcome", "pending", "denied", or its own words for why not. */
    private fun awaitWelcome(input: DataInputStream): String {
        var said = "The computer didn't answer."
        while (true) {
            val kind = input.readUnsignedByte()
            val size = input.readInt()
            if (size < 0 || size > 16_000_000) throw IOException("Bad frame")
            val body = ByteArray(size)
            input.readFully(body)
            if (kind != LinkProtocol.JSON) continue
            val m = JSONObject(String(body))
            when (val t = m.optString("t")) {
                "welcome", "pending", "denied" -> return t
                // "No access to /dev/uinput": the line closes after it
                "toast" -> said = m.optString("msg", said)
            }
            if (said != "The computer didn't answer.") return said
        }
    }

    companion object {
        /** While the remote is on its way up, a shortcut waits this long for it before taking a line of its own. */
        const val SESSION_WAIT_MS = 3_000
        private val COMING_UP = setOf(LinkStatus.Connecting, LinkStatus.Reconnecting)
        const val CONNECT_MS = 4_000
        const val ANSWER_MS = 6_000
        const val LINGER_MS = 150L

        /** A JSON message as it goes on the wire: its kind, its length, itself. */
        internal fun frame(o: JSONObject): ByteArray {
            val body = o.toString().toByteArray()
            val frame = ByteArray(5 + body.size)
            frame[0] = LinkProtocol.JSON.toByte()
            frame[1] = (body.size ushr 24).toByte()
            frame[2] = (body.size ushr 16).toByte()
            frame[3] = (body.size ushr 8).toByte()
            frame[4] = body.size.toByte()
            body.copyInto(frame, 5)
            return frame
        }
    }
}
