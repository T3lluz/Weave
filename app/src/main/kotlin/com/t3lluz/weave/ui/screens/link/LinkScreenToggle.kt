package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.components.ToneTile
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The top row's tile for the laptop's screen, at its far right: a window with its top panel
 * (Lucide's panel-top), whose chevron points up while the screen shows (fold it away) and down
 * while it is folded (bring it back). The chevron bends through flat on its way from one to the
 * other, so the tile says which way the screen will go. It is lit while the screen shows.
 */
@Composable
internal fun ScreenToggle(shown: Boolean, shape: Shape, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FoldToggle(
        shown, if (shown) "Fold the laptop's screen away" else "Show the laptop's screen", LinkScreenTone, shape, onToggle, modifier,
    ) { u, stroke, ink ->
        drawRoundRect(ink, topLeft = Offset(3f * u, 3f * u), size = Size(18f * u, 18f * u), cornerRadius = CornerRadius(2f * u), style = stroke)
        drawLine(ink, Offset(3f * u, 9f * u), Offset(21f * u, 9f * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
        15f
    }
}

/**
 * The same for the shortcuts over the trackpad, next to it: a row of three tiles with the
 * chevron under them, up while they show (fold them away) and down while folded.
 */
@Composable
internal fun ShortcutsToggle(shown: Boolean, shape: Shape, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FoldToggle(
        shown, if (shown) "Fold the shortcuts away" else "Show the shortcuts", LinkShortcutTone, shape, onToggle, modifier,
    ) { u, _, ink ->
        for (i in 0..2) {
            drawRoundRect(
                ink, topLeft = Offset((2.5f + i * 6.75f) * u, 4f * u), size = Size(5.5f * u, 5.5f * u),
                cornerRadius = CornerRadius(1.6f * u),
            )
        }
        16.5f
    }
}

/**
 * A tile of the top row with a glyph on Lucide's 24-unit grid and a chevron under it that
 * bends from up (shown: fold it away) through flat to down (folded: bring it back). [glyph]
 * draws the rest in the tile's ink and returns where the chevron's middle sits, in grid units.
 */
@Composable
private fun FoldToggle(
    shown: Boolean,
    label: String,
    tone: Color,
    shape: Shape,
    onToggle: () -> Unit,
    modifier: Modifier,
    glyph: DrawScope.(u: Float, stroke: Stroke, ink: Color) -> Float,
) {
    // -1 is an arrow up, 1 an arrow down; read only while drawing
    val bend = animateFloatAsState(if (shown) -1f else 1f, AppMotion.Link.press(), label = "bend")
    ToneTile(label, tone, shape, modifier, on = shown, switch = true, onClick = onToggle) { ink, hop ->
        Canvas(Modifier.size(20.dp).graphicsLayer { scaleX = hop(); scaleY = hop() }) {
            // drawn on Lucide's 24-unit grid, as the other icons are
            val u = size.width / 24f
            val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val mid = glyph(u, stroke, ink) * u
            // the chevron: its middle above its ends (up) or below them (down)
            val lift = bend.value * 1.6f * u
            val chevron = Path().apply {
                moveTo(9f * u, mid - lift)
                lineTo(12f * u, mid + lift)
                lineTo(15f * u, mid - lift)
            }
            drawPath(chevron, ink, style = stroke)
        }
    }
}
