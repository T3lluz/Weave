package com.t3lluz.weave.ui.screens.link

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.roomColor
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.util.rememberReducedMotion
import com.t3lluz.weave.ui.viewmodel.LinkViewModel
import org.intellij.lang.annotations.Language

/*
 * The trackpad as cloth stretched over a frame. A finger pushes a dent into it: the weave
 * around the finger is drawn in toward it and the dent's walls are a shade darker all round,
 * while the pad's edges, held by the frame, stay put. Lifting the finger lets the cloth spring
 * back, a touch past flat, then settle. Android 13+ (RuntimeShader); older phones get a soft
 * shadow under the finger instead.
 */
@Language("AGSL")
private const val CLOTH_DENT_AGSL = """
    uniform shader content;
    uniform float2 uTouch0;
    uniform float2 uTouch1;
    uniform float2 uTouch2;
    uniform float3 uDepth;
    uniform float uPull;
    uniform float uRadius;
    uniform float2 uSize;
    uniform float uEdge;

    // How much of one finger's dent reaches p: 1 under the finger, fading out round it.
    float reach(float2 p, float2 touch) {
        float x = length(p - touch) / uRadius;
        return exp(-x * x);
    }

    // How much darker one dent's wall makes the cloth at p. Lit from straight above, the walls
    // are a little darker than the flat cloth, most where they are steepest, so the shading
    // rings the finger evenly. Only while pressed in: springing back past flat must not
    // brighten the cloth.
    float wall(float2 p, float2 touch, float depth, float edge) {
        float x = length(p - touch) / uRadius;
        return 0.12 * max(depth, 0.0) * x * exp(-x * x) * 2.33 * edge;
    }

    half4 main(float2 p) {
        // the frame holds the cloth: the pull fades out toward the pad's edges, which stay put
        float edge = clamp(min(min(p.x, uSize.x - p.x), min(p.y, uSize.y - p.y)) / uEdge, 0.0, 1.0);
        // one dent under each finger; a finger that isn't there has no depth and adds nothing
        float3 f = float3(reach(p, uTouch0), reach(p, uTouch1), reach(p, uTouch2));
        // Where two dents meet they share the cloth: the pull of all of them together is scaled
        // by how much one of them has the place to itself (1 under a finger, a half midway
        // between two). Added up plainly, the cloth between two fingers is pulled past itself
        // and the weave folds into streaks. uPull is eased down as well while fingers crowd
        // each other (crowding, below): with both, the cloth is never squeezed tighter between
        // two fingers than round one.
        float3 g = f * abs(uDepth);
        float all = g.x + g.y + g.z + 0.0001;
        float own = dot(g, g) / (all * all);
        float3 pull = uPull * own * uDepth * f * edge;
        // the cloth is pulled into the dents: each point shows what lay further out, so the
        // weave stretches toward the fingers
        float2 src = p + (p - uTouch0) * pull.x + (p - uTouch1) * pull.y + (p - uTouch2) * pull.z;
        half4 color = content.eval(clamp(src, float2(0.5), uSize - 0.5));
        color.rgb *= (1.0 - wall(p, uTouch0, uDepth.x, edge)) * (1.0 - wall(p, uTouch1, uDepth.y, edge)) * (1.0 - wall(p, uTouch2, uDepth.z, edge));
        return color;
    }
"""

/** How many fingers dent the cloth at once: two scroll, three are the middle button. */
private const val DENTS = 3

/** How far the cloth is drawn into a dent: 1.25 times its own distance from the finger, at the finger. */
private const val PULL = 1.25f

/** How much of that pull is taken off while two fingers crowd each other. */
private const val CROWDED_EASE = 0.42f

/**
 * How much two fingers crowd each other, 0 to 1. Two dents close enough to overlap but far
 * enough apart to pull against each other (about a thumb and a half apart, 115 to 200 dp)
 * squeeze the cloth between them; closer together they act as one dent, further apart as two
 * that never meet. A dent springing back counts for as much as it is still pressed in.
 * Checked by numbers: with this, the weave between two fingers is never drawn tighter than
 * the weave round one, at any distance.
 */
internal fun crowding(places: List<Offset>, depths: FloatArray, density: Float): Float {
    var most = 0f
    for (i in places.indices) for (j in i + 1 until places.size) {
        val pressed = minOf(kotlin.math.abs(depths[i]), kotlin.math.abs(depths[j])).coerceAtMost(1f)
        if (pressed <= 0f) continue
        val apart = (places[i] - places[j]).getDistance() / density
        val near = ((apart - 90f) / 50f).coerceIn(0f, 1f) * ((230f - apart) / 70f).coerceIn(0f, 1f)
        most = maxOf(most, near * near * (3f - 2f * near) * pressed)
    }
    return most
}

/**
 * One dent in the cloth: the finger it is under, where that finger is (kept after it lifts, so
 * the cloth springs back where it was pressed), and how far it is pressed in.
 */
private class Dent {
    var finger = NO_FINGER
    var at by mutableStateOf(Offset.Unspecified)
    var touching by mutableStateOf(false)
    val depth = Animatable(0f)
}

private const val NO_FINGER = -1L

/**
 * Gives each finger on the pad its own dent and keeps it under that finger, so a dent never
 * jumps from one finger to another when one of two lifts. A new finger takes a dent that is
 * flat, or the flattest there is; a finger that has lifted leaves its dent to spring back.
 */
private fun follow(dents: List<Dent>, fingers: List<Pair<Long, Offset>>) {
    for (dent in dents) {
        if (dent.touching && fingers.none { it.first == dent.finger }) dent.touching = false
    }
    for ((id, at) in fingers.take(dents.size)) {
        val dent = dents.firstOrNull { it.touching && it.finger == id }
            ?: dents.filter { !it.touching }.minByOrNull { kotlin.math.abs(it.depth.value) }
            ?: continue
        dent.finger = id
        dent.at = at
        dent.touching = true
    }
}

/** The weave: one faint dot every this many dp, offset every other row. */
private const val WEAVE_DP = 13f

/** A dot's radius. */
private const val WEAVE_DOT_DP = 1.5f

/**
 * The trackpad, as a room in the hands' green with cloth on it. Until a finger has been on
 * it, it says what fingers do. Its left and right buttons sit at its foot, outside the dent.
 * While a held finger drags, the weave is lit under the fingers and Left shows itself held.
 */
@Composable
internal fun LinkTrackpad(
    viewModel: LinkViewModel,
    config: LinkConfig,
    hint: Boolean,
    buttons: Boolean,
    modifier: Modifier = Modifier,
    /** The computer reads two fingers and more itself ([LinkSession.TOUCHPAD]). */
    nativePad: Boolean = false,
    /** A finger is on the pad (true) or has lifted (false). */
    onTouch: (Boolean) -> Unit = {},
) {
    val session = viewModel.session
    val haptics = rememberHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    // the pad's surface under the finger: one faint, uneven grain while it glides
    val paper = remember(context) { com.t3lluz.weave.ui.util.PaperFeel(context) }
    // A held finger has taken hold of what is under the pointer. Until the last finger lifts the
    // laptop's left button is down: the weave is lit under every finger that is on the pad, and
    // the Left button at the foot shows itself held, as it is.
    var held by remember { mutableStateOf(false) }
    val grip = remember { Animatable(0f) }
    LaunchedEffect(held) { grip.animateTo(if (held) 1f else 0f, if (held) AppMotion.Link.dentIn() else tween(220)) }
    val reduced = rememberReducedMotion()
    val room = roomColor(LinkHandsTone)
    val shape = RoundedCornerShape(28.dp)
    // a dent per finger: where each is and how far the cloth is pressed there, read only while drawing
    val dents = remember { List(DENTS) { Dent() } }
    var anyFinger by remember { mutableStateOf(false) }
    // A tap sends a ring out through the weave from where it landed: the dots light up as it
    // passes and settle again. One runs from the middle when the pad first appears.
    val ripples = remember { mutableStateListOf<Ripple>() }
    val scope = rememberCoroutineScope()
    fun ripple(at: Offset, far: Float = 1f) {
        if (reduced || ripples.size >= 4) return
        val one = Ripple(at, far)
        ripples += one
        scope.launch {
            one.out.animateTo(1f, tween((RIPPLE_MS * far).toInt(), easing = LinearEasing))
            ripples -= one
        }
    }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(padSize != IntSize.Zero) {
        if (padSize != IntSize.Zero) ripple(Offset(padSize.width / 2f, padSize.height / 2f), far = 1.9f)
    }
    // two fingers just flicked sideways: which way (true is back), and a count so the same way twice shows twice
    var swept by remember { mutableStateOf<Boolean?>(null) }
    var sweeps by remember { mutableIntStateOf(0) }
    LaunchedEffect(sweeps) {
        if (sweeps > 0) {
            kotlinx.coroutines.delay(SWEEP_SHOWN_MS)
            swept = null
        }
    }
    dents.forEach { dent ->
        LaunchedEffect(dent.touching) {
            if (dent.touching) dent.depth.animateTo(1f, AppMotion.Link.dentIn()) else dent.depth.animateTo(0f, AppMotion.Link.dentOut())
        }
    }

    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                // A swipe that starts at the pad's edge is the pad's, not Android's "back". (The
                // system honours this for the lower 200 dp of each side of the screen.)
                .systemGestureExclusion()
                .clip(shape)
                .then(if (reduced) Modifier else Modifier.clothDent(dents))
                .background(room)
                .drawWithCache {
                    // the weave: one path of dots, made once for the pad's size
                    val step = WEAVE_DP * density
                    val r = WEAVE_DOT_DP * density
                    val weave = Path()
                    val ring = Path()
                    var row = 0
                    var y = step / 2
                    while (y < size.height) {
                        var x = if (row % 2 == 0) step / 2 else step
                        while (x < size.width) {
                            weave.addOval(androidx.compose.ui.geometry.Rect(x - r, y - r, x + r, y + r))
                            x += step
                        }
                        y += step * 0.87f
                        row++
                    }
                    onDrawBehind {
                        drawPath(weave, Color.White.copy(alpha = 0.07f))
                        // held: the dots round each finger on the pad are lit in the hands' green, brightest close in
                        val g = grip.value.coerceIn(0f, 1.2f)
                        if (g > 0.01f) {
                            for (dent in dents) {
                                val at = dent.at
                                if (!dent.touching || at == Offset.Unspecified) continue
                                for ((share, light) in GRIP_BANDS) {
                                    val r = GRIP_DP * density * share * (0.55f + 0.45f * g)
                                    ring.rewind()
                                    ring.fillType = PathFillType.NonZero
                                    ring.addOval(androidx.compose.ui.geometry.Rect(at.x - r, at.y - r, at.x + r, at.y + r))
                                    clipPath(ring) { drawPath(weave, LinkHandsTone.copy(alpha = (light * g).coerceIn(0f, 1f))) }
                                }
                            }
                        }
                        // The rings: the same dots again, lit only where each ring is right now. Three
                        // bands, the middle one brightest, each a plain colour through a ring-shaped
                        // cut-out (no gradient: the dots are small enough that bands read as a soft edge).
                        for (one in ripples) {
                            val t = one.out.value
                            val band = RIPPLE_BAND_DP * density / 3f
                            // it runs out fast and slows, and dims evenly on the way: seen all the way out
                            val reach = RIPPLE_REACH_DP * density * one.far * (1f - (1f - t) * (1f - t))
                            val fade = 1f - t
                            for ((i, share) in RIPPLE_BANDS.withIndex()) {
                                val inner = (reach - band * 1.5f + band * i).coerceAtLeast(0f)
                                val outer = reach - band * 1.5f + band * (i + 1)
                                if (outer <= 0f) continue
                                ring.rewind()
                                ring.fillType = PathFillType.EvenOdd
                                ring.addOval(androidx.compose.ui.geometry.Rect(one.at.x - outer, one.at.y - outer, one.at.x + outer, one.at.y + outer))
                                if (inner > 0f) ring.addOval(androidx.compose.ui.geometry.Rect(one.at.x - inner, one.at.y - inner, one.at.x + inner, one.at.y + inner))
                                clipPath(ring) { drawPath(weave, Color.White.copy(alpha = RIPPLE_LIGHT * share * fade)) }
                            }
                        }
                    }
                }
                .onSizeChanged { padSize = it }
                .drawBehind {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        val r = 70.dp.toPx()
                        for (dent in dents) {
                            val at = dent.at
                            val depth = dent.depth.value
                            if (at != Offset.Unspecified && depth > 0f) {
                                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.14f * depth), Color.Transparent), at, r), r, at)
                            }
                        }
                    }
                }
                .trackpad(
                    session, config.speed, config.naturalScroll,
                    onTick = haptics::tick, onTouched = { viewModel.touched = true },
                    onHold = {
                        haptics.grab()
                        held = true
                    },
                    // a tick for each finger of a tap, so the hand knows which button it pressed
                    onTap = { fingers, at ->
                        haptics.taps(fingers)
                        ripple(at)
                    },
                    onDrop = {
                        haptics.drop()
                        held = false
                    },
                    onGlide = paper::glide,
                    onFingers = { fingers ->
                        if (fingers.isEmpty()) paper.lift()
                        follow(dents, fingers)
                        if (anyFinger != fingers.isNotEmpty()) onTouch(fingers.isNotEmpty())
                        anyFinger = fingers.isNotEmpty()
                    },
                    nativePad = nativePad,
                    onSweep = { back ->
                        haptics.swoosh()
                        swept = back
                        sweeps++
                    },
                ),
        ) {
            // what the pad does, until a finger has been on it
            AnimatedVisibility(
                visible = hint,
                enter = fadeIn(AppMotion.fadeTween()), exit = fadeOut(AppMotion.revealTween()),
                modifier = Modifier.align(Alignment.Center).padding(bottom = 56.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Gesture(AppIcons.MousePointer, "Tap", "to click")
                    Gesture(AppIcons.Move, "Hold", "to drag")
                    Gesture(AppIcons.SwapVertical, "Two fingers", "to scroll")
                    Gesture(AppIcons.SwapHorizontal, "Sideways", "back, forward")
                }
            }
        }
        // back comes in from the left as it does in a browser, forward from the right
        SweepMark(swept == true, back = true, reduced, Modifier.align(Alignment.CenterStart))
        SweepMark(swept == false, back = false, reduced, Modifier.align(Alignment.CenterEnd))
        AnimatedVisibility(
            visible = buttons,
            // they rise into place as the keyboard leaves, and sink away as it comes
            enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = 0.82f, stiffness = 520f)) { it / 2 },
            exit = fadeOut(tween(90)) + slideOutVertically(tween(140)) { it / 2 },
            // 8 dp in from the pad's sides and foot, their outer corners the pad's (28) less that
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton(session, "left", "Left", RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 20.dp), Modifier.weight(1f), held = held)
                PadButton(session, "right", "Right", RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 20.dp, bottomStart = 12.dp), Modifier.weight(1f))
            }
        }
    }
}

/** Dents this node's drawing under each of [dents]' fingers, as far as each is pressed (0 flat, 1 pressed, below 0 springing back past flat). */
private fun Modifier.clothDent(dents: List<Dent>): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) then(Api33Dent.modifier(dents)) else this

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Api33Dent {
    private val shader by lazy { RuntimeShader(CLOTH_DENT_AGSL) }

    fun modifier(dents: List<Dent>): Modifier = Modifier.graphicsLayer {
        // a dent with no finger yet, or flat again, counts for nothing
        val depths = FloatArray(DENTS) { i ->
            val dent = dents[i]
            if (dent.at != Offset.Unspecified && kotlin.math.abs(dent.depth.value) > 0.004f) dent.depth.value else 0f
        }
        if (depths.any { it != 0f }) {
            for (i in 0 until DENTS) {
                val at = dents[i].at.takeIf { it != Offset.Unspecified } ?: Offset.Zero
                shader.setFloatUniform("uTouch$i", at.x, at.y)
            }
            shader.setFloatUniform("uDepth", depths[0], depths[1], depths[2])
            val places = dents.map { it.at.takeIf { at -> at != Offset.Unspecified } ?: Offset.Zero }
            shader.setFloatUniform("uPull", PULL * (1f - CROWDED_EASE * crowding(places, depths, density)))
            shader.setFloatUniform("uRadius", 50.dp.toPx())
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uEdge", 28.dp.toPx())
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        } else {
            renderEffect = null
        }
    }
}

/**
 * A mouse button at the pad's foot: a solid shape a shade darker than the pad, a small mouse
 * with this button's side lit, and its name in the display face. Outside the dent, so the
 * cloth never warps it. Held, it fills with the hands' green and the mouse's side lights up.
 */
@Composable
private fun PadButton(
    session: LinkSession, button: String, label: String, shape: RoundedCornerShape, modifier: Modifier = Modifier,
    /** The pad is holding this button down itself (a held drag): it shows pressed without a finger on it. */
    held: Boolean = false,
) {
    val haptics = rememberHaptics()
    var down by remember { mutableStateOf(false) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(down || held) { press.animateTo(if (down || held) 1f else 0f, AppMotion.Link.press()) }
    val face = lerp(roomColor(LinkHandsTone), Color.Black, 0.3f)
    Box(
        modifier
            .height(52.dp)
            .graphicsLayer {
                val s = 1f - 0.03f * press.value
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(face)
            // a click going down and a lighter one coming up, as the switch under a real button gives
            .mouseButton(session, button, onTick = haptics::press, onPressed = {
                if (!it) haptics.release()
                down = it
            }),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = press.value }.background(LinkHandsTone.copy(alpha = 0.22f)))
        // the mouse sits on the outer side, so the pair mirror each other
        val left = button == "left"
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left) {
                MouseGlyph(left = true, lit = { 0.45f + 0.55f * press.value })
                Spacer(Modifier.width(9.dp))
            }
            Text(label, style = HealthDisplay, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (!left) {
                Spacer(Modifier.width(9.dp))
                MouseGlyph(left = false, lit = { 0.45f + 0.55f * press.value })
            }
        }
    }
}

/** A small mouse, outlined, with the side of the button it stands for filled in the hands' green. */
@Composable
private fun MouseGlyph(left: Boolean, lit: () -> Float) {
    Canvas(Modifier.size(width = 13.dp, height = 19.dp)) {
        val stroke = 1.6.dp.toPx()
        val body = Path().apply {
            addRoundRect(RoundRect(stroke / 2, stroke / 2, size.width - stroke / 2, size.height - stroke / 2, CornerRadius(size.width / 2)))
        }
        val split = size.height * 0.44f
        clipPath(body) {
            drawRect(
                LinkHandsTone.copy(alpha = lit()),
                topLeft = Offset(if (left) 0f else size.width / 2, 0f),
                size = Size(size.width / 2, split),
            )
        }
        drawPath(body, LinkHandsTone, style = Stroke(stroke))
        drawLine(LinkHandsTone, Offset(size.width / 2, 0f), Offset(size.width / 2, split), stroke)
        drawLine(LinkHandsTone, Offset(0f, split), Offset(size.width, split), stroke)
    }
}

/** A ring running out through the weave: where it started, how far it goes (1 is a tap's), and how far out it is (0 to 1). */
private class Ripple(val at: Offset, val far: Float) {
    val out = Animatable(0f)
}

private const val RIPPLE_MS = 760
private const val RIPPLE_REACH_DP = 150f
private const val RIPPLE_BAND_DP = 30f
/** How much brighter than the resting weave (0.07) the dots get at the ring's crest. */
private const val RIPPLE_LIGHT = 0.5f
/** The ring's three bands, inside to outside: how much of that light each gets. */
private val RIPPLE_BANDS = floatArrayOf(0.45f, 1f, 0.45f)

/** How far round a finger the weave is lit while something is held, and its two bands: (share of that, light). */
private const val GRIP_DP = 92f
private val GRIP_BANDS = listOf(1f to 0.34f, 0.66f to 0.4f)

/** How long the mark of a sweep stays. */
private const val SWEEP_SHOWN_MS = 650L

/** Says which way a flick just went: a pill at the side the page comes from. */
@Composable
private fun SweepMark(visible: Boolean, back: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)) + if (reduced) fadeIn(tween(90)) else slideInHorizontally(spring(dampingRatio = 0.62f, stiffness = 700f)) { if (back) -it else it },
        exit = fadeOut(AppMotion.revealTween()),
        modifier = modifier.padding(horizontal = 14.dp),
    ) {
        Row(
            Modifier.clip(CircleShape).background(lerp(roomColor(LinkHandsTone), LinkHandsTone, 0.22f)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (back) Icon(AppIcons.ChevronLeft, null, tint = LinkHandsTone, modifier = Modifier.size(18.dp))
            Text(if (back) "Back" else "Forward", style = HealthDisplay, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!back) Icon(AppIcons.ChevronRight, null, tint = LinkHandsTone, modifier = Modifier.size(18.dp))
        }
    }
}

/** One part of the trackpad's hint: an icon, what the fingers do, and what that does. */
@Composable
private fun Gesture(icon: ImageVector, what: String, does: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = LinkHandsTone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(what, style = HealthDisplay, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(does, color = TextSecondary, fontSize = 12.sp)
    }
}
