package com.t3lluz.weave.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGridTest {
    // what a Pixel 10 gives a 4 x 2 widget, upright
    private fun rows(n: Int, w: Float = 373f, h: Float = 200f) = widgetLayout(n, w, h).rows

    @Test
    fun aFourByTwoWidgetFillsItselfWithHoweverManyWerePicked() {
        assertEquals(listOf(2), rows(2))
        assertEquals(listOf(3), rows(3))
        assertEquals(listOf(2, 2), rows(4))
        assertEquals(listOf(3, 2), rows(5))
        assertEquals(listOf(3, 3), rows(6))
        assertEquals(listOf(4, 3), rows(7))
        assertEquals(listOf(4, 4), rows(8))
    }

    @Test
    fun fewerShortcutsGetBiggerPictures() {
        val two = widgetLayout(2, 373f, 200f)
        val eight = widgetLayout(8, 373f, 200f)
        assertTrue(two.faceDp > eight.faceDp)
        assertEquals(28, eight.faceDp)
        assertTrue(two.bigText && !eight.bigText)
        assertTrue(two.labels && eight.labels)
    }

    @Test
    fun aOneRowWidgetKeepsOneRowAndDropsNamesWhenTilesGetNarrow() {
        // 4 x 1 on a Pixel: 373 x 92 dp
        assertEquals(listOf(4), rows(4, 373f, 92f))
        assertTrue(widgetLayout(4, 373f, 92f).labels)
        val eight = widgetLayout(8, 373f, 92f)
        assertEquals(8, eight.rows.sum())
        assertFalse("eight in one row are 40 dp wide: no room for names", eight.labels)
    }

    @Test
    fun aTallNarrowWidgetStacksThem() {
        // 2 x 2: 180 x 200 dp
        assertEquals(listOf(1, 1), rows(2, 180f, 200f))
        assertEquals(listOf(2, 2), rows(4, 180f, 200f))
    }

    @Test
    fun everyShortcutPickedHasAPlace() {
        for (n in 1..MAX_SHORTCUTS) for ((w, h) in listOf(373f to 200f, 373f to 92f, 180f to 200f, 180f to 92f, 373f to 416f)) {
            val layout = widgetLayout(n, w, h)
            assertEquals("$n in $w x $h", n, layout.rows.sum())
            assertTrue(layout.rows.all { it > 0 })
        }
    }

    @Test
    fun theOnesPickedShowInTheOrderPickedAndDeletedOnesDropOut() {
        val all = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a"), widgetPicks(all, listOf("c", "gone", "a")) { it })
    }

    @Test
    fun withNonePickedTheShelfsFirstEightFillTheWidget() {
        val all = (1..11).map { "s$it" }
        assertEquals(all.take(8), widgetPicks(all, emptyList()) { it })
        // every pick since deleted: the shelf again, not an empty widget
        assertEquals(all.take(8), widgetPicks(all, listOf("gone")) { it })
    }
}
