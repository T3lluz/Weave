package com.t3lluz.weave.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainTest {
    private var clock = 1_000L
    private fun grain(chance: () -> Float = { 0.5f }) = Grain(tickMs = 12, chance = chance, now = { clock })

    @Test
    fun aMovingFingerGetsRunAfterRunWithNoGapBetweenThem() {
        val g = grain()
        var playingUntil = 0L
        var silent = 0L
        var runs = 0
        // a finger at 0.5 dp a millisecond, told of every 8 ms, for a second
        for (step in 0 until 125) {
            clock += 8
            val run = g.glide(4f)
            if (clock > playingUntil && run == null && playingUntil != 0L) silent += 8
            if (run != null) {
                runs++
                assertEquals(Grain.RUN, run.size)
                playingUntil = clock + run.sumOf { 12 + it.waitMs }
            }
        }
        assertTrue("about one run every forty ms, got $runs", runs in 20..32)
        assertTrue("never silent for longer than it takes to hear of the next move, was $silent ms in all", silent <= 16)
    }

    @Test
    fun aRestingFingerGetsNothing() {
        val g = grain()
        clock += 8
        assertNotNull(g.glide(3f))
        clock += 200
        assertNull("no move, no grain", g.glide(0f))
        assertNull("a tremble is not a stroke", g.glide(0.1f))
    }

    @Test
    fun aFasterStrokeIsFirmerAndNeitherIsLoud() {
        val slow = grain()
        clock += 8
        val faint = slow.glide(0.8f)!!.maxOf { it.firm }
        val fast = grain()
        var firm = 0f
        repeat(40) {
            clock += 8
            fast.glide(12f)?.let { run -> firm = run.maxOf { it.firm } }
        }
        assertTrue("$faint < $firm", faint < firm)
        assertTrue(firm <= Grain.FIRM)
        assertTrue(faint >= Grain.FAINT * 0.6f)
    }

    @Test
    fun noTwoTicksAreQuiteAlike() {
        var roll = 0f
        val g = grain { roll.also { roll = (roll + 0.37f) % 1f } }
        clock += 8
        val run = g.glide(3f)!!
        assertTrue(run.map { it.firm }.toSet().size > 1)
        assertTrue(run.all { it.waitMs in 0..Grain.GAP_MS.toInt() })
    }

    @Test
    fun aLiftStartsTheNextStrokeAtOnce() {
        val g = grain()
        clock += 8
        assertNotNull(g.glide(3f))
        clock += 8
        assertNull("the run still plays", g.glide(3f))
        g.lift()
        clock += 8
        assertNotNull(g.glide(3f))
    }
}
