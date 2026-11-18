package com.t3lluz.weave.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A rectangle with a superellipse's corners: each eases into its sides instead of meeting them
 * at once, as an app icon's do. [corner] is how far along each side the curve reaches (never
 * past half the shorter side), so a square of twice the corner is a squircle through and
 * through, and a wider box keeps the same corners with straight runs between them.
 */
class SquircleShape(private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val reach = min(with(density) { corner.toPx() }, min(size.width, size.height) / 2f)
        val path = Path()
        squircle(size.width, size.height, reach).forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is SquircleShape && other.corner == corner
    override fun hashCode() = corner.hashCode()
}

/** How square a corner is: 2 is a circle's arc, higher hugs the corner. */
private const val POWER = 4.6

/** Enough straight pieces per corner that none shows at any size on a phone. */
private const val STEPS = 20

/**
 * The outline of a [width] by [height] box whose corners reach [reach] along each side, clockwise
 * from the left end of the top side.
 */
internal fun squircle(width: Float, height: Float, reach: Float): List<Pair<Float, Float>> {
    // one corner, top right, from the top side down to the right side, as distances from the box's corner
    val arc = (0..STEPS).map { i ->
        val t = i.toDouble() / STEPS * PI / 2
        val along = reach * (1.0 - sin(t).pow(2.0 / POWER))   // back from the corner along the top
        val down = reach * (1.0 - cos(t).pow(2.0 / POWER))    // and down the right side
        along.toFloat() to down.toFloat()
    }
    val turned = arc.reversed()
    return buildList {
        arc.forEach { (a, d) -> add(width - a to d) }                 // top right
        turned.forEach { (a, d) -> add(width - a to height - d) }     // bottom right
        arc.forEach { (a, d) -> add(a to height - d) }                // bottom left
        turned.forEach { (a, d) -> add(a to d) }                      // top left
    }
}
