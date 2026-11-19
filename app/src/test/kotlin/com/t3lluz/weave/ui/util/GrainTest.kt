package com.t3lluz.weave.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GrainTest {
    private var clock = 1_000L
    private fun grain(chance: () -> Float = { 0.5f }) = Grain(stepMs = 20, chance = chance, now = { clock })

    /** Moves [g] at [dpPerMs] for [ms], told of every 8 ms; the stretches it started (empty ones are cut-offs). */
    private fun stroke(g: Grain, dpPerMs: Float, ms: Int): List<Stretch> = buildList {
        repeat(ms / 8) {
            clock += 8
            g.glide(dpPerMs * 8)?.let(::add)
        }
    }

    @Test
    fun aSteadyStrokeIsOneUnbrokenStretch() {
        val g = grain()
        assertEquals("started once and left to play: every restart is a hitch", 1, stroke(g, 0.6f, 2_000).size)
    }

    @Test
    fun aFasterFingerGetsAStrongerAndHigherGrain() {
        // (past the first moments, in which each eases in)
        val slow = stroke(grain(), 0.1f, 400).single().steps.drop(Grain.EASE_STEPS)
        val easy = stroke(grain(), 0.6f, 400).last().steps.drop(Grain.EASE_STEPS)
        val fast = stroke(grain(), 2.5f, 400).last().steps.drop(Grain.EASE_STEPS)
        assertTrue(slow.maxOf { it.strength } < easy.minOf { it.strength })
        assertTrue(easy.maxOf { it.strength } < fast.minOf { it.strength })
        assertTrue("low and dull when slow, a high hiss when fast", slow.maxOf { it.sharp } < fast.minOf { it.sharp })
        assertTrue("never more than a hiss", fast.maxOf { it.strength } <= Grain.STRONGEST + 1e-5f)
    }

    @Test
    fun aCreepingFingerGetsNothingAndOneThatSlowsToACreepLosesIt() {
        val g = grain()
        assertTrue("too slow for the grain to be felt at all", stroke(g, 0.02f, 600).isEmpty())
        g.rested()
        clock += 500
        val started = stroke(g, 0.6f, 500)
        assertEquals(1, started.size)
        val slowed = stroke(g, 0.015f, 600)
        assertTrue("cut off as it creeps: ${slowed.map { it.steps.size }}", slowed.isNotEmpty() && slowed.last().steps.isEmpty())
        assertFalse(g.playing)
    }

    @Test
    fun aPaceThatWaversAtABorderDoesNotStartOverAndOver() {
        val g = grain()
        var starts = 0
        // right on the border between two paces, a little over and a little under by turns, for three seconds
        repeat(375) { step ->
            clock += 8
            val dpPerMs = if (step % 2 == 0) 0.34f else 0.26f
            if (g.glide(dpPerMs * 8) != null) starts++
        }
        assertTrue("one pace is picked and kept: $starts starts", starts <= 2)
    }

    @Test
    fun aRealChangeOfPaceStartsTheNextGrainOnceAndNotAtOnce() {
        val g = grain()
        val before = stroke(g, 0.1f, 700).single().steps
        val after = stroke(g, 2.5f, 900)
        assertEquals("straight to the fast grain, not through the pace between", 1, after.size)
        val steps = after[0].steps
        // it takes over from where the slow one was, and gets to its own over a few steps
        assertTrue(abs(steps[0].strength - before.last().strength) < 0.012f)
        assertTrue(steps[Grain.EASE_STEPS].strength > before.last().strength * 1.4f)
        val biggest = steps.take(Grain.EASE_STEPS + 2).zipWithNext { a, b -> abs(a.strength - b.strength) }.max()
        assertTrue("no step on the way: $biggest", biggest < 0.012f)
    }

    @Test
    fun aStretchIsRenewedJustBeforeItRunsOut() {
        val g = grain()
        var startedAt = 0L
        var renewedAfter = 0L
        repeat(900) {
            clock += 8
            if (g.glide(4.8f) != null) {
                if (startedAt != 0L && renewedAfter == 0L) renewedAfter = clock - startedAt
                startedAt = clock
            }
        }
        val whole = Grain.STEPS * 20L
        assertTrue("before the end, not long before: $renewedAfter of $whole", renewedAfter in (whole - 60)..whole)
    }

    @Test
    fun aStrokeStartsOnlyOnceItsPaceIsKnownAndAfreshAfterARest() {
        val g = grain()
        clock += 8
        assertNull("the very first move says nothing about pace", g.glide(5f))
        val began = stroke(g, 0.6f, 80)
        assertEquals("within a few moves", 1, began.size)
        g.rested()
        clock += 300
        assertNull("and not on the first move of the next stroke either", g.glide(5f))
    }

    @Test
    fun theGrainDriftsInShortSteps() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        val all = stroke(g, 0.6f, 200).single().steps
        assertEquals(Grain.STEPS, all.size)
        val steps = all.drop(Grain.EASE_STEPS)
        assertTrue("it does drift", steps.map { it.strength }.toSet().size > 5)
        val mean = steps.map { it.strength }.average().toFloat()
        val biggest = steps.zipWithNext { a, b -> abs(a.strength - b.strength) }.max()
        assertTrue("barely: $biggest of $mean", biggest <= mean * 0.12f)
        assertTrue("and stays near its pace's own strength", steps.all { abs(it.strength - mean) <= mean * 0.16f })
        assertTrue("a stroke starts faint and comes up to it", all.first().strength < mean * 0.75f)
    }
}
