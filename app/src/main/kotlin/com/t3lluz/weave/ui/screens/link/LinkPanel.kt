package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.util.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.min

// The full-screen panel: the controls in one row, the shortcuts in a row under them, and the
// handle it all folds into.

/**
 * The panel's rows, one under the other. The controls say how wide the panel is; the shortcuts
 * get that width and scroll inside it, however many there are.
 */
@Composable
internal fun PanelStack(modifier: Modifier = Modifier, controls: @Composable () -> Unit, shelf: (@Composable () -> Unit)?) {
    Layout(
        content = {
            Box { controls() }
            if (shelf != null) Box { shelf() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val top = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val under = measurables.getOrNull(1)?.measure(
            Constraints(minWidth = top.width, maxWidth = top.width, maxHeight = (constraints.maxHeight - top.height).coerceAtLeast(0)),
        )
        layout(top.width, top.height + (under?.height ?: 0)) {
            top.place(0, 0)
            under?.place(0, top.height)
        }
    }
}

/**
 * Folds the panel away to this one button, and opens it again. Folded, it is four tiles: what
 * is behind it, controls and shortcuts. Opening, the tiles stretch into the arms of a cross.
 */
@Composable
internal fun PanelHandle(open: Boolean, onClick: () -> Unit) {
    val turn = animateFloatAsState(if (open) 1f else 0f, AppMotion.Link.panel(), label = "handle")
    val label = if (open) "Hide the controls" else "Show the controls"
    Box(
        Modifier.pressable(label, onClick).size(46.dp).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val k = turn.value                    // may run a little past 0 and 1: the spring's bounce shows in the turn
            val f = k.coerceIn(0f, 1f)            // sizes stay inside what they are meant to be
            val s = size.minDimension
            val middle = Offset(size.width / 2f, size.height / 2f)
            val tile = s * 0.36f                  // a tile's side
            val arm = s * 0.40f                   // an arm of the cross, from the middle out
            val thick = s * 0.10f
            rotate(90f * k, middle) {
                for ((sx, sy) in CORNERS) {
                    val w = tile + (arm - tile) * f
                    val h = tile + (thick - tile) * f
                    // a tile sits out in its corner; an arm starts at the middle and points there
                    val out = s * 0.27f + (arm / 2f * DIAGONAL - s * 0.27f) * f
                    val at = Offset(middle.x + sx * out, middle.y + sy * out)
                    rotate(45f * sx * sy * f, at) {
                        drawRoundRect(
                            TextPrimary, topLeft = Offset(at.x - w / 2f, at.y - h / 2f), size = Size(w, h),
                            cornerRadius = CornerRadius(min(s * 0.11f, h / 2f)),
                        )
                    }
                }
            }
        }
    }
}

private val CORNERS = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
private const val DIAGONAL = 0.70710677f

/**
 * The shortcuts in the full-screen panel: a row that scrolls, fading at whichever end has more.
 * A tap runs one, as on the shelf; [onUsed] says the panel is in use, so it stays open.
 */
@Composable
internal fun PanelShortcuts(shortcuts: List<LinkShortcut>, store: LinkShortcutStore, onUsed: () -> Unit, onRun: (LinkShortcut) -> Unit) {
    val icons by store.icons.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) onUsed() }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Row(
            Modifier.fillMaxWidth().fadeEnds(scroll).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            shortcuts.forEachIndexed { i, s ->
                key(s.id) { PanelShortcut(i, s, store, icons) { onUsed(); onRun(s) } }
            }
        }
    }
}

/** One shortcut of the panel: the shelf's tile on glass. It arrives after the controls, lights and hops when tapped. */
@Composable
private fun PanelShortcut(index: Int, s: LinkShortcut, store: LinkShortcutStore, icons: Int, onRun: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val fx = remember { TileFx() }
    val arrived = remember { Animatable(0f) }
    // after the controls over it; the ones far along the row don't keep anyone waiting
    LaunchedEffect(Unit) { arrived.animateTo(1f, AppMotion.Link.arrive(3 + min(index, 7))) }
    val shortcut by rememberUpdatedState(s)
    val run by rememberUpdatedState(onRun)
    fun tapped() {
        // one with two looks says which way it went
        when {
            shortcut.toggle == null -> haptics.tick()
            shortcut.on -> haptics.toggleOff()
            else -> haptics.toggleOn()
        }
        scope.launch { fx.flash.snapTo(1f); fx.flash.animateTo(0f, tween(AppMotion.Link.FLASH_MS)) }
        scope.launch { fx.hop.snapTo(0.78f); fx.hop.animateTo(1f, AppMotion.Link.press()) }
        run()
    }
    Box(
        Modifier
            .graphicsLayer {
                alpha = arrived.value
                val k = 0.82f + 0.18f * arrived.value
                scaleX = k
                scaleY = k
                translationY = (1f - arrived.value) * 8.dp.toPx()
            }
            .size(width = PANEL_SHORTCUT_W, height = PANEL_SHORTCUT_H)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Run") { tapped(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        scope.launch { fx.press.animateTo(1f, AppMotion.Link.press()) }
                        tryAwaitRelease()
                        scope.launch { fx.press.animateTo(0f, AppMotion.Link.press()) }
                    },
                    onTap = { tapped() },
                )
            },
    ) {
        ShortcutTile(s, store, icons, fx, glass = true)
    }
}

private val PANEL_SHORTCUT_W = 70.dp
private val PANEL_SHORTCUT_H = 56.dp

/** A scrolling row fading out at whichever end has more to show, and not at one that has none. */
internal fun Modifier.fadeEnds(scroll: ScrollState, edge: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val e = edge.toPx().coerceAtMost(size.width / 2f)
        if (e <= 0f) return@drawWithContent
        val start = 1f - (scroll.value / e).coerceIn(0f, 1f)
        val end = 1f - ((scroll.maxValue - scroll.value) / e).coerceIn(0f, 1f)
        if (start >= 1f && end >= 1f) return@drawWithContent
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = start),
                e / size.width to Color.Black,
                1f - e / size.width to Color.Black,
                1f to Color.Black.copy(alpha = end),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
