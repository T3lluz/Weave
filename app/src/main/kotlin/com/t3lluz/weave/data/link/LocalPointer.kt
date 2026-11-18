package com.t3lluz.weave.data.link

import kotlin.math.hypot

/**
 * The pointer as the phone draws it, ahead of the picture (as TeamViewer and Parsec do): the
 * laptop's last word on where it is, plus the moves sent since that it can't have counted yet.
 *
 * The laptop reports the pointer about 30 times a second while it moves ([report], the "cur"
 * message: 0..1 of the shown monitor), and each report is roughly half a round trip and half a
 * polling interval old when it lands. Moves sent before then are already in it; the ones after
 * are added on top, scaled by [gain], the laptop's pixels per unit of the phone's moves. That
 * gain isn't fixed (Hyprland puts its own pointer acceleration on tandem's mouse), so it is
 * learnt from how far the pointer really went for the moves that report covers. When the finger
 * stops, every move is counted and the pointer sits exactly where the laptop says.
 *
 * Moves come from the UI thread and reports from the link's reading thread; [x] and [y] are read
 * once a frame by the overlay.
 */
class LocalPointer {
    /** Where to draw it, 0..1 of the shown monitor. */
    @Volatile var x = 0.5f
        private set
    @Volatile var y = 0.5f
        private set
    /** Whether there is anywhere to draw it: a report on this monitor has come, and it is on it. */
    @Volatile var shown = false
        private set
    /** Bumps with each change, so a drawing loop can skip frames where nothing moved. */
    @Volatile var version = 0L
        private set

    /** Laptop pixels per unit of the phone's moves, learnt as it goes. */
    var gain = 1f
        private set

    private class Move(val at: Long, val dx: Float, val dy: Float)

    private val pending = ArrayDeque<Move>()
    private var known = false
    private var reportX = 0.5f
    private var reportY = 0.5f
    private var monW = 1f
    private var monH = 1f
    private var monitor: String? = null

    /** The link's round trip, for how old a report is when it lands. */
    @Volatile var rttMs = 40

    /** The shown monitor changed (or the picture started): nothing is known about it yet. */
    @Synchronized
    fun onMonitor(name: String?, width: Float, height: Float) {
        if (name == monitor && width == monW && height == monH) return
        monitor = name
        monW = width.coerceAtLeast(1f)
        monH = height.coerceAtLeast(1f)
        known = false
        pending.clear()
        publish(0.5f, 0.5f, false)
    }

    /** A move sent to the laptop, in the phone's units. */
    @Synchronized
    fun sent(dx: Float, dy: Float, now: Long) {
        if (!known) return
        pending.addLast(Move(now, dx, dy))
        // nothing older than a second can still be outstanding
        while (pending.size > 1 && now - pending.first().at > 1_000) pending.removeFirst()
        predict()
    }

    /** A tap on the picture put the pointer there. */
    @Synchronized
    fun jump(px: Float, py: Float) {
        if (monitor == null) return
        pending.clear()
        known = true
        reportX = px
        reportY = py
        publish(px, py, true)
    }

    /** Where the laptop says the pointer is, 0..1 of [name], landing at [now]. */
    @Synchronized
    fun report(rx: Float, ry: Float, now: Long) {
        val age = (rttMs / 2 + POLL_AGE_MS).coerceIn(10, 300)
        val cutoff = now - age
        // the moves this report already counts
        var sx = 0f
        var sy = 0f
        while (pending.isNotEmpty() && pending.first().at <= cutoff) {
            val m = pending.removeFirst()
            sx += m.dx
            sy += m.dy
        }
        if (known) learn(rx - reportX, ry - reportY, sx, sy, rx, ry)
        known = true
        reportX = rx
        reportY = ry
        predict()
    }

    /** How far the pointer went for the moves the report covers: the gain, eased toward it. */
    private fun learn(dxN: Float, dyN: Float, sx: Float, sy: Float, rx: Float, ry: Float) {
        val sent = hypot(sx, sy)
        val went = hypot(dxN * monW, dyN * monH)
        // too little to tell, or held up by the monitor's edge
        if (sent < 6f || went < 3f) return
        if (rx < 0.01f || rx > 0.99f || ry < 0.01f || ry > 0.99f) return
        val seen = (went / sent).coerceIn(GAIN_MIN, GAIN_MAX)
        gain += (seen - gain) * 0.25f
    }

    private fun predict() {
        var sx = 0f
        var sy = 0f
        for (m in pending) {
            sx += m.dx
            sy += m.dy
        }
        val px = reportX + sx * gain / monW
        val py = reportY + sy * gain / monH
        // past the monitor's edge the laptop's pointer stops (or goes to the next monitor, which
        // the picture follows): draw it at the edge
        publish(px.coerceIn(0f, 1f), py.coerceIn(0f, 1f), known)
    }

    private fun publish(px: Float, py: Float, show: Boolean) {
        x = px
        y = py
        shown = show
        version++
    }

    companion object {
        /** tandem looks at the pointer every 33 ms: a report is half of that old on average when sent. */
        const val POLL_AGE_MS = 17
        const val GAIN_MIN = 0.2f
        const val GAIN_MAX = 10f
    }
}
