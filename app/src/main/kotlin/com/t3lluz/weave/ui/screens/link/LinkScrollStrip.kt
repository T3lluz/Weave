package com.t3lluz.weave.ui.screens.link

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.util.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sign

/*
 * The scroll strip: one side of the trackpad works as a mouse's wheel under one finger. It is
 * part of the pad's cloth and drawn with it, so it dents with it.
 *
 * At rest it is a seam: the weave's outer dots on that side lit in the hands' green, the next
 * ones in fainter. The cloth holds it. A finger on it presses a dent into the cloth, and dragging
 * pulls the seam along with it, against the cloth's give: the dots near the finger stretch after
 * it and start drawing out into ridges, the more the further it is pulled, and nothing scrolls
 * yet. Pulled far enough it comes free, as a notification does from its list: a firm snap under
 * the finger, the dent springs out of the cloth, the stretched rows catch up into one strip, and
 * a ring of light runs out along it. From there the finger scrolls. Let go before that, and the
 * cloth springs back past flat and settles, and nothing has scrolled.
 *
 * Free, the strip is a shade darker than the pad, softly at its inner edge, and its rows are
 * ridges that slide with the finger and the roll as a wheel's grip does, lit green round the
 * finger, with a faint tick at each notch the page scrolls. A finger on the free strip scrolls at
 * once. Pulling is the only way to free it (Fredde, 2026-11-21, for one way in that always
 * feels the same): a tap on the seam is answered with a soft decline and a little tug that
 * shows it wants pulling. A little after the finger has gone and
 * the page has stopped, or at once when a finger goes down on the pad, a wave runs back along
 * it, and the ridges glide to where the pad's dots are and become them again.
 */

/** Finger travel, in dp, for one notch of the wheel at speed 1: the same as two fingers'. */
private const val STRIP_DP_PER_NOTCH = 26f

/**
 * How far in from its side a finger takes the strip: at rest the seam and a little more, so it is
 * found without aiming, and little enough that a stroke for the pointer seldom starts there;
 * free, all of it and this much past.
 */
private const val REST_REACH_DP = 24f
private const val REACH_PAST_DP = 10f

/**
 * The seam: the weave's outermost dots on the strip's side lit this much in the hands' green,
 * the next ones in by [SEAM_ECHO] of that. Fredde found it the look that fits, but too faint at
 * 0.36, and turned down a pill, a tiny channel, a tile beside the pad and a column of its own
 * the pad stepped aside for (2026-11-21).
 */
private const val SEAM_LIGHT = 0.7f
private const val SEAM_ECHO = 0.55f

/** Where the strip ends: above the mouse buttons (8 dp up and 52 tall), or near the foot while the keyboard has them away. */
internal const val STRIP_FOOT_OVER_BUTTONS_DP = 68f
internal const val STRIP_FOOT_DP = 12f

/** Its ridges taper out this far in from the top and the foot, clear of the pad's corner and the buttons. */
private const val END_INSET_DP = 10f

/**
 * The strip reaches this many of the weave's steps in from its outer dots: its four half-columns
 * (every row has two of their dots) and halfway to the pad's next. A ridge reaches this many steps
 * either side of its middle, which is halfway between the pad's side and the pad's next dots, so
 * the room either side of a ridge is the same (Fredde, 2026-11-21). He found a line drawn at the
 * strip's edge bad: a soft shade sets it off instead, fading out over [SOFT_EDGE_DP].
 */
private const val BAND_STEPS = 1.75f
private const val RIDGE_HALF_STEPS = 0.62f
private const val SOFT_EDGE_DP = 10f
private const val SHADE = 0.2f

/** How far round the finger the ridges are lit. */
private const val LIT_DP = 64f

/**
 * Pulling it free (Fredde's ask, 2026-11-21, after Android's notifications, which give a firm
 * tick as they come loose from their list): the finger goes this far along the strip, and the
 * seam follows it by up to [GIVE_DP], less and less the further it is pulled (the cloth's give),
 * most near the finger and fading out over [STRETCH_DP] either side of it. While it is pulled, it
 * is drawn part way to free: [HOLD_GRIP] for the finger on it, up to [HOLD_PULL] more as it is
 * pulled.
 */
private const val FREE_DP = 44f
private const val GIVE_DP = 22f
private const val STRETCH_DP = 80f
private const val HOLD_GRIP = 0.15f
private const val HOLD_PULL = 0.35f

/**
 * The cloth creaks as the pull passes these shares of the way, each firmer than the one before,
 * so the tension is felt building toward the snap; a creak is ready again once the pull has
 * eased this far back under it. Fredde asked for a pull long enough to feel like dragging it off
 * something (2026-11-21).
 */
private val CREAKS = floatArrayOf(0.4f, 0.75f)
private const val CREAK_EASE = 0.15f

/**
 * The wave: a row as far from the finger as the strip goes starts turning this far into the
 * whole turn, the row under the finger at once; each row takes the rest of it. Coming free and
 * going back to rest are slow enough to watch the wave run along the strip: Fredde asked for the
 * turn to be noticeable, and slower still (2026-11-21).
 */
private const val WAVE = 0.4f
/** The rest of the way after it is pulled free (the pull has drawn it part way already), for the whole way. */
private const val FREED_MS = 800
private const val RESTING_MS = 700
/** Back to rest because the pad is in use: quicker, still seen. */
private const val GIVING_WAY_MS = 380

/** A turn over [ms] for the whole way, as long as the [way] (0 to 1) left to go, so one taken up halfway keeps its pace. */
private fun turnFor(ms: Int, way: Float) = tween<Float>((ms * way.coerceIn(0f, 1f)).toInt().coerceAtLeast(90), easing = FastOutSlowInEasing)

/** The ring of light along the ridges as it comes free: how far it runs, how wide, how long. */
private const val FLASH_REACH_DP = 260f
private const val FLASH_BAND_DP = 26f
private const val FLASH_MS = 620

/** How long the strip stays free after the finger lifts and the page stops. */
private const val LINGER_MS = 1_100L

/** The fewest ms between two ticks: a Pixel's tick takes about 30, and a new one cuts it off. */
private const val TICK_GAP_MS = 34L

/**
 * How firm a notch's tick is under the finger, and at the start of a roll (it fades as the roll
 * slows): faint, felt as a wheel's detents and not as taps. Fredde asked for them much softer
 * than the first 0.4 (2026-11-21).
 */
private const val TICK_FIRM = 0.14f
private const val ROLL_TICK_FIRM = 0.12f

/** A stroke that sets off this many times further across than along is the pointer's, not the strip's. */
private const val ACROSS = 1.4f

/** Notch speed, in notches per ms, that starts a roll after the finger lifts, and where it ends: as two fingers'. */
private const val ROLL_MIN = 0.006f
private const val ROLL_END = 0.0012f

/**
 * Counts the notches a wheel turns past, either way. [per] is one notch's length; where the
 * wheel is starts at 0, between notches never on one, so the first notch is a whole one away.
 */
internal class Detents(private val per: Float) {
    private var at = per / 2

    /** Turns the wheel by [by] and says how many notches that passed. */
    fun turn(by: Float): Int {
        val before = floor(at / per)
        at += by
        return abs(floor(at / per) - before).toInt()
    }
}

/** How far the seam follows a finger pulled [d] along the strip, against the cloth's [give]: all of it at first, then less and less, never more than [give]. */
internal fun clothGive(d: Float, give: Float): Float = sign(d) * give * (1f - exp(-abs(d) / give))

/** How a finger's touch on the strip ended. */
private enum class Lift {
    /** Down and up without going anywhere. */
    TAP,

    /** Pulled, but let go before it came free. */
    HELD,

    /** Came free (or was already), and scrolled. */
    FREE,

    /** Set off across the strip: the pointer's. */
    ACROSS,
}

/** What the strip looks like now: written by its touches ([ScrollStrip]), read by the cloth that draws it ([drawStrip]). */
@Stable
internal class StripLook {
    /** How free it is, by time: 0 the seam, 1 free (ridges). */
    val open = Animatable(0f)

    /** A finger is on it: 0 to 1, eased. */
    val grip = Animatable(0f)

    /** How far it has been pulled toward coming free, 0 to 1. */
    var tension by mutableFloatStateOf(0f)

    /** How far the seam follows the pull, px; most at [pullFrom], less away from it. */
    var slack by mutableFloatStateOf(0f)

    /** Come free: the stretched rows catching up, 0 (stretched round [pullFrom]) to 1 (all of them as far). */
    var spread by mutableFloatStateOf(0f)

    /** Where the pull began, px down the pad. */
    var pullFrom by mutableFloatStateOf(Float.NaN)

    /** How lit the finger's place is. */
    val lit = Animatable(0f)

    /** Where the finger is, px down the pad; NaN with none. */
    var finger by mutableFloatStateOf(Float.NaN)

    /** Where the last wake began: the wave runs out from here, and back to it. NaN: the middle. */
    var from by mutableFloatStateOf(Float.NaN)

    /** How far the wheel has turned, px. At rest a whole number of the weave's row pairs, so the dots sit where the pad's do. */
    var wheel by mutableFloatStateOf(0f)

    /** The ring of light as it came free: 0 to 1 on its way out, 1 gone; and where from. */
    val flash = Animatable(1f)
    var flashFrom by mutableFloatStateOf(Float.NaN)

    /** How far along from the seam to the ridges it is drawn, 0 to 1: by time once free, by the finger while pulled. */
    val morph: Float get() = maxOf(open.value, HOLD_GRIP * grip.value + HOLD_PULL * tension)

    private var settling: Job? = null

    /** The finger has it pulled [slack] px along, [tension] of the way to coming free. */
    fun pull(slack: Float, tension: Float) {
        settling?.cancel()
        Snapshot.withMutableSnapshot {
            this.slack = slack
            this.tension = tension
            spread = 0f
        }
    }

    /** Let go before it came free: the cloth springs back past flat and settles, as a dent in it does. */
    fun recoil(scope: CoroutineScope, reduced: Boolean) {
        settling?.cancel()
        settling = scope.launch {
            launch { animate(slack, 0f, animationSpec = if (reduced) tween(120) else AppMotion.Link.dentOut()) { v, _ -> slack = v } }
            animate(tension, 0f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { v, _ -> tension = v }
        }
    }

    /**
     * A tap on the held seam, which only a pull frees: it is tugged [by] px, a little of the way a
     * pull would take it, and springs back as the cloth does, which shows what it wants.
     */
    fun nudge(scope: CoroutineScope, by: Float, reduced: Boolean) {
        if (reduced) return
        settling?.cancel()
        settling = scope.launch {
            launch { animate(tension, 0.25f, animationSpec = tween(150, easing = FastOutSlowInEasing)) { v, _ -> tension = v } }
            animate(slack, by, animationSpec = tween(150, easing = FastOutSlowInEasing)) { v, _ -> slack = v }
            launch { animate(slack, 0f, animationSpec = AppMotion.Link.dentOut()) { v, _ -> slack = v } }
            animate(tension, 0f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { v, _ -> tension = v }
        }
    }

    /**
     * Come free: the stretched rows catch up with the ones by the finger, with a little spring, and
     * the stretch becomes the wheel's turn, so nothing moves when it is handed over.
     */
    fun free(scope: CoroutineScope, reduced: Boolean) {
        settling?.cancel()
        settling = scope.launch {
            try {
                animate(spread, 1f, animationSpec = if (reduced) tween(100) else spring(dampingRatio = 0.55f, stiffness = 520f)) { v, _ -> spread = v }
            } finally {
                Snapshot.withMutableSnapshot {
                    wheel += slack
                    slack = 0f
                    spread = 0f
                    tension = 0f
                }
            }
        }
    }

    /** A ring of light out along the ridges from [y]. */
    fun ring(scope: CoroutineScope, y: Float) {
        if (y.isNaN()) return
        flashFrom = y
        scope.launch {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(FLASH_MS, easing = LinearEasing))
        }
    }
}

/** The weave's rows come in pairs, every other one offset: the length after which they look the same, px. */
private fun rowPair(step: Float) = 2f * step * 0.87f

/** How far in from its side the strip reaches on a pad whose weave is [margin] in and [step] apart. */
internal fun stripBand(margin: Float, step: Float): Float = margin + step * BAND_STEPS

/**
 * Where the strip's foot is, px down a pad [height] tall: between two of the weave's rows, so no
 * dot of the pad's is cut in half there, and at least [foot] above the pad's foot.
 */
internal fun stripBottom(height: Float, foot: Float, step: Float): Float {
    val row = step * 0.87f
    val rows = floor((height - foot - step / 2) / row).coerceAtLeast(0f)
    return step / 2 + rows * row + row / 2
}

/**
 * The strip, drawn in the cloth: give it the weave's [margin] and [step] and its dots' radius
 * [dot], all px, and where its foot is ([stripBottom]). The pad leaves its own dots out of the
 * strip (up to [stripBand], down to [bottom]); these stand in for them, at rest exactly where
 * the pad's would be, so there is no telling where one ends and the other starts.
 */
internal fun DrawScope.drawStrip(look: StripLook, left: Boolean, margin: Float, step: Float, dot: Float, bottom: Float) {
    if (bottom <= 0f) return
    val t = look.morph.coerceIn(0f, 1f)
    val row = step * 0.87f
    fun x(inFromSide: Float) = if (left) inFromSide else size.width - inFromSide
    fun smooth(v: Float) = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    // the ridges' middle: halfway between the pad's side and the first dots past the strip
    val middle = margin / 2 + step
    val half = step * RIDGE_HALF_STEPS
    val inset = END_INSET_DP.dp.toPx()
    val seamFade = minOf(56.dp.toPx(), bottom / 3f)
    val ridgeFade = minOf(48.dp.toPx(), bottom / 4f)
    val from = look.from.takeIf { !it.isNaN() } ?: (bottom / 2)
    val farthest = maxOf(from, bottom - from).coerceAtLeast(1f)
    val pullFrom = look.pullFrom.takeIf { !it.isNaN() } ?: (bottom / 2)
    val stretch = STRETCH_DP.dp.toPx()
    val slack = look.slack
    val spread = look.spread
    val finger = look.finger
    val l = look.lit.value
    val reach = LIT_DP.dp.toPx()
    val shade = smooth(t)
    val ring = look.flash.value
    val ringAt = look.flashFrom
    val ringReach = FLASH_REACH_DP.dp.toPx() * (1f - (1f - ring) * (1f - ring))
    val ringFade = (1f - ring) * (1f - ring)
    val ringBand = FLASH_BAND_DP.dp.toPx()

    // Free, the strip is a shade darker than the pad, so the eye takes it as a part of its own.
    // The shade has no edge: it fades out toward the pad, and toward the strip's ends.
    if (shade > 0.01f) {
        val band = stripBand(margin, step)
        val soft = SOFT_EDGE_DP.dp.toPx()
        val x0 = if (left) 0f else size.width - band
        val fade = (seamFade / bottom).coerceAtMost(0.45f)
        val dark = Color.Black.copy(alpha = SHADE * shade)
        val across = if (left) {
            Brush.horizontalGradient(0f to dark, (band - soft) / band to dark, 1f to Color.Transparent, startX = 0f, endX = band)
        } else {
            Brush.horizontalGradient(0f to Color.Transparent, soft / band to dark, 1f to dark, startX = x0, endX = size.width)
        }
        val bounds = Rect(x0, 0f, x0 + band, bottom)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint())
            drawRect(across, topLeft = bounds.topLeft, size = bounds.size)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent, startY = 0f, endY = bottom),
                topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }

    // The rows. At rest, the weave's own: every other row a dot in the first and third
    // half-columns, the rows between in the second and fourth. Pulled, they follow the finger,
    // the nearest most. Free, each row is one ridge across the strip's middle, and the ridges
    // slide with the wheel. A row turns as the wave reaches it.
    // (the rows look the same after a pair of them, so where in a pair the wheel is, is all that shows)
    val pair = rowPair(step)
    val slide = ((look.wheel % pair) + pair) % pair
    val ridgeStroke = 1.6.dp.toPx()
    val white = Color.White.copy(alpha = 0.07f)
    val ridge = Color.White.copy(alpha = 0.16f)
    val lit = LinkHandsTone.copy(alpha = 0.85f)
    val bright = lerp(LinkHandsTone, Color.White, 0.35f)
    var k = -2
    while (true) {
        val base = step / 2 + k * row
        if (base - 2 * row > bottom) break
        val odd = Math.floorMod(k, 2) == 1
        k++
        val at = base + slide
        // the pull: rows near where it began follow it all the way, rows away from it less, until it comes free
        val near = exp(-((at - pullFrom) / stretch).let { it * it })
        val y = at + slack * (near + spread * (1f - near))
        if (y < -dot || y > bottom + dot) continue
        // a row past the strip's ends (slid in, or about to slide out) shows only as a fading ridge
        val inside = if (at < step / 2 - dot || at > bottom - row / 2 + dot) 0f else 1f
        val turn = smooth((t - abs(y - from) / farthest * WAVE) / (1f - WAVE))
        val ends = smooth(minOf(y - inset, bottom - inset - y) / ridgeFade)
        val seam = smooth(minOf(at, bottom - at) / seamFade)
        val close = if (finger.isNaN()) 0f else smooth(1f - abs(y - finger) / reach) * l
        val crest = if (ringFade <= 0.001f || ringAt.isNaN()) 0f else exp(-((abs(y - ringAt) - ringReach) / ringBand).let { it * it }) * ringFade
        val awake = lerp(lerp(ridge, lit, close), bright, crest * 0.75f).let { it.copy(alpha = it.alpha * ends) }
        // tapering toward the ends, as the rows of a cloth seen round a bend
        val reachOut = half * (0.3f + 0.7f * ends)
        val columns = if (odd) floatArrayOf(margin + step / 2, margin + step * 1.5f) else floatArrayOf(margin, margin + step)
        for ((i, dotAt) in columns.withIndex()) {
            val rest = when {
                i == 1 -> white
                odd -> LinkHandsTone.copy(alpha = SEAM_LIGHT * SEAM_ECHO * seam)
                else -> LinkHandsTone.copy(alpha = SEAM_LIGHT * seam)
            }.let { it.copy(alpha = it.alpha * inside) }
            var c = lerp(rest, awake, turn)
            // a row's two dots become one ridge: the inner one gives way as they meet
            if (i == 1) c = c.copy(alpha = c.alpha * (1f - turn))
            if (c.alpha <= 0.003f) continue
            val start = dotAt + (middle - reachOut - dotAt) * turn
            val end = dotAt + (middle + reachOut - dotAt) * turn
            drawLine(c, Offset(x(start), y), Offset(x(end), y), strokeWidth = dot * 2 + (ridgeStroke - dot * 2) * turn, cap = StrokeCap.Round)
        }
    }
}

/**
 * The scroll strip's touches, laid over the trackpad: give it the pad's size. Only the strip's
 * side and a little past it take touches; the rest of the pad is the pad's. The pad draws it
 * ([drawStrip]) from [look].
 */
@Composable
internal fun ScrollStrip(
    session: LinkSession,
    config: LinkConfig,
    look: StripLook,
    /** Nobody has used the pad yet: the strip is tugged once and springs back, so it is seen. */
    hint: Boolean,
    /** A finger is on the pad: the strip goes back to rest at once, and a roll of its own stops. */
    padBusy: Boolean,
    onTouched: () -> Unit,
    /** Where a finger holds the cloth, in the pad's px, for its dent; null once it has let go or come free. */
    onCloth: (Offset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val left = config.scrollStripLeft
    val side = if (left) Alignment.CenterStart else Alignment.CenterEnd
    val open = look.open
    val feel = config.scrollStripTicks

    var touching by remember { mutableStateOf(false) }
    // this touch has it free: pulled free, or it was free already when the finger came down
    var freed by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(false) }

    val busy by rememberUpdatedState(padBusy)
    val density = LocalDensity.current
    val pair = with(density) { rowPair(WEAVE_STEP_DP.dp.toPx()) }
    val give = with(density) { GIVE_DP.dp.toPx() }
    // Back to rest: the wave runs back, and the ridges glide the short way to where the pad's dots
    // are, so they become them with no jump; both on the same curve.
    suspend fun rest(spec: AnimationSpec<Float>) = coroutineScope {
        val to = round(look.wheel / pair) * pair
        launch { animate(look.wheel, to, animationSpec = spec) { v, _ -> look.wheel = v } }
        open.animateTo(0f, spec)
    }
    val awake = (touching && freed) || rolling
    LaunchedEffect(awake, padBusy) {
        when {
            awake -> {
                // from wherever the pull had it, so coming free carries straight on
                if (open.value < look.morph) open.snapTo(look.morph)
                open.animateTo(1f, if (reduced) tween(120) else turnFor(FREED_MS, 1f - open.value))
            }
            // the pad is being used: out of its way now, not after the wait
            padBusy -> rest(if (reduced) tween(80) else turnFor(GIVING_WAY_MS, open.value))
            open.value > 0f -> {
                delay(LINGER_MS)
                rest(if (reduced) tween(160) else turnFor(RESTING_MS, open.value))
            }
        }
    }
    LaunchedEffect(touching) {
        launch { look.grip.animateTo(if (touching) 1f else 0f, tween(if (touching) 260 else 420, easing = FastOutSlowInEasing)) }
        look.lit.animateTo(if (touching) 1f else 0f, tween(if (touching) 160 else 520))
    }
    // a first look: the seam is tugged a little way, as a finger would, and springs back
    LaunchedEffect(Unit) {
        if (!hint || reduced) return@LaunchedEffect
        delay(1_100)
        look.pullFrom = Float.NaN
        look.from = Float.NaN
        launch { look.grip.animateTo(0.7f, tween(320, easing = FastOutSlowInEasing)) }
        animate(0f, 1f, animationSpec = tween(420, easing = FastOutSlowInEasing)) { v, _ -> look.pull(give * 0.7f * v, 0.6f * v) }
        delay(80)
        if (!touching) {
            look.recoil(scope, reduced)
            look.grip.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier) {
        // where a finger takes hold of it: the seam and a little more at rest; free, all of it and a little past
        var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
        fun onPad(local: Offset): Offset = local + (placed?.takeIf { it.isAttached }?.positionInParent() ?: Offset.Zero)
        // how many of the creaks this pull has passed
        var creaked by remember { mutableStateOf(0) }
        Box(
            Modifier
                .align(side)
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val step = WEAVE_STEP_DP.dp.toPx()
                    val freeReach = stripBand(weaveMargin(constraints.maxWidth.toFloat(), step), step) + REACH_PAST_DP.dp.toPx()
                    val reach = maxOf(REST_REACH_DP.dp.toPx(), freeReach * open.value.coerceIn(0f, 1f)).toInt()
                    val p = measurable.measure(Constraints.fixed(reach, constraints.maxHeight))
                    layout(reach, constraints.maxHeight) { p.place(0, 0) }
                }
                .onPlaced { placed = it }
                // a stroke along the screen's edge is the strip's, not Android's "back"
                .systemGestureExclusion()
                .stripInput(
                    session, config,
                    isFree = { open.value > 0.5f },
                    onDown = { at, free ->
                        onTouched()
                        creaked = 0
                        freed = free
                        look.finger = at.y
                        if (!free) {
                            // the finger takes hold of the cloth: a dent, and the pull will start from here
                            look.from = at.y
                            look.pullFrom = at.y
                            onCloth(onPad(at))
                        }
                        if (feel) haptics.stripTouch()
                        touching = true
                    },
                    onMove = { at ->
                        look.finger = at.y
                        if (!freed) onCloth(onPad(at))
                    },
                    onPull = { along, progress ->
                        look.pull(clothGive(along, give), progress)
                        if (creaked < CREAKS.size && progress >= CREAKS[creaked]) {
                            if (feel) haptics.creak(creaked)
                            creaked++
                        } else if (creaked > 0 && progress < CREAKS[creaked - 1] - CREAK_EASE) {
                            creaked--
                        }
                    },
                    onFree = { y ->
                        freed = true
                        onCloth(null)
                        look.free(scope, reduced)
                        if (!reduced) look.ring(scope, y)
                        if (feel) haptics.comeFree()
                    },
                    onTurn = { px -> look.wheel += px },
                    onNotch = { firm -> if (feel) haptics.stripNotch(firm) },
                    onLift = { how ->
                        onCloth(null)
                        when (how) {
                            // A tap on the held seam does not free it: a soft "not like that" under
                            // the finger, and a little tug that shows a pull is what it wants. On the
                            // free strip a tap only keeps it free a little longer.
                            Lift.TAP -> if (!freed) {
                                look.nudge(scope, give * 0.35f, reduced)
                                if (feel) haptics.stripDecline()
                            }
                            Lift.FREE -> Unit
                            Lift.HELD, Lift.ACROSS -> {
                                if (look.slack != 0f || look.tension > 0f) {
                                    look.recoil(scope, reduced)
                                    if (feel && how == Lift.HELD) haptics.stripRecoil()
                                }
                            }
                        }
                        touching = false
                    },
                    onRolling = { rolling = it },
                    padBusy = { busy },
                ),
        )
    }
}

/**
 * The strip under a finger. Held by the cloth (it was not free when the finger came down), a
 * stroke along it pulls it toward coming free, [FREE_DP] of it, and scrolls nothing; past that it
 * is free. Free, one finger along it scrolls, a notch every [STRIP_DP_PER_NOTCH] dp at speed 1,
 * the way the pad's two fingers go ([LinkConfig.naturalScroll]). A stroke that sets off across
 * the strip rather than along it was meant for the pointer, and moves it as the pad would. A
 * flick rolls on after the finger lifts, when [LinkConfig.scrollStripRoll] is on; a finger on
 * the strip or the pad stops it. Other fingers on the strip are ignored.
 */
private fun Modifier.stripInput(
    session: LinkSession,
    config: LinkConfig,
    /** It is free already: a finger on it scrolls at once. */
    isFree: () -> Boolean,
    onDown: (at: Offset, free: Boolean) -> Unit,
    onMove: (at: Offset) -> Unit,
    /** Held by the cloth, pulled [along] px (either way), [progress] of the way to coming free. */
    onPull: (along: Float, progress: Float) -> Unit,
    /** Pulled free, the finger at [y]. */
    onFree: (y: Float) -> Unit,
    /** The wheel turned this many px of its surface. */
    onTurn: (Float) -> Unit,
    /** It passed a notch: tick this firmly. */
    onNotch: (Float) -> Unit,
    /** The finger lifted, or its stroke turned out to be the pointer's. */
    onLift: (Lift) -> Unit,
    onRolling: (Boolean) -> Unit,
    /** A finger is on the rest of the pad: a roll stops, as it would under the finger. */
    padBusy: () -> Boolean,
): Modifier = pointerInput(session, config) {
    val slop = viewConfiguration.touchSlop
    val notch = STRIP_DP_PER_NOTCH * density / config.scrollStripSpeed
    val freeAt = FREE_DP * density
    val sign = if (config.naturalScroll) 1f else -1f
    coroutineScope {
        var roll: Job? = null
        // the wheel's notches: kept from one stroke to the next, as a wheel's are
        val detents = Detents(notch)
        var tickedAt = 0L
        fun turn(px: Float, firm: Float, now: Long) {
            onTurn(px)
            if (detents.turn(px) > 0 && now - tickedAt >= TICK_GAP_MS) {
                tickedAt = now
                onNotch(firm)
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            roll?.cancel()
            var free = isFree()
            onDown(down.position, free)
            var travel = Offset.Zero
            // null until the stroke has gone far enough to say: true along the strip, false across it
            var along: Boolean? = null
            var lifted = false
            var speed = 0f          // notches per ms, smoothed, for the roll
            var scrolledAt = 0L
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // read before it is consumed: a consumed change reports no movement (and none for the
                    // strip itself moving under a still finger, as it widens or the pad moves: Compose
                    // gives the previous place in the strip's present place)
                    var delta = change?.positionChange() ?: Offset.Zero
                    event.changes.forEach { it.consume() }
                    if (change == null || !change.pressed) break
                    onMove(change.position)
                    if (along == null) {
                        travel += delta
                        if (hypot(travel.x, travel.y) <= slop) continue
                        along = abs(travel.x) <= abs(travel.y) * ACROSS
                        if (along == false) {
                            lifted = true
                            onLift(Lift.ACROSS)
                        } else if (!free) {
                            // held by the cloth: the stroke so far is the pull so far
                            onPull(travel.y, abs(travel.y) / freeAt)
                            continue
                        }
                        // the stroke up to here counts
                        delta = travel
                    } else if (along == true && !free) {
                        travel += delta
                        if (abs(travel.y) < freeAt) {
                            onPull(travel.y, abs(travel.y) / freeAt)
                            continue
                        }
                        // free: it scrolls from here on, not for the pull that freed it
                        free = true
                        onFree(change.position.y)
                        continue
                    }
                    val ms = (change.uptimeMillis - change.previousUptimeMillis).coerceIn(1, 40)
                    if (along == true) {
                        val step = sign * delta.y / notch
                        session.scroll(0f, step)
                        turn(delta.y, TICK_FIRM, change.uptimeMillis)
                        speed = speed * 0.6f + step / ms * 0.4f
                        scrolledAt = change.uptimeMillis
                    } else {
                        // as the pad moves the pointer: slow strokes stay precise, fast ones cross the screen
                        val dpPerMs = hypot(delta.x, delta.y) / density / ms
                        val gain = config.speed * (0.75f + 1.5f * dpPerMs.coerceAtMost(2.5f))
                        session.move(delta.x / density * gain, delta.y / density * gain)
                    }
                }
            } finally {
                if (!lifted) {
                    onLift(
                        when {
                            along == null -> Lift.TAP
                            along == true && !free -> Lift.HELD
                            else -> Lift.FREE
                        },
                    )
                }
            }
            // a finger that stopped before lifting meant to stop; a flick rolls on
            if (along == true && free && config.scrollStripRoll && SystemClock.uptimeMillis() - scrolledAt < 70 && abs(speed) > ROLL_MIN) {
                roll = launch {
                    onRolling(true)
                    try {
                        var v = speed
                        val from = abs(v)
                        while (abs(v) > ROLL_END && !padBusy()) {
                            val step = v * AppMotion.Link.FLING_FRAME_MS
                            session.scroll(0f, step)
                            turn(step * notch * sign, ROLL_TICK_FIRM * abs(v) / from, SystemClock.uptimeMillis())
                            v *= AppMotion.Link.FLING_KEEP
                            delay(AppMotion.Link.FLING_FRAME_MS)
                        }
                    } finally {
                        onRolling(false)
                    }
                }
            }
        }
    }
}
