package com.t3lluz.weave.data.link

import kotlin.math.abs

/**
 * The edge between two of the laptop's monitors, held: the pointer stops at it, and a little more
 * push lets it through, as the scroll strip comes free of the cloth (Fredde asked for the same
 * there, 2026-11-21). Without it a pointer reaching for a window's edge slips onto the next
 * monitor and the picture swipes away under the thumb.
 *
 * Moves that would carry the pointer over an edge with a monitor beyond it are cut at the edge
 * ([filter]); what is cut is counted, and once it comes to [HOLD] (in the phone's units of move,
 * counting for at most [MOST_A_MS] a millisecond, so a quick move is held as a slow one is:
 * Fredde asked for that) the move goes as it is, and the pointer crosses. Fredde asked for it to feel like it has to be pulled free: it
 * takes some 60 to 100 dp of an unhurried finger. What was held back is not sent: the pointer enters the next
 * monitor at its edge, where it was aimed, not a stretch inside it. Along the edge it moves
 * freely while it is held. Going back from the edge, or pausing, lets go of the push.
 *
 * On a laptop with one screen the same edge leads to the next workspace instead, left and right
 * (Fredde asked for it, 2026-11-27): held and let go of the same way, but the pointer is not let
 * through. [takeFreedSpace] names the workspace, and the session switches to it.
 *
 * Only where the phone knows where the pointer is (it draws it, [LocalPointer]) and the monitors
 * say where they lie. Moves come from the UI thread; the screen reads the fields once a frame.
 */
class MonitorEdge {
    /** How far the push has gone toward letting the pointer through, 0 to 1. */
    @Volatile var push = 0f
        private set

    /** Which way it is pushed: -1, 0 or 1 across and down. */
    @Volatile var dirX = 0
        private set
    @Volatile var dirY = 0
        private set

    /** The monitor beyond the edge, by name, or the workspace, by its key. */
    @Volatile var beyond: String? = null
        private set

    /** What lies beyond is a workspace of the one screen, not a monitor. */
    @Volatile var toSpace = false
        private set

    /** The workspace the push just went through to, for the session to switch to; once. */
    private var freedSpace: String? = null

    fun takeFreedSpace(): String? = freedSpace.also { freedSpace = null }

    /** When (uptime ms) it was last pushed, and when it last let the pointer through. */
    @Volatile var pushedAt = 0L
        private set
    @Volatile var freedAt = 0L
        private set

    /** What the screen feels: the pointer met the edge, the push passed one of [CREAKS] (the how-manyth, from 0), it let through. */
    enum class Felt { MET, CREAK, FREED }

    /** Told on the UI thread as each happens, with the creak's number (0 otherwise). */
    @Volatile var onFelt: ((Felt, Int) -> Unit)? = null

    private var held = 0f
    private var creaked = 0

    /** Lets go of the push: the finger went back, or the monitor changed under it. */
    fun reset() {
        held = 0f
        creaked = 0
        push = 0f
    }

    /**
     * The part of a move of ([dx], [dy]) to send, with the pointer at ([x], [y]) on [on] (0..1 of
     * it), [gain] laptop pixels to a unit of move, among [monitors], at [now]. [spaces] are the keys
     * of the workspaces left and right of the one shown, on a laptop with one screen.
     */
    fun filter(
        dx: Float, dy: Float, x: Float, y: Float, gain: Float, on: LinkMonitor, monitors: List<LinkMonitor>, now: Long,
        spaces: Pair<String?, String?>? = null,
    ): Pair<Float, Float> {
        val w = on.layoutWidth.toFloat()
        val h = on.layoutHeight.toFloat()
        if (w <= 0f || h <= 0f || gain <= 0f) {
            reset()
            return dx to dy
        }
        val px = x * w
        val py = y * h
        val nx = px + dx * gain
        val ny = py + dy * gain
        // where it is held: a few pixels inside the edge, since the laptop keeps the pointer to a
        // fraction of a pixel and says where it is only to the whole one
        val right = w - 1f - MARGIN
        val bottom = h - 1f - MARGIN
        // how far past each edge the move would take it, laptop pixels
        val overX = when {
            nx > right -> nx - right
            nx < MARGIN -> MARGIN - nx
            else -> 0f
        }
        val overY = when {
            ny > bottom -> ny - bottom
            ny < MARGIN -> MARGIN - ny
            else -> 0f
        }
        if (overX <= 0f && overY <= 0f) {
            // back from the edge it was pushed against: the push is let go
            if (push > 0f && ((dirX != 0 && dx * dirX < 0f) || (dirY != 0 && dy * dirY < 0f))) reset()
            return dx to dy
        }
        // What lies just past each edge it goes past, where it would cross: the edge it goes furthest
        // past first, then the other (into a corner, the one with a monitor past it holds). Which
        // edge, by the edge and not by where it lands: inside the margin, a move past the left
        // edge still lands right of 0.
        fun beyondX(): LinkMonitor? = if (overX <= 0f) null else monitorUnder(
            monitors, LinkPointer(on.x + (if (nx > right) w + 1f else -1f), on.y + ny.coerceIn(0f, h - 1f)),
        )?.takeIf { it.name != on.name }
        fun beyondY(): LinkMonitor? = if (overY <= 0f) null else monitorUnder(
            monitors, LinkPointer(on.x + nx.coerceIn(0f, w - 1f), on.y + (if (ny > bottom) h + 1f else -1f)),
        )?.takeIf { it.name != on.name }
        val first = overX >= overY
        val pastX = beyondX()
        val pastY = beyondY()
        val across = if (first) pastX != null || pastY == null else pastY == null && pastX != null
        val next = if (across) pastX else pastY
        // past the side of the only screen: the workspace that way, if it has one
        val space = if (next == null && across && spaces != null) (if (nx > right) spaces.second else spaces.first) else null
        val nextName = next?.name ?: space
        if (nextName == null) {
            // nothing there: the laptop holds the pointer at the edge itself
            reset()
            return dx to dy
        }
        val sideX = if (across) (if (nx > right) 1 else -1) else 0
        val sideY = if (across) 0 else (if (ny > bottom) 1 else -1)
        // a push that paused, or one against another edge, starts again
        if (now - pushedAt > STALE_MS || sideX != dirX || sideY != dirY || nextName != beyond) {
            reset()
            dirX = sideX
            dirY = sideY
            beyond = nextName
            toSpace = next == null
        }
        val met = held == 0f
        // the push counts for at most so much a millisecond, so a quick move is held as surely as a slow one
        val since = if (met) 16L else (now - pushedAt).coerceIn(1L, 50L)
        held += ((if (across) overX else overY) / gain).coerceAtMost(MOST_A_MS * since)
        pushedAt = now
        if (met) onFelt?.invoke(Felt.MET, 0)
        if (held >= HOLD) {
            // Through: this move goes, and what was held back stays behind. It takes the pointer at
            // least [OVER] past the edge, however small it is: it was held [MARGIN] inside, and a
            // slow finger's move is less than that.
            reset()
            freedAt = now
            onFelt?.invoke(Felt.FREED, 0)
            if (toSpace) {
                // the pointer stays on this screen: the session moves it to the edge it enters by
                freedSpace = nextName
                return 0f to 0f
            }
            return if (across) {
                val past = ((if (sideX > 0) w - 1f + OVER else -OVER) - px) / gain
                (if (sideX > 0) maxOf(dx, past) else minOf(dx, past)) to dy
            } else {
                val past = ((if (sideY > 0) h - 1f + OVER else -OVER) - py) / gain
                dx to (if (sideY > 0) maxOf(dy, past) else minOf(dy, past))
            }
        }
        push = held / HOLD
        while (creaked < CREAKS.size && push >= CREAKS[creaked]) {
            onFelt?.invoke(Felt.CREAK, creaked)
            creaked++
        }
        // up to the edge on the held axis, all of it along the edge
        return if (across) {
            val to = (if (sideX > 0) right else MARGIN) - px
            (if (to * sideX > 0f) to / gain else 0f) to dy
        } else {
            val to = (if (sideY > 0) bottom else MARGIN) - py
            dx to (if (to * sideY > 0f) to / gain else 0f)
        }
    }

    companion object {
        /**
         * How much push lets the pointer through, in the phone's units of move: some 60 to 100 dp
         * of an unhurried finger at the usual pointer speed, and a fifth of a second at the least
         * for a quick one ([MOST_A_MS]).
         */
        const val HOLD = 120f

        /** The most a millisecond of push counts toward [HOLD]: a push takes at least about a fifth of a second, however fast the finger. */
        const val MOST_A_MS = 0.55f

        /** How far inside the edge the pointer is held, laptop pixels. */
        const val MARGIN = 3f

        /** How far past the edge the move that lets it through takes it, at the least. */
        const val OVER = 2f

        /** The give creaks as the push passes these shares of the way, each firmer: the strip's own two. */
        val CREAKS = floatArrayOf(0.4f, 0.75f)

        /** A push that rests this long is let go of. */
        const val STALE_MS = 450L

        /** Is the push still being made, for the screen: pushed within this long. */
        const val PUSHING_MS = 160L
    }
}

/** The edge is being pushed at [now]: the screen shows the peek while it is. */
internal fun MonitorEdge.pushing(now: Long): Boolean = push > 0f && now - pushedAt < MonitorEdge.PUSHING_MS && abs(dirX) + abs(dirY) > 0

/**
 * Where the pointer is on the whole desk, in the layout's pixels, wherever the picture is: the
 * laptop's own word about it ("ptr", about 30 a second, asked for with the picture on too while
 * the edges hold) plus the moves sent since that it can't have counted yet, as [LocalPointer]
 * guesses for a laptop that doesn't number them. Kept on the desk as Hyprland keeps the pointer
 * there: a point past every monitor goes to the nearest point of one.
 */
class DeskPointer {
    private class Move(val at: Long, val dx: Float, val dy: Float)

    private val pending = ArrayDeque<Move>()
    private var known = false
    private var rx = 0f
    private var ry = 0f

    /** The link's round trip, for how old a report is when it lands. */
    @Volatile var rttMs = 40

    @Synchronized
    fun report(x: Float, y: Float, now: Long) {
        val age = (rttMs + LocalPointer.POLL_AGE_MS + LocalPointer.INJECT_MS).coerceIn(15, 400)
        while (pending.isNotEmpty() && pending.first().at <= now - age) pending.removeFirst()
        known = true
        rx = x
        ry = y
    }

    @Synchronized
    fun sent(dx: Float, dy: Float, now: Long) {
        if (!known) return
        pending.addLast(Move(now, dx, dy))
        while (pending.size > 1 && now - pending.first().at > 2_000) pending.removeFirst()
    }

    /** Where it is now, [gain] laptop pixels to a unit of move, on [monitors]; null until the laptop has said. */
    @Synchronized
    fun at(gain: Float, monitors: List<LinkMonitor>): LinkPointer? {
        if (!known) return null
        var sx = 0f
        var sy = 0f
        for (m in pending) {
            sx += m.dx
            sy += m.dy
        }
        return onDesk(LinkPointer(rx + sx * gain, ry + sy * gain), monitors)
    }

    /** The laptop is putting the pointer at ([x], [y]) on the desk: it is there from now on. */
    @Synchronized
    fun jump(x: Float, y: Float) {
        pending.clear()
        known = true
        rx = x
        ry = y
    }

    @Synchronized
    fun forget() {
        known = false
        pending.clear()
    }
}

/** [p], or the nearest point of [monitors] to it when it is on none: where Hyprland would keep the pointer. */
internal fun onDesk(p: LinkPointer, monitors: List<LinkMonitor>): LinkPointer {
    val laid = monitors.filter { it.layoutWidth > 0 && it.layoutHeight > 0 }
    if (laid.isEmpty() || monitorUnder(laid, p) != null) return p
    return laid.map { m ->
        LinkPointer(p.x.coerceIn(m.x.toFloat(), m.x + m.layoutWidth - 1f), p.y.coerceIn(m.y.toFloat(), m.y + m.layoutHeight - 1f))
    }.minBy { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) }
}
