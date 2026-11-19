package com.t3lluz.weave.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainTest {
    private var clock = 1_000L
    private fun grain(chance: () -> Float = { 0.5f }) = Grain(stepMs = 20, chance = chance, now = { clock })

    @Test
    fun aSteadyStrokeIsOneUnbrokenStretchNotAStringOfThem() {
        val g = grain()
        var starts = 0
        // a finger at 0.5 dp a millisecond, told of every 8 ms, for a second and a half
        repeat(187) {
            clock += 8
            if (g.glide(4f) != null) starts++
        }
        assertTrue("it starts, settles to its pace, and is left to play: $starts", starts in 1..2)
    }

    @Test
    fun aStretchIsRenewedJustBeforeItRunsOut() {
        val g = grain()
        var startedAt = 0L
        var renewedAfter = 0L
        repeat(400) {
            clock += 8
            if (g.glide(4f) != null) {
                if (startedAt != 0L && renewedAfter == 0L && clock - startedAt > 1_000) renewedAfter = clock - startedAt
                startedAt = clock
            }
        }
        val whole = Grain.STEPS * 20L
        assertTrue("before the end, not long before: $renewedAfter of $whole", renewedAfter in (whole - 60)..whole)
    }

    @Test
    fun aRestingFingerStartsNothingAndTheNextMoveStartsAfresh() {
        val g = grain()
        clock += 8
        assertNull("a tremble is not a stroke", g.glide(0.1f))
        assertNull(g.glide(0f))
        clock += 8
        assertNotNull(g.glide(3f))
        g.rested()
        clock += 300
        assertNull(g.glide(0.2f))
        clock += 8
        assertNotNull("it has to move on before it is felt again", g.glide(1f))
    }

    @Test
    fun aFasterStrokeIsStrongerAndStartsOverOnlyOnceItHasPlayedAWhile() {
        val g = grain()
        clock += 8
        val slow = g.glide(0.8f)!!.steps.maxOf { it.strength }
        var fast = 0f
        var changes = 0
        repeat(60) {
            clock += 8
            g.glide(14f)?.let { stretch ->
                fast = stretch.steps.maxOf { it.strength }
                changes++
            }
        }
        assertTrue("$slow < $fast", slow < fast)
        assertEquals("once to the faster pace, not at every step on the way there", 1, changes)
        assertTrue(fast <= Grain.STRENGTH.last())
    }

    @Test
    fun theGrainWandersAndStaysHigh() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        clock += 8
        val stretch = g.glide(3f)!!
        assertEquals(Grain.STEPS, stretch.steps.size)
        assertTrue(stretch.steps.map { it.strength }.toSet().size > 2)
        assertTrue(stretch.steps.all { it.sharp >= Grain.SHARP_FROM && it.sharp <= 1f })
        assertTrue(stretch.steps.all { it.strength > 0f })
    }
}
