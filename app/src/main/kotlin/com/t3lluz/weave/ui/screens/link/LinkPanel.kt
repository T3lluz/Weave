package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.util.rememberHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The full-screen panel: the controls in one row, the shortcuts in a row under them, and the
// handle it all folds into.

/**
 * The screen the folded panel, a bubble [bubble] across, is moved about on, in px. It rests
 * against an edge, [inset] in from it and [gap] clear of the camera's [holes], and only where
 * the panel, [panel] wide, has room to unfold beside it: the handle stays under the finger that
 * opened it, so the same tap folds it again.
 */
internal class PanelField(
    val width: Float, val height: Float, val bubble: Float, val inset: Float,
    private val holes: List<Rect> = emptyList(), private val gap: Float = 0f, private val panel: Float = 0f,
) {
    private val right = max(inset, width - bubble - inset)
    private val bottom = max(inset, height - bubble - inset)

    /** The bubble's top left corner kept on the screen, wherever the finger went. */
    fun within(at: Offset) = Offset(at.x.coerceIn(inset, right), at.y.coerceIn(inset, bottom))

    /** Where a bubble let go at [at] comes to rest: against the nearest edge, slid along it off any hole. */
    fun rest(at: Offset): Offset {
        val p = within(at)
        val toLeft = p.x - inset
        val toRight = right - p.x
        val toTop = p.y - inset
        val toBottom = bottom - p.y
        return when (minOf(toLeft, toRight, toTop, toBottom)) {
            toLeft -> clear(Offset(inset, p.y), down = true)
            toRight -> clear(Offset(right, p.y), down = true)
            toTop -> clear(Offset(roomy(p.x), inset), down = false)
            else -> clear(Offset(roomy(p.x), bottom), down = false)
        }
    }

    /**
     * Along the top and the bottom the panel unfolds sideways. One wider than half the screen has
     * no room either way from around the middle: [x] moved to the nearest place it has.
     */
    private fun roomy(x: Float): Float {
        val rightwards = width - inset - panel       // the furthest right it still unfolds to the right from
        val leftwards = inset + panel - bubble       // the furthest left it still unfolds to the left from
        return if (x <= rightwards || x >= leftwards) x else beside(x, rightwards, leftwards, right)
    }

    /** [at] moved along its edge ([down] it or across) to the nearest place no hole is under it. */
    private fun clear(at: Offset, down: Boolean): Offset {
        var p = at
        for (hole in holes) {
            val h = hole.inflate(gap)
            if (!h.overlaps(Rect(p, Size(bubble, bubble)))) continue
            p = if (down) Offset(p.x, beside(p.y, h.top - bubble, h.bottom, bottom)) else Offset(beside(p.x, h.left - bubble, h.right, right), p.y)
        }
        return p
    }

    /** The nearer of the two places beside a hole that are on the screen; [now] when neither is. */
    private fun beside(now: Float, before: Float, after: Float, end: Float): Float =
        listOf(before, after).filter { it in inset..end }.minByOrNull { abs(it - now) } ?: now

    /** A place as it is kept: how far across and down the screen, 0 to 1 each. */
    fun kept(at: Offset): Pair<Float, Float> =
        (if (right > inset) (at.x - inset) / (right - inset) else 0f) to (if (bottom > inset) (at.y - inset) / (bottom - inset) else 0f)

    fun from(kept: Pair<Float, Float>) = rest(
        Offset(inset + kept.first.coerceIn(0f, 1f) * (right - inset), inset + kept.second.coerceIn(0f, 1f) * (bottom - inset)),
    )

    /** The panel unfolds into the room it has: to the left from the right half of the screen, up from the lower half. */
    fun opensLeft(at: Offset) = at.x + bubble / 2f > width / 2f
    fun opensUp(at: Offset) = at.y + bubble / 2f > height / 2f
}

/** Where the bubble is, and which way the panel unfolds from there. */
@Stable
internal class PanelPlace(val field: PanelField, start: Offset) {
    /** The bubble's top left corner. */
    val spot = Animatable(start, Offset.VectorConverter)
    var left by mutableStateOf(field.opensLeft(start))
        private set
    var up by mutableStateOf(field.opensUp(start))
        private set

    /** The corner of the panel its handle is in, which stays put as it unfolds. */
    val corner: Alignment
        get() = when {
            left && up -> Alignment.BottomEnd
            left -> Alignment.TopEnd
            up -> Alignment.BottomStart
            else -> Alignment.TopStart
        }

    fun settled(at: Offset) {
        left = field.opensLeft(at)
        up = field.opensUp(at)
    }
}

/**
 * Puts the panel where its bubble is, its handle's corner on the bubble, and gives the handle
 * all its gestures. A tap opens or folds the panel ([onTap]). Dragged, it follows the finger;
 * let go, it goes to the nearest edge ([onDrop] gets the place to keep). Held still, it offers
 * the way out of full screen ([onHolding] says while it does), and let go there it takes it
 * ([onLeave]); moved instead, it is a drag after all. A panel too big for the room it has is
 * moved onto the screen, and clear of what covers the foot of it ([floor], px: the keyboard);
 * one too big for the screen keeps its handle's end on it. Goes before the panel's glass.
 */
internal fun Modifier.panelPlace(
    place: PanelPlace, floor: () -> Int,
    onTap: () -> Unit, onHolding: (Boolean) -> Unit, onLeave: () -> Unit,
    onGrab: () -> Unit, onDrop: (Pair<Float, Float>) -> Unit,
): Modifier = this
    .layout { measurable, constraints ->
        val field = place.field
        val inset = field.inset.roundToInt()
        val panel = measurable.measure(
            Constraints(maxWidth = (constraints.maxWidth - 2 * inset).coerceAtLeast(0), maxHeight = (constraints.maxHeight - 2 * inset).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            // read here, so a move places the panel again and measures nothing
            val spot = place.spot.value
            val x = if (place.left) spot.x + field.bubble - panel.width else spot.x
            val y = if (place.up) spot.y + field.bubble - panel.height else spot.y
            val right = constraints.maxWidth - inset - panel.width
            val foot = constraints.maxHeight - floor() - inset - panel.height
            panel.place(
                if (place.left) x.roundToInt().coerceAtLeast(inset).coerceAtMost(right) else x.roundToInt().coerceAtMost(right).coerceAtLeast(inset),
                if (place.up) y.roundToInt().coerceAtLeast(inset).coerceAtMost(foot) else y.roundToInt().coerceAtMost(foot).coerceAtLeast(inset),
            )
        }
    }
    .pointerInput(place) {
        coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // only the handle: the buttons beside it are for pressing
                val across = if (place.left) size.width - down.position.x else down.position.x
                val along = if (place.up) size.height - down.position.y else down.position.y
                if (across > place.field.bubble || along > place.field.bubble) return@awaitEachGesture
                var at = place.spot.value
                val follow = { change: PointerInputChange, over: Offset ->
                    change.consume()
                    at = place.field.within(at + over)
                }
                // lifted before the hold takes: a tap; moved: a drag; neither: it is held
                var first: PointerInputChange? = null
                val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    first = awaitTouchSlopOrCancellation(down.id, follow)
                    true
                }
                if (quick == true && first == null) {
                    onTap()
                    return@awaitEachGesture
                }
                if (quick == null) {
                    onHolding(true)
                    first = awaitTouchSlopOrCancellation(down.id, follow)
                    onHolding(false)
                    if (first == null) {
                        // let go where it was held (and not taken by anything else): out of full screen
                        if (currentEvent.changes.none { it.isConsumed }) onLeave()
                        return@awaitEachGesture
                    }
                }
                val start = first ?: return@awaitEachGesture
                onGrab()
                scope.launch { place.spot.snapTo(at) }
                drag(start.id) { change ->
                    at = place.field.within(at + change.positionChange())
                    change.consume()
                    scope.launch { place.spot.snapTo(at) }
                }
                val home = place.field.rest(at)
                place.settled(home)
                onDrop(place.field.kept(home))
                scope.launch { place.spot.animateTo(home, AppMotion.Link.panel()) }
            }
        }
    }

/**
 * The panel's rows, one over the other. The controls say how wide the panel is; the shortcuts
 * get that width and scroll inside it, however many there are. The controls are the row the
 * handle is in, so with [shelfAbove] (a panel that unfolds upwards) the shortcuts go over them.
 */
@Composable
internal fun PanelStack(modifier: Modifier = Modifier, shelfAbove: Boolean = false, controls: @Composable () -> Unit, shelf: (@Composable () -> Unit)?) {
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
        val shelfHeight = under?.height ?: 0
        layout(top.width, top.height + shelfHeight) {
            top.place(0, if (shelfAbove) shelfHeight else 0)
            under?.place(0, if (shelfAbove) 0 else top.height)
        }
    }
}

/**
 * Folds the panel away to this one button, and opens it again. Folded, it is four tiles: what
 * is behind it, controls and shortcuts. Opening, the tiles stretch into the arms of a cross.
 * [holding], it turns into the way out of full screen. It is only drawn here: the panel takes
 * its tap, hold and drag ([panelPlace]); [onClick] and [onLeave] are for screen readers.
 */
@Composable
internal fun PanelHandle(open: Boolean, holding: Boolean, onClick: () -> Unit, onLeave: () -> Unit) {
    val turn = animateFloatAsState(if (open) 1f else 0f, AppMotion.Link.panel(), label = "handle")
    val leave = animateFloatAsState(if (holding) 1f else 0f, AppMotion.Link.press(), label = "leave")
    val label = if (open) "Hide the controls" else "Show the controls"
    Box(
        Modifier.size(PANEL_HANDLE).semantics {
            role = Role.Button
            contentDescription = label
            onClick(label = label) { onClick(); true }
            customActions = listOf(CustomAccessibilityAction("Leave full screen") { onLeave(); true })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(20.dp).graphicsLayer {
                val k = 1f - 0.4f * leave.value.coerceIn(0f, 1f)
                alpha = 1f - leave.value.coerceIn(0f, 1f)
                scaleX = k
                scaleY = k
            },
        ) {
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
        // the way out, grown from the middle as the tiles give way
        Icon(
            AppIcons.Minimize, null, tint = TextPrimary,
            modifier = Modifier.size(22.dp).graphicsLayer {
                val v = leave.value.coerceIn(0f, 1.2f)
                alpha = v.coerceAtMost(1f)
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
            },
        )
    }
}

internal val PANEL_HANDLE = 46.dp

/**
 * The panel's measures. Folded it is a circle, a [PANEL_BUBBLE] across; unfolded it keeps that
 * corner, and its controls are as tall as the handle, so a panel of controls alone is a pill.
 * What sits in it, [PANEL_PAD] in from its edge, follows the same curve: the controls' and the
 * shortcuts' corners are the panel's less that gap, so the two run side by side round a corner.
 */
internal val PANEL_PAD = 5.dp
internal val PANEL_BUBBLE = PANEL_HANDLE + PANEL_PAD * 2
internal val PANEL_RADIUS = PANEL_BUBBLE / 2
internal val PanelShape = RoundedCornerShape(PANEL_RADIUS)
internal val PanelInnerShape = RoundedCornerShape(PANEL_RADIUS - PANEL_PAD)

private val CORNERS = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
private const val DIAGONAL = 0.70710677f

/**
 * The shortcuts in the full-screen panel: a row that scrolls, fading at whichever end has more.
 * A tap runs one, as on the shelf; [onUsed] says the panel is in use, so it stays open. [above]
 * the controls instead of under them, in a panel that unfolds upwards.
 */
@Composable
internal fun PanelShortcuts(
    shortcuts: List<LinkShortcut>, store: LinkShortcutStore, running: Map<String, Int> = emptyMap(),
    above: Boolean = false, onUsed: () -> Unit, onRun: (LinkShortcut) -> Unit,
) {
    val icons by store.icons.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) onUsed() }
    // the line between the row and the controls, whichever side of it they are on
    val line = @Composable {
        Box(Modifier.padding(horizontal = PANEL_RADIUS - PANEL_PAD, vertical = PANEL_PAD).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.14f)))
    }
    Column(Modifier.fillMaxWidth()) {
        if (!above) line()
        // a tile scrolled into the panel's corner is cut along its curve, not straight across
        val inner = PANEL_RADIUS - PANEL_PAD
        Row(
            Modifier
                .fillMaxWidth()
                .clip(if (above) RoundedCornerShape(topStart = inner, topEnd = inner) else RoundedCornerShape(bottomStart = inner, bottomEnd = inner))
                .fadeEnds(scroll)
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            shortcuts.forEachIndexed { i, s ->
                key(s.id) { PanelShortcut(i, s, store, icons, busy = s.id in running) { onUsed(); onRun(s) } }
            }
        }
        if (above) line()
    }
}

/** One shortcut of the panel: the shelf's tile on glass. It arrives after the controls, lights and hops when tapped. */
@Composable
private fun PanelShortcut(index: Int, s: LinkShortcut, store: LinkShortcutStore, icons: Int, busy: Boolean, onRun: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val fx = remember { TileFx() }
    RunEnd(busy, fx)
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
        ShortcutTile(s, store, icons, fx, glass = true, busy = busy, shape = PanelInnerShape)
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
