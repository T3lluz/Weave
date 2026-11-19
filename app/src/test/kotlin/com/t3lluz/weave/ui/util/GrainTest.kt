package com.t3lluz.weave.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GrainTest {
    private var clock = 1_000L
    private fun grain(chance: () -> Float = { 0.5f }) = Grain(stepMs = 20, chance = chance, now = { clock })

    @Test
    fun aStrokeIsOneUnbrokenStretchWhateverItsPace() {
        val g = grain()
        var starts = 0
        // slow, then fast, then slow again, told of every 8 ms, for three seconds
        repeat(375) { step ->
            clock += 8
            val dp = when (step / 60 % 3) { 0 -> 0.8f; 1 -> 14f; else -> 3f }
            if (g.glide(dp) != null) starts++
        }
        assertEquals("started once and left to play: every restart is a hitch", 1, starts)
    }

    @Test
    fun aStretchIsRenewedJustBeforeItRunsOut() {
        val g = grain()
        var startedAt = 0L
        var renewedAfter = 0L
        repeat(900) {
            clock += 8
            if (g.glide(4f) != null) {
                if (startedAt != 0L && renewedAfter == 0L) renewedAfter = clock - startedAt
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
    fun theGrainDriftsInShortStepsAndStaysFaintAndHigh() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        clock += 8
        val steps = g.glide(3f)!!.steps
        assertEquals(Grain.STEPS, steps.size)
        assertTrue("it does drift", steps.map { it.strength }.toSet().size > 5)
        assertTrue(steps.all { it.strength in Grain.STRENGTH * 0.77f..Grain.STRENGTH * 1.23f })
        assertTrue(steps.all { it.sharp in Grain.SHARP_FROM..1f })
        val biggest = steps.zipWithNext { a, b -> abs(a.strength - b.strength) }.max()
        assertTrue("never a jump: $biggest", biggest <= Grain.STRENGTH * Grain.DRIFT + 1e-5f)
    }
}
