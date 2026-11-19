package com.t3lluz.weave.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.t3lluz.weave.ui.util.LocalTickersPaused
import com.t3lluz.weave.ui.util.rememberIsResumed
import com.t3lluz.weave.ui.util.rememberReducedMotion
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * The trackpad's weave, waiting: the same field of faint dots, with rings of light running out
 * through it from [origin] (0..1 of the field, its middle by default). Each dot brightens into
 * [tone] as a ring's crest passes and settles again, so the cloth seems to carry a signal out
 * to the computer. Used wherever something is being reached for and there is nothing to show yet.
 *
 * Every dot is a point in one of a few brightness groups, redrawn each frame: no gradients and
 * no layers, so it costs next to nothing. Still (one ring, held) when motion is turned off.
 */
@Composable
fun WeaveWaves(
    modifier: Modifier = Modifier,
    tone: Color,
    origin: Offset = Offset(0.5f, 0.5f),
    /** How bright the resting dots are; the trackpad's own is 0.07. */
    rest: Float = 0.07f,
) {
    val reduced = rememberReducedMotion()
    val resumed = rememberIsResumed()
    val running = !reduced && resumed && !LocalTickersPaused.current
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val began = withFrameMillis { it }
        while (true) withFrameMillis { frame = it - began }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    // the dots' places, and a buffer per brightness group to sort them into each frame
    val field = remember { Field() }
    Canvas(modifier) {
        val step = WEAVE_DP * density
        field.lay(size.width, size.height, step)
        val at = Offset(size.width * origin.x, size.height * origin.y)
        // far enough for a ring to leave the field from wherever it starts
        val reach = max(hypot(at.x, at.y), max(hypot(size.width - at.x, at.y), max(hypot(at.x, size.height - at.y), hypot(size.width - at.x, size.height - at.y)))) + CREST_DP * density
        val crest = CREST_DP * density
        val t = if (running) frame / RING_MS.toFloat() else 0.3f
        field.sort { x, y ->
            val d = hypot(x - at.x, y - at.y)
            var glow = 0f
            for (k in 0 until RINGS) {
                // each ring a third of the way behind the last; it starts sharp and thins as it goes out
                val phase = (t + k.toFloat() / RINGS) % 1f
                val off = (d - phase * reach) / crest
                val strength = (1f - phase) * (1f - phase) * (phase * 14f).coerceAtMost(1f)
                glow = max(glow, exp(-off * off) * strength)
            }
            glow
        }
        paint.strokeWidth = DOT_DP * density * 2f
        drawIntoCanvas { canvas ->
            for (group in 0 until GROUPS) {
                val n = field.count[group]
                if (n == 0) continue
                val glow = group / (GROUPS - 1f)
                // a lit dot is the tone itself and a little larger; a resting one the weave's faint white
                paint.color = lerp(Color.White.copy(alpha = rest), tone.copy(alpha = 0.95f), glow).toArgb()
                paint.strokeWidth = DOT_DP * density * 2f * (1f + 0.5f * glow)
                canvas.nativeCanvas.drawPoints(field.points[group], 0, n * 2, paint)
            }
        }
    }
}

/** The dots of a field of some size, and where each one is sorted to by how brightly it glows. */
private class Field {
    private var w = 0f
    private var h = 0f
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    var points = Array(GROUPS) { FloatArray(0) }
        private set
    val count = IntArray(GROUPS)

    /** Lays the dots out for this size: the trackpad's own grid, every other row set in by half a step. */
    fun lay(width: Float, height: Float, step: Float) {
        if (width == w && height == h) return
        w = width
        h = height
        val x = ArrayList<Float>()
        val y = ArrayList<Float>()
        var row = 0
        var cy = step / 2
        while (cy < height) {
            var cx = if (row % 2 == 0) step / 2 else step
            while (cx < width) {
                x += cx
                y += cy
                cx += step
            }
            cy += step * 0.87f
            row++
        }
        xs = x.toFloatArray()
        ys = y.toFloatArray()
        points = Array(GROUPS) { FloatArray(xs.size * 2) }
    }

    /** Sorts every dot into the group its [glow] (0..1) belongs to. */
    inline fun sort(glow: (Float, Float) -> Float) {
        count.fill(0)
        for (i in xs.indices) {
            val group = (glow(xs[i], ys[i]).coerceIn(0f, 1f) * (GROUPS - 1) + 0.5f).toInt()
            val at = count[group] * 2
            points[group][at] = xs[i]
            points[group][at + 1] = ys[i]
            count[group]++
        }
    }
}

private const val WEAVE_DP = 13f
private const val DOT_DP = 1.5f
/** How wide a ring's crest is, and how long one takes from the origin to the far corner. */
private const val CREST_DP = 34f
private const val RING_MS = 2_600
private const val RINGS = 3
private const val GROUPS = 9
