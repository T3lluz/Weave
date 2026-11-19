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

private const val CLICK = 1        // VibrationEffect.Composition.PRIMITIVE_CLICK
private const val THUD = 2
private const val QUICK_RISE = 4
private const val SLOW_RISE = 5
private const val QUICK_FALL = 6
private const val TICK = 7
private const val LOW_TICK = 8

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
    /* ── Little phrases, made of the motor's own building blocks where it has them ──────────── */

    private val motor: android.os.Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            (view.context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            null
        }
    }

    /** One beat of a phrase: which block, how firm (0..1), and how long after the one before. */
    private class Beat(val block: Int, val firm: Float, val afterMs: Int = 0)

    /** Plays [beats] and says true, or says false where the motor can't (the caller then falls back to a plain effect). */
    private fun phrase(vararg beats: Beat): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val m = motor ?: return false
        return try {
            if (!m.areAllPrimitivesSupported(*beats.map { it.block }.distinct().toIntArray())) return false
            val made = android.os.VibrationEffect.startComposition()
            for (beat in beats) made.addPrimitive(beat.block, beat.firm, beat.afterMs)
            m.vibrate(made.compose(), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** A tap with one, two or three fingers: as many light ticks, so the hand knows which button it was. */
    fun taps(fingers: Int) {
        val beats = List(fingers.coerceIn(1, 3)) { Beat(TICK, 0.7f, if (it == 0) 0 else 55) }
        if (!phrase(*beats.toTypedArray())) tick()
    }

    /** A held finger takes hold of what is under the pointer: a low thump with a click on top. */
    fun grab() {
        if (!phrase(Beat(LOW_TICK, 0.9f), Beat(CLICK, 0.45f, 18))) click()
    }

    /** ...and lets it go: the same, the other way round and softer. */
    fun drop() {
        if (!phrase(Beat(CLICK, 0.3f), Beat(LOW_TICK, 0.6f, 24))) gestureEnd()
    }

    /** A page turned by a flick sideways: a quick swell that ends on a tick. */
    fun swoosh() {
        if (!phrase(Beat(QUICK_RISE, 0.35f), Beat(TICK, 0.8f))) thresholdActivate()
    }

    /** The computer is there: three steps up, as a knock that is answered. */
    fun connected() {
        if (!phrase(Beat(TICK, 0.35f), Beat(TICK, 0.6f, 70), Beat(CLICK, 0.6f, 70))) confirm()
    }

    /** The link dropped: one soft step down. It comes back by itself, so nothing alarming. */
    fun lost() {
        if (!phrase(Beat(QUICK_FALL, 0.3f))) toggleOff()
    }

    /** The link is back: a single light tick. */
    fun back() {
        if (!phrase(Beat(TICK, 0.6f))) tick()
    }

    /** A new computer let this phone in: a swell, a firm click, and two ticks trailing off. */
    fun paired() {
        if (!phrase(Beat(QUICK_RISE, 0.4f), Beat(CLICK, 0.85f), Beat(TICK, 0.55f, 90), Beat(TICK, 0.3f, 80))) confirm()
    }

    /** A sleeping computer is called: a slow swell, as of something stirring. */
    fun wake() {
        if (!phrase(Beat(SLOW_RISE, 0.45f), Beat(TICK, 0.5f))) click()
    }

    /** The picture slid over to another monitor: a soft step, lighter than a tap's. */
    fun slide() {
        if (!phrase(Beat(LOW_TICK, 0.7f))) tick()
    }

    /** A slider crossed one of its marks: the faintest tick there is. */
    fun notch() {
        if (!phrase(Beat(LOW_TICK, 0.35f))) tick()
    }

    /** A computer taken off the list: one dull thud. */
    fun removed() {
        if (!phrase(Beat(THUD, 0.45f))) confirm()
    }

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
 * What a finger sliding over paper feels. A surface's grain is in the surface, so what the
 * finger gets depends on how fast it crosses it: creeping, there is next to nothing; slowly, a
 * faint low murmur; faster, a stronger and higher hiss. Stopped, nothing at all.
 *
 * It is played as one long stretch for as long as the finger keeps its pace, simply cut off
 * when the finger rests ([restMs] after its last move), lifts, or slows to a creep. A new
 * stretch is started only when the pace has really changed and stayed changed: every start is a
 * small hitch in the motor, and several a second are a wobble, not paper. So the paces overlap
 * widely, and one is held a while before the next may take over. Within a stretch the strength
 * and the pitch drift a little from one moment to the next, each a short step from the last.
 *
 * [chance] gives numbers from 0 to 1 and [now] the time in ms; both are handed in so this can
 * be tested without a phone.
 */
class Grain(
    private val stepMs: Int = 20,
    private val chance: () -> Float = { kotlin.random.Random.nextFloat() },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    /** A pace: from which speed (dp a ms) it holds, how strong its grain is and how sharp (low to high). */
    private class Pace(val from: Float, val strength: Float, val dull: Float, val sharp: Float)

    private var speed = 0f          // dp a millisecond, smoothed
    private var lastAt = 0L
    private var moves = 0
    private var pace = -1           // which pace plays; -1 while nothing does
    private var startedAt = 0L
    private var endsAt = 0L
    private var wanted = -1         // the pace the speed points at, and since when it has
    private var wantedSince = 0L
    private var wantedMoves = 0

    /** How long after the finger's last move the grain is cut off. */
    val restMs: Long get() = REST_MS

    /** Whether a stretch plays right now. */
    val playing: Boolean get() = pace >= 0

    /**
     * The finger went [dp] further. A stretch to start playing now (in place of what plays), an
     * empty one when what plays is to be cut off (the finger has slowed to a creep), or null to
     * let things be.
     */
    fun glide(dp: Float): Stretch? {
        if (dp <= 0f) return null
        val at = now()
        moves++
        if (moves == 1) {
            // a first move says how far, not how fast: the next one does
            lastAt = at
            return null
        }
        val since = (at - lastAt).coerceIn(1, 80)
        speed = if (moves == 2) dp / since else speed * (1f - HEED) + dp / since * HEED
        lastAt = at
        // the pace this speed belongs to; the one playing keeps the finger a good way past its borders
        var to = -1
        for (i in PACES.indices) {
            val edge = PACES[i].from * if (i <= pace) 1f - GIVE else 1f + GIVE
            if (speed >= edge) to = i
        }
        if (to != wanted) {
            wanted = to
            wantedSince = at
            wantedMoves = 0
        }
        wantedMoves++
        if (pace < 0) {
            // started once the speed has pointed at the same pace a few moves running
            if (to < 0 || wantedMoves < SETTLED_MOVES) return null
        } else {
            if (to < 0) {
                // slowed to a creep: the grain goes with it, without waiting
                pace = -1
                return Stretch(emptyList(), stepMs)
            }
            val runningOut = at >= endsAt - 2 * stepMs
            // Another pace takes over only once the speed has settled on it (a finger speeding up
            // passes through the paces between on its way) and this one has played a while.
            val changed = to != pace && at - wantedSince >= SETTLED_MS && at - startedAt >= HOLD_MS
            if (!runningOut && !changed) return null
        }
        // Where the grain comes from: the pace that played until now, or (a new stroke) a
        // fainter, duller version of this one. It eases over from there, so neither a start
        // nor a change of pace is a step.
        val p = PACES[to]
        val before = PACES.getOrNull(pace)
        val fromStrength = before?.strength ?: (p.strength * 0.45f)
        val fromSharp = before?.let { (it.dull + it.sharp) / 2f } ?: p.dull
        pace = to
        startedAt = at
        endsAt = at + STEPS * stepMs.toLong()
        var strength = p.strength
        var sharp = (p.dull + p.sharp) / 2f
        return Stretch(
            List(STEPS) { i ->
                strength = (strength + (chance() - 0.5f) * 2f * p.strength * DRIFT).coerceIn(p.strength * (1f - WANDER), p.strength * (1f + WANDER))
                sharp = (sharp + (chance() - 0.5f) * 2f * (p.sharp - p.dull) * DRIFT).coerceIn(p.dull, p.sharp)
                val over = ((i + 1f) / EASE_STEPS).coerceAtMost(1f)
                val eased = over * over * (3f - 2f * over)
                Step(fromStrength + (strength - fromStrength) * eased, fromSharp + (sharp - fromSharp) * eased)
            },
            stepMs,
        )
    }

    /** The grain was cut off (the finger rested or lifted): the next stroke starts afresh. */
    fun rested() {
        pace = -1
        speed = 0f
        lastAt = 0L
        moves = 0
        wanted = -1
        wantedMoves = 0
    }

    companion object {
        /** Steps in a stretch: five seconds' worth, cut off long before by a finger that stops. */
        const val STEPS = 250
        /** A slow finger's moves can be this far apart without the grain breaking off between them. */
        const val REST_MS = 80L
        /** How much of each new move the speed takes on: little, so a jerk of the finger is not a change of pace. */
        const val HEED = 0.16f
        /** How far past a pace's border the finger has to go, either way, before the pace changes. */
        const val GIVE = 0.38f
        /** How long a pace plays at least before another takes over, and how long the speed must have pointed at that other. */
        const val HOLD_MS = 480L
        const val SETTLED_MS = 170L
        /** Over how many steps a stretch eases in from what came before it. */
        const val EASE_STEPS = 8
        /** How many moves running the speed must point at one pace before a stroke's grain starts. */
        const val SETTLED_MOVES = 3
        /** How far the strength may drift from its pace's, either way, and how much of a band each moment may cross. */
        const val WANDER = 0.07f
        const val DRIFT = 0.1f

        /**
         * Creeping (under the first) gives nothing. Then a slow finger, faint and low; an easy
         * one; a fast sweep, the strongest and highest, and still only a hiss. Three, close to
         * each other: enough to tell slow from fast, few enough that a stroke seldom crosses one.
         */
        private val PACES = listOf(
            Pace(from = 0.05f, strength = 0.05f, dull = 0.5f, sharp = 0.58f),
            Pace(from = 0.3f, strength = 0.068f, dull = 0.68f, sharp = 0.76f),
            Pace(from = 1.0f, strength = 0.086f, dull = 0.86f, sharp = 0.94f),
        )

        /** The strongest the grain ever is. */
        val STRONGEST: Float get() = PACES.last().strength * (1f + WANDER)
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
        val stretch = grain.glide(dp) ?: return
        // an empty stretch: the finger has slowed to a creep, and the grain goes with it
        if (stretch.steps.isEmpty()) runCatching { vibrator?.cancel() } else play(stretch)
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
