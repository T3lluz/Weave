package com.t3lluz.weave.ui.screens.link

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 */
@Composable
internal fun LinkTrackpad(
    viewModel: LinkViewModel,
    config: LinkConfig,
    hint: Boolean,
    buttons: Boolean,
    modifier: Modifier = Modifier,
    /** A finger is on the pad (true) or has lifted (false). */
    onTouch: (Boolean) -> Unit = {},
) {
    val session = viewModel.session
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val room = roomColor(LinkHandsTone)
    val shape = RoundedCornerShape(28.dp)
    // a dent per finger: where each is and how far the cloth is pressed there, read only while drawing
    val dents = remember { List(DENTS) { Dent() } }
    var anyFinger by remember { mutableStateOf(false) }
    dents.forEach { dent ->
        LaunchedEffect(dent.touching) {
            if (dent.touching) dent.depth.animateTo(1f, AppMotion.Link.dentIn()) else dent.depth.animateTo(0f, AppMotion.Link.dentOut())
        }
    }

    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .then(if (reduced) Modifier else Modifier.clothDent(dents))
                .background(room)
                .drawWithCache {
                    // the weave: one path of dots, made once for the pad's size
                    val step = WEAVE_DP * density
                    val r = WEAVE_DOT_DP * density
                    val weave = Path()
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
                    onDrawBehind { drawPath(weave, Color.White.copy(alpha = 0.07f)) }
                }
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
                    onTick = haptics::tick, onHold = haptics::click, onTouched = { viewModel.touched = true },
                    onFingers = { fingers ->
                        follow(dents, fingers)
                        if (anyFinger != fingers.isNotEmpty()) onTouch(fingers.isNotEmpty())
                        anyFinger = fingers.isNotEmpty()
                    },
                ),
        ) {
            // what the pad does, until a finger has been on it
            AnimatedVisibility(
                visible = hint,
                enter = fadeIn(AppMotion.fadeTween()), exit = fadeOut(AppMotion.revealTween()),
                modifier = Modifier.align(Alignment.Center).padding(bottom = 56.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Gesture(AppIcons.MousePointer, "Tap", "to click")
                    Gesture(AppIcons.Move, "Hold", "to drag")
                    Gesture(AppIcons.SwapVertical, "Two fingers", "to scroll")
                }
            }
        }
        AnimatedVisibility(
            visible = buttons,
            enter = fadeIn(AppMotion.fadeTween()), exit = fadeOut(AppMotion.fadeTween()),
            // 8 dp in from the pad's sides and foot, their outer corners the pad's (28) less that
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton(session, "left", "Left", RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 20.dp), Modifier.weight(1f))
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
private fun PadButton(session: LinkSession, button: String, label: String, shape: RoundedCornerShape, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    var down by remember { mutableStateOf(false) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(down) { press.animateTo(if (down) 1f else 0f, AppMotion.Link.press()) }
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
            .mouseButton(session, button, onTick = haptics::tick, onPressed = { down = it }),
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
