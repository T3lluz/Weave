package com.t3lluz.weave.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Centralized haptic feedback helper optimised for Pixel devices.
 *
 * Uses Android 12+ `CONFIRM`, `REJECT`, `GESTURE_START`, `GESTURE_END`,
 * `SEGMENT_TICK` and `CLOCK_TICK` constants when available,
 * falling back to standard Compose [HapticFeedbackType] on older builds.
 */
class HapticHelper(
    private val view: View,
    private val feedback: HapticFeedback,
) {
    /* ── Light tap — e.g. chip selection, nav item, toggle ───────── */
    fun tick() {
        if (Build.VERSION.SDK_INT >= 34) {
            // SEGMENT_TICK (API 34) is the subtlest, crispest tick — perfect for Pixel
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /* ── Medium click — e.g. button press, card expand ───────────── */
    fun click() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /* ── Heavy / confirm — e.g. successful log, shutter capture ──── */
    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /* ── Reject / error — e.g. validation failure ────────────────── */
    fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /* ── Toggle on / off — expand or collapse cards ──────────────── */
    fun toggleOn() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_ON)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun toggleOff() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_OFF)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /* ── Gesture start/end — e.g. drag or long-press begin/end ───── */
    fun gestureStart() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /* ── A drag crossing its threshold, as a swiped notification detaches on a Pixel ── */
    fun thresholdActivate() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /* ── …and back under it ─────────────────────────────────────── */
    fun thresholdDeactivate() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun gestureEnd() {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}

/**
 * Remember a [HapticHelper] scoped to the current composition.
 * Usage:
 * ```
 * val haptics = rememberHaptics()
 * Button(onClick = { haptics.click(); doSomething() }) { … }
 * ```
 */
@Composable
fun rememberHaptics(): HapticHelper {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    return remember(view, feedback) { HapticHelper(view, feedback) }
}




/** One moment of the grain: how strong (0..1) and how sharp (0 a low hum, 1 a high hiss). */
data class Step(val strength: Float, val sharp: Float)

/** A stretch of grain to play: [steps], each lasting [stepMs]. */
data class Stretch(val steps: List<Step>, val stepMs: Int)

/**
 * What a finger sliding over paper feels: not ticks but one unbroken, faint hiss whose pitch
 * and strength wander all the time, there while the finger moves and gone when it stops.
 *
 * It is played as one long stretch that is simply cut off when the finger rests ([restMs]
 * after its last move) or lifts. A new stretch is only started when the old one is about to
 * run out, or when the finger has changed its pace enough to be felt: a faster stroke is
 * stronger, as paper is louder under a faster hand.
 *
 * [chance] gives numbers from 0 to 1 and [now] the time in ms; both are handed in so this can
 * be tested without a phone.
 */
class Grain(
    private val stepMs: Int = 20,
    private val chance: () -> Float = { kotlin.random.Random.nextFloat() },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private var travelled = 0f
    private var speed = 0f          // dp a millisecond, smoothed
    private var lastAt = 0L
    private var playing = false
    private var startedAt = 0L
    private var endsAt = 0L
    private var pace = 0

    /** How long after the finger's last move the grain is cut off. */
    val restMs: Long get() = REST_MS

    /** The finger went [dp] further: a stretch to start playing now (in place of what plays), or null to let it be. */
    fun glide(dp: Float): Stretch? {
        if (dp <= 0f) return null
        val at = now()
        val since = (at - lastAt).coerceIn(1, 60)
        lastAt = at
        speed = speed * 0.7f + dp / since * 0.3f
        travelled += dp
        val was = pace
        // slow, steady or fast, with some give so it doesn't flip at the border
        val to = when {
            speed > FAST * (if (was == 2) 0.8f else 1.2f) -> 2
            speed > STEADY * (if (was >= 1) 0.8f else 1.2f) -> 1
            else -> 0
        }
        if (playing) {
            val running = at - startedAt
            val renew = at >= endsAt - 2 * stepMs || (to != was && running >= PACE_HOLDS_MS)
            if (!renew) return null
        } else if (travelled < MOVED_DP) {
            return null   // a resting finger's tremble is not a stroke
        }
        travelled = 0f
        pace = to
        playing = true
        startedAt = at
        endsAt = at + STEPS * stepMs.toLong()
        val strong = STRENGTH[to]
        return Stretch(List(STEPS) { Step(strong * (0.55f + 0.45f * chance()), SHARP_FROM + (1f - SHARP_FROM) * chance()) }, stepMs)
    }

    /** The grain was cut off (the finger rested or lifted): the next move starts it again. */
    fun rested() {
        playing = false
        travelled = 0f
        speed = 0f
        pace = 0
    }

    companion object {
        /** Steps in a stretch: two seconds' worth, cut off long before by a finger that stops. */
        const val STEPS = 100
        const val REST_MS = 55L
        /** How far the finger has to go before the grain starts. */
        const val MOVED_DP = 0.5f
        /** Where steady and fast begin, in dp a millisecond. */
        const val STEADY = 0.22f
        const val FAST = 0.8f
        /** How long a stretch plays at least before a change of pace starts another. */
        const val PACE_HOLDS_MS = 140L
        /** How strong the grain is under a slow, a steady and a fast finger. */
        val STRENGTH = floatArrayOf(0.14f, 0.2f, 0.28f)
        /** The hiss stays high: from here up to as sharp as the motor goes. */
        const val SHARP_FROM = 0.55f
    }
}

/**
 * Paper under the finger: the trackpad's surface, felt. Phones that can shape a vibration's
 * strength and pitch over time (Android 16's envelopes; Pixels can) get the real thing. Others
 * with a motor whose strength can be set get the same grain at the motor's own pitch. The rest
 * get nothing, since a plain buzz is not what paper feels like. It is touch feedback to the
 * system, so turning that off in the phone's settings turns this off too.
 */
class PaperFeel(context: android.content.Context) {
    private val vibrator: android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    private val envelopes = Build.VERSION.SDK_INT >= 36 && runCatching { vibrator?.areEnvelopeEffectsSupported() == true }.getOrDefault(false)
    private val can = envelopes || vibrator?.hasAmplitudeControl() == true
    private val grain = Grain(stepMs = stepMs())
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val rest = Runnable { stop() }

    /** The shortest moment this phone's motor can shape; 20 ms is what Pixels say, and what the plain kind gets. */
    private fun stepMs(): Int {
        if (!envelopes || Build.VERSION.SDK_INT < 36) return 20
        val least = runCatching { vibrator?.envelopeEffectInfo?.minControlPointDurationMillis }.getOrNull() ?: 20L
        return least.coerceIn(10L, 40L).toInt()
    }

    fun glide(dp: Float) {
        if (!can) return
        main.removeCallbacks(rest)
        main.postDelayed(rest, grain.restMs)
        grain.glide(dp)?.let(::play)
    }

    fun lift() {
        if (!can) return
        main.removeCallbacks(rest)
        stop()
    }

    private fun stop() {
        grain.rested()
        runCatching { vibrator?.cancel() }
    }

    @androidx.annotation.RequiresApi(36)
    private fun envelope(stretch: Stretch): android.os.VibrationEffect {
        val made = android.os.VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(stretch.steps.first().sharp)
        for (step in stretch.steps) made.addControlPoint(step.strength.coerceIn(0f, 1f), step.sharp.coerceIn(0f, 1f), stretch.stepMs.toLong())
        // an envelope ends at rest; it is cut off or replaced long before this is reached
        made.addControlPoint(0f, stretch.steps.last().sharp.coerceIn(0f, 1f), stretch.stepMs.toLong())
        return made.build()
    }

    private fun plain(stretch: Stretch): android.os.VibrationEffect {
        // half the step, twice the steps: the motor's own pitch can't wander, so its strength does more of it
        val each = (stretch.stepMs / 2).coerceAtLeast(5).toLong()
        val times = LongArray(stretch.steps.size * 2) { each }
        val strengths = IntArray(stretch.steps.size * 2) { i ->
            val step = stretch.steps[i / 2]
            (255 * step.strength * (if (i % 2 == 0) 1f else step.sharp) * PLAIN_SCALE).toInt().coerceIn(1, 255)
        }
        return android.os.VibrationEffect.createWaveform(times, strengths, -1)
    }

    private fun play(stretch: Stretch) {
        val v = vibrator ?: return
        try {
            val effect = if (envelopes && Build.VERSION.SDK_INT >= 36) envelope(stretch) else plain(stretch)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        } catch (_: RuntimeException) {
            // a motor that refuses is not worth a crash
        }
    }

    private companion object {
        /** A motor at its own pitch is far louder than a hiss above it: the plain kind is turned down to match. */
        const val PLAIN_SCALE = 0.35f
    }
}
