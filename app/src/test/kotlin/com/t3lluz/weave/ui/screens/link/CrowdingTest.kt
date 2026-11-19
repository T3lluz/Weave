package com.t3lluz.weave.ui.screens.link

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrowdingTest {
    private fun two(apartDp: Float, a: Float = 1f, b: Float = 1f, density: Float = 2.625f) =
        crowding(listOf(Offset(0f, 0f), Offset(apartDp * density, 0f), Offset.Zero), floatArrayOf(a, b, 0f), density)

    @Test
    fun fingersTouchingOrFarApartDoNotCrowd() {
        assertEquals(0f, two(40f), 1e-4f)
        assertEquals(0f, two(90f), 1e-4f)
        assertEquals(0f, two(230f), 1e-4f)
        assertEquals(0f, two(400f), 1e-4f)
    }

    @Test
    fun aThumbAndAHalfApartIsWhereTheyCrowdMost() {
        assertEquals(1f, two(150f), 1e-4f)
        assertTrue(two(115f) in 0.1f..0.9f)
        assertTrue(two(190f) in 0.1f..0.9f)
    }

    @Test
    fun aDentSpringingBackCrowdsOnlyAsMuchAsItIsStillPressed() {
        assertEquals(0.3f, two(150f, b = 0.3f), 1e-4f)
        assertEquals("past flat counts by how far", 0.2f, two(150f, b = -0.2f), 1e-4f)
        assertEquals("a finger that isn't there crowds nobody", 0f, two(150f, b = 0f), 1e-4f)
    }
}
