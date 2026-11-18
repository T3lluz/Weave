package com.t3lluz.weave.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPointerTest {
    private fun pointer() = LocalPointer().apply {
        rttMs = 40
        onMonitor("A", 1000f, 500f)
    }

    @Test
    fun nothingIsDrawnUntilTheLaptopSaysWhereThePointerIs() {
        val p = pointer()
        p.sent(10f, 0f, 0)
        assertFalse(p.shown)
        p.report(0.5f, 0.5f, 100)
        assertTrue(p.shown)
        assertEquals(0.5f, p.x, 1e-4f)
    }

    @Test
    fun movesGoOnTopOfTheLastReportTheMomentTheyAreSent() {
        val p = pointer()
        p.report(0.5f, 0.5f, 1_000)
        p.sent(100f, 50f, 1_010)
        // gain 1: 100 units are 100 of the monitor's 1000 pixels
        assertEquals(0.6f, p.x, 1e-4f)
        assertEquals(0.6f, p.y, 1e-4f)
    }

    @Test
    fun aReportCountsTheMovesSentBeforeItWasTakenAndNoMore() {
        val p = pointer()
        p.report(0.5f, 0.5f, 1_000)
        p.sent(100f, 0f, 1_010)
        p.sent(100f, 0f, 1_100)
        // lands at 1_120: the moves had to get there and back (40 ms) and be looked for (about 23 ms),
        // so it counts the first move, not the second
        p.report(0.6f, 0.5f, 1_120)
        assertEquals(0.7f, p.x, 1e-4f)
        // once the finger stops, the pointer sits exactly where the laptop says
        p.report(0.7f, 0.5f, 1_400)
        assertEquals(0.7f, p.x, 1e-4f)
    }

    @Test
    fun theLaptopsAccelerationIsLearnt() {
        val p = pointer()
        p.report(0.3f, 0.5f, 1_000)
        var t = 1_000L
        var laptopX = 0.3f
        // the laptop moves the pointer twice as far as the phone sends
        repeat(12) {
            t += 33
            p.sent(10f, 0f, t)
            laptopX += 20f / 1000f
            p.report(laptopX, 0.5f, t + 60)
        }
        assertEquals(2f, p.gain, 0.2f)
    }

    @Test
    fun theEdgeHoldsItAndAnotherMonitorStartsOver() {
        val p = pointer()
        p.report(0.98f, 0.5f, 1_000)
        p.sent(500f, 0f, 1_010)
        assertEquals(1f, p.x, 1e-4f)
        p.onMonitor("B", 1920f, 1080f)
        assertFalse(p.shown)
        p.jump(0.25f, 0.75f)
        assertTrue(p.shown)
        assertEquals(0.25f, p.x, 1e-4f)
    }

    @Test
    fun aGainTheLaptopStatesIsTakenAsItIs() {
        val p = pointer()
        p.laptopGain(1.44f)
        p.report(0.5f, 0.5f, 1_000)
        p.sent(100f, 0f, 1_010)
        assertEquals(0.5f + 144f / 1000f, p.x, 1e-4f)
        // a report that moved further than the gain says doesn't change it
        p.report(0.9f, 0.5f, 1_200)
        assertEquals(1.44f, p.gain, 1e-4f)
    }

    @Test
    fun aMoveIsOnlyCountedOnceTheLaptopCanHaveSeenIt() {
        val p = pointer()
        p.report(0.5f, 0.5f, 1_000)
        p.sent(50f, 0f, 1_000)
        // back 30 ms later: too soon to include the move, so it is still drawn on top
        p.report(0.5f, 0.5f, 1_030)
        assertEquals(0.55f, p.x, 1e-4f)
    }
}
