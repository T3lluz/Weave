package com.t3lluz.weave.ui.screens.link

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollStripTest {
    @Test
    fun aNotchIsWholeTurnsAwayEitherWay() {
        val d = Detents(10f)
        // it rests between notches: half a notch either way passes one
        assertEquals(0, d.turn(4f))
        assertEquals(1, d.turn(2f))
        assertEquals(0, d.turn(8f))
        assertEquals(1, d.turn(3f))
        // back the other way, over the same notch again
        assertEquals(1, d.turn(-4f))
    }

    @Test
    fun aQuickTurnCountsEveryNotchItPassed() {
        val d = Detents(10f)
        assertEquals(3, d.turn(31f))
        assertEquals(3, d.turn(-30f))
    }

    @Test
    fun theClothGivesLessTheFurtherItIsPulledAndNeverPastItsGive() {
        // a little pull is followed nearly all the way
        assertEquals(0.97f, clothGive(1f, 15f), 0.01f)
        val half = clothGive(12f, 15f)
        val more = clothGive(24f, 15f)
        assert(more > half) { "pulled further, it follows further" }
        assert(more - half < half) { "but less for each dp than before" }
        assert(clothGive(500f, 15f) <= 15f)
        // either way
        assertEquals(-half, clothGive(-12f, 15f), 0.0001f)
    }
}
