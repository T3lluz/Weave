package com.t3lluz.weave.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorEdgeTest {
    // two monitors side by side, 100 by 50 each, and nothing past the right one
    private val a = LinkMonitor("A", "Left", 100, 50, x = 0, y = 0, layoutWidth = 100, layoutHeight = 50)
    private val b = LinkMonitor("B", "Right", 100, 50, x = 100, y = 0, layoutWidth = 100, layoutHeight = 50)
    private val desk = listOf(a, b)
    private var clock = 1_000L
    private val felt = mutableListOf<MonitorEdge.Felt>()
    private fun edge() = MonitorEdge().also { it.onFelt = { f, _ -> felt += f } }

    @Test
    fun theEdgeHoldsThePointerUntilItIsPushedThrough() {
        val e = edge()
        // 6 px short of where it is held (3 px inside the edge), 10 more: it stops there
        val (dx, dy) = e.filter(10f, 0f, 0.90f, 0.5f, 1f, a, desk, clock)
        assertEquals(6f, dx, 0.001f)
        assertEquals(0f, dy, 0.001f)
        assertEquals(listOf(MonitorEdge.Felt.MET), felt)
        assertEquals("B", e.beyond)
        assertEquals(1, e.dirX)
        // pushed on at the edge: nothing goes across until the push is spent
        // (once through, the pointer is past this monitor and its moves no longer come here)
        var through = false
        var pushes = 0
        while (!through && pushes < 60) {
            clock += 16
            pushes++
            val (x, _) = e.filter(10f, 0f, 0.96f, 0.5f, 1f, a, desk, clock)
            if (x == 10f) through = true else assertEquals(0f, x, 0.001f)
        }
        assertTrue("it lets the pointer through in the end", through)
        assertEquals(listOf(MonitorEdge.Felt.MET, MonitorEdge.Felt.CREAK, MonitorEdge.Felt.CREAK, MonitorEdge.Felt.FREED), felt)
    }

    @Test
    fun aQuickMoveIsHeldAsSurelyAsASlowOne() {
        val e = edge()
        // from the middle, a move that goes past the edge by more than the whole hold: it stops there all the same
        val (dx, _) = e.filter(200f, 0f, 0.5f, 0.5f, 1f, a, desk, clock)
        assertEquals(46f, dx, 0.001f)
        assertEquals(listOf(MonitorEdge.Felt.MET), felt)
        // and goes through after a little more pushing, quick moves or not
        var moves = 0
        var through = false
        while (!through && moves < 60) {
            clock += 8
            moves++
            through = e.filter(200f, 0f, 0.96f, 0.5f, 1f, a, desk, clock).first == 200f
        }
        assertTrue(through)
        assertTrue("a fifth of a second at the least: $moves moves of 8 ms", moves * 8 >= 200)
    }

    @Test
    fun theOnlyScreensSideLeadsToTheNextWorkspace() {
        val e = edge()
        val one = listOf(a)
        val spaces = "workspace:1" to "workspace:3"
        // held at the right side as at a monitor's edge, with the workspace that way beyond
        val (dx, _) = e.filter(10f, 0f, 0.90f, 0.5f, 1f, a, one, clock, spaces)
        assertEquals(6f, dx, 0.001f)
        assertEquals("workspace:3", e.beyond)
        assertTrue(e.toSpace)
        var freed: String? = null
        var pushes = 0
        while (freed == null && pushes < 60) {
            clock += 16
            pushes++
            // the pointer never goes past the side: the session switches the workspace instead
            assertEquals(0f, e.filter(10f, 0f, 0.96f, 0.5f, 1f, a, one, clock, spaces).first, 0.001f)
            freed = e.takeFreedSpace()
        }
        assertEquals("workspace:3", freed)
        assertEquals(MonitorEdge.Felt.FREED, felt.last())
        assertEquals(null, e.takeFreedSpace())
        // no workspace to the left, and none up or down: the laptop's own edge
        e.reset()
        clock += 1_000
        assertEquals(-10f, e.filter(-10f, 0f, 0.01f, 0.5f, 1f, a, one, clock, null to "workspace:3").first, 0.001f)
        assertEquals(10f, e.filter(0f, 10f, 0.5f, 0.97f, 1f, a, one, clock, spaces).second, 0.001f)
    }

    @Test
    fun anEdgeWithNothingPastItIsLeftToTheLaptop() {
        val e = edge()
        val (dx, _) = e.filter(10f, 0f, 0.98f, 0.5f, 1f, b, desk, clock)
        assertEquals(10f, dx, 0.001f)
        assertTrue(felt.isEmpty())
    }

    @Test
    fun heldAtTheEdgeItStillMovesAlongIt() {
        val e = edge()
        val (dx, dy) = e.filter(5f, 3f, 0.96f, 0.5f, 1f, a, desk, clock)
        assertEquals(0f, dx, 0.001f)
        assertEquals(3f, dy, 0.001f)
    }

    @Test
    fun goingBackOrPausingLetsGoOfThePush() {
        val e = edge()
        e.filter(30f, 0f, 0.96f, 0.5f, 1f, a, desk, clock)
        assertTrue(e.push > 0f)
        e.filter(-5f, 0f, 0.96f, 0.5f, 1f, a, desk, clock + 16)
        assertEquals(0f, e.push, 0.001f)
        // a push that rested starts over
        e.filter(30f, 0f, 0.96f, 0.5f, 1f, a, desk, clock + 32)
        val before = e.push
        e.filter(30f, 0f, 0.96f, 0.5f, 1f, a, desk, clock + 32 + MonitorEdge.STALE_MS + 1)
        assertEquals(before, e.push, 0.001f)
    }

    @Test
    fun theGainTurnsLaptopPixelsIntoTheFingersUnits() {
        val e = edge()
        // at 2 laptop px a unit, 3 units reach where it is held from 6 px short of it
        val (dx, _) = e.filter(10f, 0f, 0.90f, 0.5f, 2f, a, desk, clock)
        assertEquals(3f, dx, 0.001f)
    }

    @Test
    fun theMoveThatLetsItThroughAlwaysCrosses() {
        val e = edge()
        // a slow finger: moves far smaller than the margin it is held inside the edge
        var sent = 0f
        var x = 0.96f
        var moves = 0
        while (e.freedAt == 0L && moves < 400) {
            clock += 16
            moves++
            sent = e.filter(0.5f, 0f, x, 0.5f, 1f, a, desk, clock).first
        }
        // held at 96 of 100, the move through takes it to at least 101: on the next monitor
        assertTrue("it crosses: $sent", 96f + sent >= 100f)
        // and the other way
        val back = edge()
        var sentBack = 0f
        moves = 0
        while (back.freedAt == 0L && moves < 400) {
            clock += 16
            moves++
            sentBack = back.filter(-0.5f, 0f, 0.03f, 0.5f, 1f, b, desk, clock).first
        }
        assertTrue("it crosses back: $sentBack", 3f + sentBack < 0f)
    }

    @Test
    fun intoACornerTheEdgeWithAMonitorPastItHolds() {
        // one monitor under the left one, none past the left one's left side
        val c = LinkMonitor("C", "Below", 100, 50, x = 0, y = 50, layoutWidth = 100, layoutHeight = 50)
        val e = edge()
        // near the bottom left corner of A, going left more than down: left has nothing, below has C
        val (dx, dy) = e.filter(-20f, 10f, 0.05f, 0.9f, 1f, a, listOf(a, b, c), clock)
        assertEquals("C", e.beyond)
        assertEquals(1, e.dirY)
        assertEquals(-20f, dx, 0.001f)
        assertTrue("held at the bottom edge: $dy", 45f + dy <= 46f + 0.001f)
    }
}
