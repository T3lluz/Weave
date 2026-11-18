package com.t3lluz.weave.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SquircleTest {
    @Test
    fun theOutlineStaysInsideItsBoxAndTouchesEverySide() {
        val points = squircle(200f, 120f, 40f)
        assertTrue(points.all { (x, y) -> x in 0f..200f && y in 0f..120f })
        assertEquals(0f, points.minOf { it.first }, 1e-3f)
        assertEquals(200f, points.maxOf { it.first }, 1e-3f)
        assertEquals(0f, points.minOf { it.second }, 1e-3f)
        assertEquals(120f, points.maxOf { it.second }, 1e-3f)
    }

    @Test
    fun aCornerHugsItsCornerCloserThanACirclesArcWould() {
        // the point halfway round the top right corner of a 100 square with corners reaching 50
        val points = squircle(100f, 100f, 50f)
        val (x, y) = points[10]
        // a circle of radius 50 passes 14.6 from each side there; the squircle is nearer the corner
        assertEquals(100f - x, y, 1e-3f)
        assertTrue("was ${100f - x}", 100f - x < 12f)
    }

    @Test
    fun theSidesBetweenCornersAreStraight() {
        val points = squircle(300f, 100f, 30f)
        // the top side: from where the top left corner ends to where the top right begins
        assertEquals(270f to 0f, points.first())
        assertEquals(30f to 0f, points.last())
    }
}
