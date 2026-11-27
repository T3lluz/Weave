package com.t3lluz.weave.ui.screens.link

import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import com.t3lluz.weave.data.link.MonitorEdge
import com.t3lluz.weave.data.link.pushing
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.foundation.Canvas
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.t3lluz.weave.ui.theme.TextPrimary
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.data.link.LinkVideo
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.util.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * The old monitor's picture, held while the stream moves to the next one: the image, where it
 * sat in the box when it was taken, and the way the new monitor lies ([dx], [dy]: -1, 0 or 1).
 */
private class Still(
    val image: ImageBitmap,
    val fitW: Float,
    val fitH: Float,
    val zoom: Float,
    val centerX: Float,
    val centerY: Float,
    val dx: Int,
    val dy: Int,
)

/**
 * The laptop's screen. When the stream moves to another monitor the picture swipes the way the
 * pointer went: the laptop keeps the old monitor coming until the new one is ready, this grabs
 * a still of the old picture at that moment ([LinkVideo.beforeSwitch]), and once the new
 * monitor's first frame is drawn ([LinkVideo.switched]) the still slides out as the new picture
 * slides in from the other side. A new quality on the same monitor fades across instead. All
 * of it moves in the layer, never in composition.
 */
@Composable
internal fun LinkPreview(
    session: LinkSession,
    geometry: PreviewGeometry,
    zoom: Float?,
    fill: Boolean,
    error: String?,
    /** Shown over the picture while it is gone: "Reconnecting", say. */
    note: String?,
    modifier: Modifier = Modifier,
    /** Asks for the picture again after it stopped with [error]. */
    onRetry: () -> Unit = { session.retryVideo() },
    /** Draw the pointer here, ahead of the picture, which leaves the laptop's own out ([LocalPointer]). */
    drawPointer: Boolean = false,
    /** The drawn pointer's size, 1 being the laptop's own at the picture's scale. */
    pointerSize: Float = 1f,
) {
    val video = session.video
    val size by video.size.collectAsState()
    val live by video.live.collectAsState()
    val switched by video.switched.collectAsState()
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    var view by remember { mutableStateOf<LinkTextureView?>(null) }
    var still by remember { mutableStateOf<Still?>(null) }
    // between grabbing the still and the new monitor's first frame: the new picture is kept out of sight
    var waiting by remember { mutableStateOf(false) }
    // the switch the still is for; it has landed once `switched` reaches it
    var awaited by remember { mutableIntStateOf(0) }
    val slide = remember { Animatable(1f) }

    // The peek of the monitor the pointer is pushed toward ([MonitorEdge]): how far it is in (0 to
    // about a sixth of the box), which way, and which monitor. When the edge lets the pointer
    // through, the peek holds, a little further in, until that monitor's picture lands, and the
    // slide carries on from there ([startAt]) instead of starting over.
    val edge = session.edge
    val follow = session.state.collectAsState().value.followPointer
    val peek = remember { mutableFloatStateOf(0f) }
    var peekDir by remember { mutableStateOf(0 to 0) }
    var peekName by remember { mutableStateOf<String?>(null) }
    var heldPeek by remember { mutableFloatStateOf(0f) }
    var heldUntil by remember { mutableLongStateOf(0L) }
    var startAt by remember { mutableFloatStateOf(0f) }
    // and where the picture it leaves had got to, which carries on from there too
    var outAt by remember { mutableFloatStateOf(0f) }
    // the peek's speed, for its spring
    val peekSpeed = remember { floatArrayOf(0f) }

    DisposableEffect(video, geometry, reduced) {
        val grab: (Int, Int, Int) -> Unit = { dx, dy, id ->
            // already drawn by the time this ran (a slow main thread): nothing to slide from
            val late = video.switched.value >= id
            val shot = if (late) null else view?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }?.bitmap
            // kept as the monitor's last picture, for a peek of it later
            session.stillKey()?.let { name -> shot?.let { video.stills[name] = it } }
            // a switch the pointer was pushed into, the way the peek shows: both pictures carry on from where the peek had them
            val q = peek.floatValue
            if (!reduced && shot != null && peekDir == (dx to dy) && q > 0.005f) {
                startAt = (q * PEEK_OVER).coerceAtMost(0.9f)
                outAt = (q * PEEK_OUT).coerceAtMost(startAt)
            } else {
                startAt = 0f
                outAt = 0f
            }
            still = shot?.let {
                Still(
                    it.asImageBitmap(), geometry.fitW, geometry.fitH, geometry.zoomState.floatValue,
                    geometry.centerX.floatValue, geometry.centerY.floatValue,
                    // with animations turned down, a switch is a plain crossfade: no travel, no blur
                    if (reduced) 0 else dx, if (reduced) 0 else dy,
                )
            }
            awaited = id
            waiting = shot != null
            // nothing to slide from, so nothing to feel on the way: the one soft step
            if (shot == null) haptics.slide()
        }
        video.beforeSwitch = grab
        // a rotation puts the new preview in before the old one leaves: only unhook our own
        onDispose { if (video.beforeSwitch === grab) video.beforeSwitch = null }
    }
    LaunchedEffect(switched, awaited) {
        if (!waiting || switched < awaited) return@LaunchedEffect
        val mine = still
        slide.snapTo(startAt)
        waiting = false
        // the new picture stands where the peek did: the peek is done
        peek.floatValue = 0f
        peekSpeed[0] = 0f
        heldUntil = 0L
        if (reduced || mine == null || (mine.dx == 0 && mine.dy == 0)) {
            // one picture fading into the other: nothing travels, one soft step
            haptics.slide()
            slide.animateTo(1f, AppMotion.Link.swap())
        } else {
            // the slide under the thumb: ticks timed to this spring, and a soft step as it comes to rest
            haptics.slideOver()
            slide.animateTo(1f, AppMotion.Link.swipe())
        }
        // a switch that came on top of this one has its own still: leave it
        if (still === mine) {
            still = null
            startAt = 0f
            outAt = 0f
        }
    }
    // the picture stopped: drop any still from a switch in flight, so the cover shows why at once
    LaunchedEffect(error) {
        if (error != null && (waiting || still != null)) {
            waiting = false
            still = null
            slide.snapTo(1f)
        }
    }
    // The peek follows the push each frame: in quickly, back a little slower, held while the edge
    // has let the pointer through and its monitor's picture is on its way.
    LaunchedEffect(edge, follow, reduced) {
        var last = 0L
        var freed = edge.freedAt
        while (true) withFrameNanos { t ->
            // the edge let the pointer through: the peek pops on a little and holds for the new picture
            if (edge.freedAt != freed) {
                freed = edge.freedAt
                if (follow && !reduced) {
                    peekDir = edge.dirX to edge.dirY
                    peekName = edge.beyond
                    heldPeek = (peek.floatValue + PEEK_POP).coerceAtMost(PEEK_MAX + PEEK_POP)
                    heldUntil = android.os.SystemClock.uptimeMillis() + PEEK_HOLD_MS
                }
            }
            val dt = if (last == 0L) 16f else ((t - last) / 1_000_000f).coerceIn(1f, 50f)
            last = t
            val now = android.os.SystemClock.uptimeMillis()
            val target = when {
                !follow || reduced -> 0f
                waiting || still != null -> peek.floatValue
                now < heldUntil -> heldPeek
                edge.pushing(now) -> {
                    peekDir = edge.dirX to edge.dirY
                    peekName = edge.beyond
                    peekFor(edge.push)
                }
                else -> 0f
            }
            // a spring, a little under-damped: the peek leans in with a small bounce, pops on as the
            // edge lets go, and wobbles back past rest when the push is given up
            var q = peek.floatValue
            var v = peekSpeed[0]
            if (waiting || still != null) {
                v = 0f
            } else {
                val stiff = if (target > 0f) PEEK_STIFF_IN else PEEK_STIFF_OUT
                val damp = 2f * PEEK_DAMPING * kotlin.math.sqrt(stiff)
                var left = dt / 1000f
                while (left > 0f) {
                    val h = minOf(left, 0.004f)
                    v += (stiff * (target - q) - damp * v) * h
                    q += v * h
                    left -= h
                }
                q = q.coerceIn(-PEEK_BELOW, PEEK_MAX * 2f)
                if (target == 0f && abs(q) < 0.0006f && abs(v) < 0.004f) {
                    q = 0f
                    v = 0f
                }
            }
            peek.floatValue = q
            peekSpeed[0] = v
            if (q == 0f && !waiting && still == null && peekName != null) peekName = null
        }
    }

    // a switch whose new picture never comes lets go of the old one; each switch gets its own wait
    LaunchedEffect(waiting, awaited) {
        if (!waiting) return@LaunchedEffect
        delay(AppMotion.Link.SWIPE_WAIT_MS)
        if (waiting) {
            waiting = false
            still = null
            slide.snapTo(1f)
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val aspect = if (size.first > 0 && size.second > 0) size.first.toFloat() / size.second else 16f / 9f
        geometry.layout(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), aspect, zoom, fill)
        // the view follows the pointer: the one drawn here when there is one, as it is where the finger put it
        val pointer = session.localPointer
        val pointerTick = remember { mutableLongStateOf(0L) }
        LaunchedEffect(geometry, session, drawPointer) {
            while (true) withFrameNanos {
                if (drawPointer && pointer.shown) {
                    geometry.follow(pointer.x, pointer.y)
                    if (pointerTick.longValue != pointer.version) pointerTick.longValue = pointer.version
                } else {
                    geometry.follow(session.cursorX, session.cursorY)
                }
            }
        }
        val fitW = geometry.fitW
        val fitH = geometry.fitH
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()

        // the old monitor's picture, under the new one, as the switch carries it away
        still?.let { s ->
            Image(
                bitmap = s.image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(with(density) { s.fitW.toDp() }, with(density) { s.fitH.toDp() })
                    .graphicsLayer {
                        // a switch that carries on from the peek starts where it had this picture, and catches up
                        val p = when {
                            startAt <= 0f -> if (waiting) 0f else slide.value
                            waiting -> outAt
                            else -> outAt + (1f - outAt) * ((slide.value - startAt) / (1f - startAt)).coerceIn(0f, 1f)
                        }
                        var tx = (0.5f - s.centerX) * s.fitW * s.zoom
                        var ty = (0.5f - s.centerY) * s.fitH * s.zoom
                        var depth = 1f
                        if (s.dx == 0 && s.dy == 0) {
                            alpha = (1f - p).coerceIn(0f, 1f)
                        } else {
                            // leaving the way the pointer went
                            tx -= s.dx * boxW * p
                            ty -= s.dy * boxH * p
                            depth = 1f - AppMotion.Link.SWIPE_DEPTH * p.coerceIn(0f, 1f)
                            alpha = (1f - p * 0.35f).coerceIn(0f, 1f)
                        }
                        scaleX = s.zoom * depth
                        scaleY = s.zoom * depth
                        translationX = tx
                        translationY = ty
                        motionBlur(s, if (waiting) 0f else slide.velocity)
                    },
            )
        }

        AndroidView(
            factory = { LinkTextureView(it, video).also { v -> view = v } },
            modifier = Modifier
                .align(Alignment.Center)
                .size(with(density) { fitW.toDp() }, with(density) { fitH.toDp() })
                .graphicsLayer {
                    val z = geometry.zoomState.floatValue
                    var tx = (0.5f - geometry.centerX.floatValue) * fitW * z
                    var ty = (0.5f - geometry.centerY.floatValue) * fitH * z
                    var depth = 1f
                    alpha = 1f
                    val s = still
                    val p = slide.value
                    if (waiting) {
                        alpha = 0f
                    } else if (s == null) {
                        // pushed against the edge: it gives way a little to the next monitor coming over it
                        val q = peek.floatValue * PEEK_OUT
                        if (q != 0f) {
                            tx -= peekDir.first * boxW * q
                            ty -= peekDir.second * boxH * q
                            depth = 1f - AppMotion.Link.SWIPE_DEPTH * q.coerceAtLeast(0f)
                        }
                    } else {
                        // until the slide ends (the spring is critically damped: it never passes the middle)
                        if (s.dx == 0 && s.dy == 0) {
                            alpha = p.coerceIn(0f, 1f)
                        } else {
                            // arriving from the side the pointer went to
                            tx += s.dx * boxW * (1f - p)
                            ty += s.dy * boxH * (1f - p)
                            depth = 1f - AppMotion.Link.SWIPE_DEPTH * (1f - p).coerceIn(0f, 1f)
                        }
                    }
                    scaleX = z * depth
                    scaleY = z * depth
                    translationX = tx
                    translationY = ty
                    motionBlur(s, if (waiting || s == null) 0f else slide.velocity)
                },
        )

        // The peek: the next monitor's last picture (or the weave, for one not seen yet) coming over
        // the current one from the side the pointer is pushed to, showing the part of it by the edge.
        // It comes in further than the current picture gives way, so it lies over it, a card with
        // a shadow, leaning toward you as it enters and flattening as it comes on. It ends flat and
        // square where the slide picks it up, so the slide can take it over.
        peekName?.let { name ->
            val shot = remember(name, awaited) { video.stills[name]?.asImageBitmap() }
            val (px, py) = peekDir
            val corner = with(density) { PEEK_CORNER_DP.dp.toPx() }
            val lift = with(density) { PEEK_SHADOW_DP.dp.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val at = if (waiting) startAt else peek.floatValue * PEEK_OVER
                        val shown = if (waiting) startAt > 0f else still == null && at > 0f
                        alpha = if (shown) 1f else 0f
                        translationX = px * boxW * (1f - at)
                        translationY = py * boxH * (1f - at)
                        val depth = 1f - AppMotion.Link.SWIPE_DEPTH * (1f - at)
                        scaleX = depth
                        scaleY = depth
                        // leaning in while it peeks, flat by the time it is all the way on
                        val lean = (1f - at / ((PEEK_MAX + PEEK_POP) * PEEK_OVER)).coerceIn(0f, 1f)
                        cameraDistance = 14f * this.density
                        rotationY = -px * PEEK_LEAN_DEG * lean
                        rotationX = py * PEEK_LEAN_DEG * lean
                        shape = RoundedCornerShape(corner * lean)
                        clip = true
                        shadowElevation = lift * lean
                    },
            ) {
                if (shot != null) {
                    Image(
                        bitmap = shot, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = when {
                            px > 0 -> Alignment.CenterStart
                            px < 0 -> Alignment.CenterEnd
                            py > 0 -> Alignment.TopCenter
                            else -> Alignment.BottomCenter
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        com.t3lluz.weave.ui.components.WeaveWaves(
                            Modifier.matchParentSize(), tone = LinkScreenTone,
                            origin = Offset(if (px > 0) 0f else if (px < 0) 1f else 0.5f, if (py > 0) 0f else if (py < 0) 1f else 0.5f),
                        )
                    }
                }
            }
        }

        // the pointer, drawn where the finger has put it; the picture catches up under it
        if (drawPointer) {
            val monW = session.state.collectAsState().value.shownMonitor?.let { m -> (m.layoutWidth.takeIf { it > 0 } ?: m.width).toFloat() } ?: 1920f
            val arrow = rememberArrow()
            Canvas(Modifier.fillMaxSize()) {
                pointerTick.longValue
                if (!pointer.shown || !live || waiting || still != null) return@Canvas
                var at = geometry.toBox(pointer.x, pointer.y)
                // the picture gives way to the peek: the pointer, held at its edge, goes with it
                val q = peek.floatValue * PEEK_OUT
                if (q != 0f) {
                    val depth = 1f - AppMotion.Link.SWIPE_DEPTH * q.coerceAtLeast(0f)
                    at = Offset(
                        boxW / 2 + (at.x - boxW / 2) * depth - peekDir.first * boxW * q,
                        boxH / 2 + (at.y - boxH / 2) * depth - peekDir.second * boxH * q,
                    )
                }
                // the laptop's arrow is 48 of its pixels tall: that, at the picture's scale, kept easy to see
                val scale = geometry.fitW * geometry.zoomState.floatValue / monW
                val tall = (ARROW_LAPTOP_PX * scale).coerceIn(20.dp.toPx(), 34.dp.toPx()) * pointerSize
                if (arrow != null) drawArrow(arrow, at, tall) else drawPointer(at, tall * 0.55f)
            }
        }

        // the cover lifts when the first frame is decoded
        AnimatedVisibility(visible = !live && still == null, enter = fadeIn(AppMotion.Link.leave()), exit = fadeOut(AppMotion.Link.picture())) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                // Waiting for the picture: the trackpad's weave, with rings of light running out
                // through it as the phone reaches for the screen. Not behind an error: nothing is
                // being reached for then.
                if (error == null || note != null) {
                    com.t3lluz.weave.ui.components.WeaveWaves(Modifier.matchParentSize(), tone = LinkScreenTone)
                }
                when {
                    note != null -> Text(
                        note, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(error, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        PillButton(icon = AppIcons.Refresh, label = "Try again", accent = LinkScreenTone, onClick = onRetry)
                    }
                    else -> Unit   // the weave says it
                }
            }
        }
    }
}

/**
 * Blurs a picture along the way it is swiping, as much as it is moving: none at rest, the
 * most at the middle of the swipe. A fade between qualities has none. Android 12+.
 */
private fun GraphicsLayerScope.motionBlur(still: Still?, velocity: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || still == null || (still.dx == 0 && still.dy == 0)) {
        renderEffect = null
        return
    }
    val amount = (kotlin.math.abs(velocity) / BLUR_AT_SPEED).coerceIn(0f, 1f)
    val r = AppMotion.Link.SWIPE_BLUR_DP * density * amount
    renderEffect = if (r < 0.5f) null else BlurEffect(if (still.dx != 0) r else 0.01f, if (still.dy != 0) r else 0.01f, TileMode.Clamp)
}

/**
 * The peek of the next monitor (Fredde asked for it playful, the next screen coming over the
 * current one, 2026-11-21). How far it has come, as [peekFor] gives it for the push: at most
 * this share of the box, and [PEEK_POP] more once the edge lets go, held up to [PEEK_HOLD_MS]
 * for the new picture. The next monitor comes in [PEEK_OVER] times that, the current one gives
 * way [PEEK_OUT] times it, so the next lies over the current. It moves on a spring a little
 * under-damped, in and out, and may wobble [PEEK_BELOW] past rest on its way back.
 */
private const val PEEK_MAX = 0.16f
private const val PEEK_POP = 0.06f
private const val PEEK_HOLD_MS = 1_300L
private const val PEEK_OVER = 1.4f
private const val PEEK_OUT = 0.55f
private const val PEEK_STIFF_IN = 260f
private const val PEEK_STIFF_OUT = 190f
private const val PEEK_DAMPING = 0.55f
private const val PEEK_BELOW = 0.025f

/** The peeking card: how far it leans toward you as it enters, its corners and its shadow, all gone by the time it is on. */
private const val PEEK_LEAN_DEG = 11f
private const val PEEK_CORNER_DP = 14f
private const val PEEK_SHADOW_DP = 18f

/** How far in the peek is for a push (0 to 1): quickly at first, less as it goes, as a give does. */
internal fun peekFor(push: Float): Float = PEEK_MAX * (1f - exp(-push.coerceIn(0f, 1f) * 2.2f)) / (1f - exp(-2.2f))

/** How fast a swipe (in widths a second) has to move for the most blur. */
private const val BLUR_AT_SPEED = 4.5f

/** Shows the decoder's own SurfaceTexture, and leaves it alive when this view goes away. */
@SuppressLint("ViewConstructor")
internal class LinkTextureView(context: Context, private val video: LinkVideo) : TextureView(context), TextureView.SurfaceTextureListener {
    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (surfaceTexture !== video.texture) {
            try {
                setSurfaceTexture(video.texture)
            } catch (_: RuntimeException) {
                // still held by a view that is on its way out; the next attach takes it
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /** False keeps the texture: the decoder draws into it across views. */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = surface !== video.texture
}

/** The classic arrow, its tip at [tip], [tall] high: white with a dark edge and a soft shadow, as a desktop draws it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPointer(tip: androidx.compose.ui.geometry.Offset, tall: Float) {
    val k = tall / 19.5f
    val arrow = androidx.compose.ui.graphics.Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x, tip.y + 17f * k)
        lineTo(tip.x + 4.2f * k, tip.y + 13f * k)
        lineTo(tip.x + 7.2f * k, tip.y + 19.5f * k)
        lineTo(tip.x + 9.8f * k, tip.y + 18.4f * k)
        lineTo(tip.x + 6.9f * k, tip.y + 12.1f * k)
        lineTo(tip.x + 12.4f * k, tip.y + 12.1f * k)
        close()
    }
    translate(1.2f * k, 1.6f * k) { drawPath(arrow, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)) }
    drawPath(arrow, androidx.compose.ui.graphics.Color.White)
    drawPath(
        arrow, androidx.compose.ui.graphics.Color.Black,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * k, join = androidx.compose.ui.graphics.StrokeJoin.Round),
    )
}

/** How tall the arrow is on the laptop, in its pixels: the theme's size-24 arrow is 34 by 48. */
private const val ARROW_LAPTOP_PX = 48f

/**
 * The pointer's picture: the macOS arrow from the laptop's own cursor theme (macOS27-GoldenGate),
 * 136 by 194 with its tip at 24, 24, drawn at its size-96 resolution so it stays sharp.
 */
private class Arrow(val image: androidx.compose.ui.graphics.ImageBitmap, val hotX: Float, val hotY: Float)

@androidx.compose.runtime.Composable
private fun rememberArrow(): Arrow? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        runCatching {
            context.assets.open("cursors/arrow.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { Arrow(it.asImageBitmap(), 24f, 24f) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(arrow: Arrow, tip: androidx.compose.ui.geometry.Offset, tall: Float) {
    val k = tall / arrow.image.height
    val w = (arrow.image.width * k).toInt().coerceAtLeast(1)
    val h = (arrow.image.height * k).toInt().coerceAtLeast(1)
    drawImage(
        arrow.image,
        dstOffset = androidx.compose.ui.unit.IntOffset((tip.x - arrow.hotX * k).toInt(), (tip.y - arrow.hotY * k).toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(w, h),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
    )
}
