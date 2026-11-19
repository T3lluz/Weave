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




/** One soft tick of the grain: how firm (0..1), and how long after the one before it ends. */
data class Tick(val firm: Float, val waitMs: Int)

/**
 * What a finger sliding over paper feels: a fine grain that never quite stops while it moves.
 * It is played as short runs of soft ticks, each run handed over just before the last one
 * ends, so there is no gap to feel between them. Within a run every tick is a little firmer or
 * fainter than its neighbour and a hair nearer or further, which is what makes it a texture
 * and not a buzz. A faster stroke is firmer, as paper is louder under a faster hand. A finger
 * that rests gets nothing: a run is only started once it has moved on.
 *
 * [tickMs] is how long the phone's motor takes over one tick. [chance] gives numbers from 0 to
 * 1 and [now] the time in ms; both are handed in so this can be tested without a phone.
 */
class Grain(
    private val tickMs: Int = 12,
    private val chance: () -> Float = { kotlin.random.Random.nextFloat() },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private var travelled = 0f
    private var speed = 0f          // dp a millisecond, smoothed
    private var lastAt = 0L
    private var nextAt = 0L

    /** The finger went [dp] further: the run of ticks to play now, or null while the last one still plays. */
    fun glide(dp: Float): List<Tick>? {
        if (dp <= 0f) return null
        val at = now()
        val since = (at - lastAt).coerceIn(1, 60)
        lastAt = at
        speed = speed * 0.6f + dp / since * 0.4f
        travelled += dp
        // not while the last run plays, and not for a finger that has barely moved since it started
        if (at < nextAt || travelled < MOVED_DP) return null
        travelled = 0f
        val firm = FAINT + (FIRM - FAINT) * (speed / FAST_DP_PER_MS).coerceIn(0f, 1f)
        val run = List(RUN) { Tick(firm * (0.62f + 0.38f * chance()), (chance() * GAP_MS).toInt()) }
        // the next run starts a moment before this one is over, so they join
        nextAt = at + run.sumOf { tickMs + it.waitMs } - JOIN_MS
        return run
    }

    /** The finger lifted: the next stroke starts afresh, at once. */
    fun lift() {
        travelled = 0f
        speed = 0f
        nextAt = 0L
    }

    companion object {
        /** Ticks in a run: short, so the grain stops within a few hundredths of a second of the finger. */
        const val RUN = 3
        /** The longest pause between two ticks of a run. */
        const val GAP_MS = 3f
        /** How much earlier than its end a run hands over to the next. */
        const val JOIN_MS = 5
        /** How far the finger has to go before another run: a resting finger's tremble is less. */
        const val MOVED_DP = 0.4f
        /** How firm the grain is under a slow finger and under a fast one (of the motor's soft tick). */
        const val FAINT = 0.17f
        const val FIRM = 0.36f
        const val FAST_DP_PER_MS = 1.0f
    }
}

/**
 * Paper under the finger: the trackpad's surface, felt. A phone whose motor can play the soft
 * tick this is made of (Pixels can) gets it; any other gets nothing, since a plain buzz is not
 * what paper feels like. It is touch feedback to the system, so turning that off in the phone's
 * settings turns this off too.
 */
class PaperFeel(context: android.content.Context) {
    private val vibrator: android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            null
        }
    private val can = Build.VERSION.SDK_INT >= 31 &&
        vibrator?.areAllPrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true
    private val grain = Grain(tickMs = tickMs())

    /** How long this phone's motor takes over the soft tick; a usual value where it doesn't say. */
    private fun tickMs(): Int {
        if (!can || Build.VERSION.SDK_INT < 31) return 12
        val said = runCatching { vibrator?.getPrimitiveDurations(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK)?.firstOrNull() }.getOrNull() ?: 0
        return if (said in 4..60) said else 12
    }

    fun glide(dp: Float) {
        if (!can) return
        val run = grain.glide(dp) ?: return
        play(run)
    }

    fun lift() = grain.lift()

    @androidx.annotation.RequiresApi(31)
    private fun effect(run: List<Tick>): android.os.VibrationEffect {
        val made = android.os.VibrationEffect.startComposition()
        for (tick in run) {
            made.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK, tick.firm.coerceIn(0.01f, 1f), tick.waitMs)
        }
        return made.compose()
    }

    private fun play(run: List<Tick>) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect(run), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect(run),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
                )
            }
        } catch (_: RuntimeException) {
            // a motor that refuses is not worth a crash
        }
    }
}
