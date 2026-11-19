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
    fun theGrainIsTheSameAtAnyPace() {
        // (past the first moments, in which each comes up)
        val slow = stroke(grain(), 0.1f, 400).single().steps.drop(Grain.EASE_STEPS)
        val fast = stroke(grain(), 2.5f, 400).single().steps.drop(Grain.EASE_STEPS)
        assertEquals("a slow finger and a fast one feel the same cloth", slow, fast)
        assertTrue("and never more than a faint one", fast.maxOf { it.strength } <= Grain.STRONGEST + 1e-5f)
        assertTrue("fainter than the faintest it used to be", Grain.STRONGEST < 0.05f)
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
    fun aFingerWaveringAtACreepDoesNotStartOverAndOver() {
        val g = grain()
        var starts = 0
        // right on the creep's border, a little over and a little under by turns, for three seconds
        repeat(375) { step ->
            clock += 8
            val dpPerMs = if (step % 2 == 0) 0.058f else 0.044f
            if (g.glide(dpPerMs * 8) != null) starts++
        }
        assertTrue("it plays or it doesn't: $starts starts", starts <= 1)
    }

    @Test
    fun aChangeOfPaceChangesNothing() {
        val g = grain()
        assertEquals(1, stroke(g, 0.1f, 700).size)
        assertTrue("speeding up starts nothing new", stroke(g, 2.5f, 900).isEmpty())
        assertTrue(g.playing)
    }

    @Test
    fun aStretchIsRenewedJustBeforeItRunsOutAndCarriesOn() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        var startedAt = 0L
        var renewedAfter = 0L
        val stretches = ArrayList<Stretch>()
        repeat(900) {
            clock += 8
            g.glide(4.8f)?.let {
                stretches += it
                if (startedAt != 0L && renewedAfter == 0L) renewedAfter = clock - startedAt
                startedAt = clock
            }
        }
        val whole = Grain.STEPS * 20L
        assertTrue("before the end, not long before: $renewedAfter of $whole", renewedAfter in (whole - 60)..whole)
        // the next stretch takes up where the last one was: no coming up again, no step
        val seam = abs(stretches[1].steps.first().strength - stretches[0].steps.last().strength)
        assertTrue("no step at the seam: $seam", seam <= Grain.STRENGTH * Grain.WANDER * Grain.DRIFT + 1e-6f)
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
    fun theGrainWandersButNeverFar() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        val all = stroke(g, 0.6f, 200).single().steps
        assertEquals(Grain.STEPS, all.size)
        val steps = all.drop(Grain.EASE_STEPS)
        assertTrue("it is uneven, as a surface is", steps.map { it.strength }.toSet().size > 5)
        assertTrue("and its pitch moves about", steps.maxOf { it.sharp } - steps.minOf { it.sharp } > 0.1f)
        val biggest = steps.zipWithNext { a, b -> abs(a.strength - b.strength) }.max()
        assertTrue("each moment a short way from the last: $biggest", biggest <= Grain.STRENGTH * 0.14f)
        assertTrue("never far from its own strength", steps.all { abs(it.strength - Grain.STRENGTH) <= Grain.STRENGTH * Grain.WANDER + 1e-6f })
        assertTrue("a stroke starts faint and comes up to it", all.first().strength < Grain.STRENGTH * 0.75f)
    }
}
