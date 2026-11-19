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




/**
 * When a finger gliding over a surface would feel its grain: one faint tick every few dp of
 * travel, each a little nearer or further and a little fainter or firmer than the last, so it
 * reads as a texture and not as a buzz. Never more often than the motor can keep apart.
 *
 * [chance] gives numbers from 0 to 1, [now] the time in ms; both are handed in so this can be
 * tested without a phone.
 */
class Grain(
    private val everyDp: Float = 3.4f,
    private val strength: Float = 0.13f,
    private val minGapMs: Long = 14,
    private val chance: () -> Float = { kotlin.random.Random.nextFloat() },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private var toNext = everyDp
    private var lastAt = 0L

    /** The finger went [dp] further: how firm a tick is due (0..1), or null when none is. */
    fun glide(dp: Float): Float? {
        if (dp <= 0f) return null
        toNext -= dp
        if (toNext > 0f) return null
        toNext = everyDp * (0.55f + 0.9f * chance())
        val at = now()
        // a fast stroke would ask for hundreds a second: past this they run together, so they are thinned
        if (at - lastAt < minGapMs) return null
        lastAt = at
        return strength * (0.6f + 0.8f * chance())
    }

    /** The finger lifted: the next stroke starts its grain afresh. */
    fun lift() {
        toNext = everyDp
    }
}

/**
 * Paper under the finger: the trackpad's surface, felt. A phone whose motor can play the faint
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
    private val grain = Grain()

    fun glide(dp: Float) {
        if (!can) return
        val firm = grain.glide(dp) ?: return
        play(firm)
    }

    fun lift() = grain.lift()

    @androidx.annotation.RequiresApi(31)
    private fun effect(firm: Float) = android.os.VibrationEffect.startComposition()
        .addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK, firm.coerceIn(0.01f, 1f)).compose()

    private fun play(firm: Float) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect(firm), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect(firm),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
                )
            }
        } catch (_: RuntimeException) {
            // a motor that refuses is not worth a crash
        }
    }
}
