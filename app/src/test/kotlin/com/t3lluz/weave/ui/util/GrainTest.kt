package com.t3lluz.weave.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainTest {
    private var clock = 0L
    private fun grain(chance: Float = 0.5f) = Grain(everyDp = 4f, strength = 0.1f, minGapMs = 10, chance = { chance }, now = { clock })

    @Test
    fun aTickComesEveryFewDpOfTravelAndNoneWhileTheFingerRests() {
        val g = grain()
        clock = 100
        assertNull(g.glide(2f))
        assertNull(g.glide(0f))
        assertEquals("the middle of what it may be", 0.1f, g.glide(2.5f)!!, 1e-4f)
        clock = 200
        assertNull("the next one is a grain away again", g.glide(3f))
        assertTrue(g.glide(1.5f) != null)
    }

    @Test
    fun aFastStrokeIsThinnedSoTheTicksDoNotRunTogether() {
        val g = grain()
        var ticks = 0
        // 2000 dp in 200 ms: five hundred grains asked for
        for (ms in 1..200) {
            clock = 1000L + ms
            if (g.glide(10f) != null) ticks++
        }
        assertTrue("about one every 10 ms, got $ticks", ticks in 15..21)
    }

    @Test
    fun noTwoGrainsAreQuiteAlike() {
        var roll = 0f
        val g = Grain(everyDp = 4f, strength = 0.1f, minGapMs = 0, chance = { roll }, now = { clock })
        roll = 0f
        clock = 10
        val faint = g.glide(5f)!!
        roll = 1f
        clock = 20
        val firm = g.glide(5f)!!
        assertTrue(faint < 0.1f && firm > 0.1f)
        assertTrue("never loud", firm <= 0.15f)
    }

    @Test
    fun aLiftStartsTheGrainAfresh() {
        val g = grain()
        clock = 50
        assertNull(g.glide(3.9f))
        g.lift()
        assertNull("not a hair's breadth from the last stroke's tick", g.glide(0.2f))
    }
}
