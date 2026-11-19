package com.t3lluz.weave.ui.screens.link

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the full-screen panel's bubble comes to rest, and which way the panel unfolds from there. */
class PanelFieldTest {

    /** A Pixel on its side: a bubble 150 px across, 40 px in from the edges. */
    private fun pixel(holes: List<Rect> = emptyList()) = PanelField(2400f, 1080f, bubble = 150f, inset = 40f, holes = holes, gap = 16f)

    @Test
    fun `a bubble let go comes to rest against the nearest edge`() {
        val f = pixel()
        assertEquals(Offset(40f, 500f), f.rest(Offset(300f, 500f)))
        assertEquals(Offset(2210f, 500f), f.rest(Offset(2000f, 500f)))
        assertEquals(Offset(1200f, 40f), f.rest(Offset(1200f, 300f)))
        assertEquals(Offset(1200f, 890f), f.rest(Offset(1200f, 700f)))
    }

    @Test
    fun `it stays on the screen however far the finger went`() {
        val f = pixel()
        assertEquals(Offset(40f, 40f), f.within(Offset(-500f, -500f)))
        assertEquals(Offset(2210f, 890f), f.within(Offset(9000f, 9000f)))
        assertEquals(Offset(2210f, 40f), f.rest(Offset(9000f, -9000f)))
    }

    @Test
    fun `it rests beside the camera's hole, not on it`() {
        // the hole halfway down the left edge
        val hole = Rect(30f, 490f, 130f, 590f)
        val f = pixel(listOf(hole))
        // let go a little over the middle of the hole: up off it
        assertEquals(Offset(40f, 490f - 16f - 150f), f.rest(Offset(60f, 440f)))
        // a little under: down off it
        assertEquals(Offset(40f, 590f + 16f), f.rest(Offset(60f, 500f)))
        // clear of it already: where it was let go
        assertEquals(Offset(40f, 100f), f.rest(Offset(60f, 100f)))
        // the other edge has no hole
        assertEquals(Offset(2210f, 500f), f.rest(Offset(2300f, 500f)))
    }

    @Test
    fun `the panel unfolds into the room it has`() {
        val f = pixel()
        val topRight = f.rest(Offset(2300f, 0f))
        assertTrue(f.opensLeft(topRight))
        assertFalse(f.opensUp(topRight))
        val bottomLeft = f.rest(Offset(0f, 1000f))
        assertFalse(f.opensLeft(bottomLeft))
        assertTrue(f.opensUp(bottomLeft))
    }

    @Test
    fun `along the top it rests only where the panel has room to unfold beside it`() {
        // a panel 1330 px wide on a screen 2400: no room either way from around the middle
        val f = PanelField(2400f, 1080f, bubble = 150f, inset = 40f, panel = 1330f)
        // just left of the middle: back to the last place it unfolds to the right from
        val left = f.rest(Offset(1100f, 0f))
        assertEquals(Offset(2400f - 40f - 1330f, 40f), left)
        assertFalse(f.opensLeft(left))
        // just right of it: on to the first place it unfolds to the left from
        val right = f.rest(Offset(1180f, 2000f))
        assertEquals(Offset(40f + 1330f - 150f, 890f), right)
        assertTrue(f.opensLeft(right))
        // where there is room already it stays where it was let go, and down the sides it always has
        assertEquals(Offset(600f, 40f), f.rest(Offset(600f, 0f)))
        assertEquals(Offset(40f, 500f), f.rest(Offset(0f, 500f)))
    }

    @Test
    fun `the place it was left is kept as a share of the screen, and comes back the same`() {
        val f = pixel()
        assertEquals(Offset(2210f, 40f), f.from(1f to 0f))
        val left = f.rest(Offset(0f, 700f))
        val (x, y) = f.kept(left)
        assertEquals(0f, x, 0.0001f)
        assertEquals(left, f.from(x to y))
        // a share from another build or a wrong one still lands on the screen
        assertEquals(Offset(2210f, 890f), f.from(7f to 7f))
    }
}
