package com.t3lluz.weave.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.util.rememberHaptics

/**
 * The rows of tiles that frame a screen: the row at its top (back, what the screen is about,
 * its switches) and the remote's dock at its foot. Same tiles, same gaps, same sides. A top
 * row's small tiles are all [TopTile] wide, on every screen, and its wide one takes the rest.
 * The end tiles take a rounder corner where they sit in the screen's own.
 */
object TileRow {
    val Gap = 6.dp
    val Side = 12.dp

    /** The top row's tiles; the dock stands a little taller, under the thumb. */
    val TopTile = 54.dp
    val TopHeight = 50.dp
    val DockHeight = 54.dp

    val TopFirst = RoundedCornerShape(topStart = 28.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 18.dp)
    val TopLast = RoundedCornerShape(topStart = 14.dp, topEnd = 28.dp, bottomEnd = 18.dp, bottomStart = 14.dp)
    val Middle = RoundedCornerShape(14.dp)
    val DockFirst = RoundedCornerShape(topStart = 18.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 28.dp)
    val DockLast = RoundedCornerShape(topStart = 14.dp, topEnd = 18.dp, bottomEnd = 28.dp, bottomStart = 14.dp)
}

/**
 * A tile in its own colour, after Health's meters: a flat dark tint of [tone] with its content
 * in that colour. [on], it fills with more of the colour and its content turns white, with a
 * small hop each time it flips. It gives a little under the finger. [content] gets the colour
 * to draw in and the hop to scale by (read while drawing).
 */
@Composable
fun ToneTile(
    label: String,
    tone: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    /** Whether this tile is a switch: a switch says on and off under the finger, a button ticks. */
    switch: Boolean = false,
    height: Dp = TileRow.TopHeight,
    /** How far it gives while pressed: a wide tile gives less than a small one. */
    pressedScale: Float = AppMotion.Link.PRESSED_SCALE,
    onClick: () -> Unit,
    content: @Composable BoxScope.(ink: Color, hop: () -> Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val fill by animateColorAsState(lerp(Background, tone, if (on) 0.42f else 0.14f), AppMotion.colorTween(), label = "tile")
    val ink by animateColorAsState(if (on) Color.White else tone, AppMotion.colorTween(), label = "tileInk")
    val hop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on != seen) {
            seen = on
            hop.snapTo(0.74f)
            hop.animateTo(1f, AppMotion.Link.press())
        }
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) pressedScale else 1f, AppMotion.Link.press(), label = "tilePress")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .height(height)
            .clip(shape)
            .background(fill)
            .clickable(interactionSource = source, indication = null, role = if (switch) Role.Switch else Role.Button, onClickLabel = label) {
                when {
                    !switch -> haptics.tick()
                    on -> haptics.toggleOff()
                    else -> haptics.toggleOn()
                }
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content(ink) { hop.value }
    }
}

/** A [ToneTile] that is one icon. */
@Composable
fun IconTile(
    icon: ImageVector,
    label: String,
    tone: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    switch: Boolean = false,
    height: Dp = TileRow.TopHeight,
    onClick: () -> Unit,
) {
    ToneTile(label, tone, shape, modifier, on, switch, height, onClick = onClick) { ink, hop ->
        Icon(icon, null, tint = ink, modifier = Modifier.size(if (height >= TileRow.DockHeight) 22.dp else 20.dp).graphicsLayer { scaleX = hop(); scaleY = hop() })
    }
}

/** The way back, at the start of every top row: the same tile on every screen. */
@Composable
fun BackTile(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Back") {
    IconTile(com.t3lluz.weave.ui.theme.AppIcons.ArrowBack, label, TextSecondary, TileRow.TopFirst, modifier.width(TileRow.TopTile), onClick = onClick)
}
