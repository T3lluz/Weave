package com.t3lluz.weave.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the slide between monitors has gone how far: what its haptic ticks are timed by. */
class SwipeTimingTest {

    @Test
    fun `the slide is halfway in 84 ms and there in about a quarter of a second`() {
        assertEquals(84f, AppMotion.Link.swipeMs(0.5f).toFloat(), 2f)
        assertEquals(266f, AppMotion.Link.swipeMs(0.97f).toFloat(), 3f)
        assertEquals(0, AppMotion.Link.swipeMs(0f))
    }

    @Test
    fun `equal stretches take longer and longer, as the slide slows`() {
        val at = (1..9).map { AppMotion.Link.swipeMs(it / 10f) }
        val gaps = at.zipWithNext { a, b -> b - a }
        assertTrue(at.toString(), at == at.sorted())
        assertTrue(gaps.toString(), gaps.drop(2).zipWithNext().all { (a, b) -> b >= a })
    }
}
