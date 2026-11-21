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
 * The ticks of the picture's slide to another monitor: how far along the slide each falls, and
 * how firm it is. The stretches between them shrink, since the slide slows: in time they come
 * about 30, 30, 35, 40 and 45 ms apart. Then where it counts as there, and how firm it lands.
 */
private val SLIDE_TICKS = listOf(0.10f to 0.62f, 0.31f to 0.55f, 0.52f to 0.47f, 0.70f to 0.39f, 0.83f to 0.31f, 0.92f to 0.24f)
private const val SLIDE_LANDS = 0.97f
private const val SLIDE_LANDS_FIRM = 0.55f

/**
 * How much longer a block takes inside a phrase than the motor says it does. Measured on a
 * Pixel 10: seven blocks ran 27 ms over what their own times and the pauses came to.
 */
private const val MOTOR_LAG_MS = 4

/** How long a phrase rings on past what the motor says it takes, kept clear of the grain. */
private const val RING_ON_MS = 40

/**
 * Until when (uptime, ms) a phrase is playing that the trackpad's grain must leave alone: a new
 * vibration replaces the one playing, and a cancel ends whichever that is.
 */
@Volatile private var phraseUntil = 0L
private fun phrasePlaying() = android.os.SystemClock.uptimeMillis() < phraseUntil

/**
 * Counts the phrases played. A phrase replaces whatever the motor was playing, the trackpad's
 * grain included, so the grain starts afresh once one has played over it.
 */
@Volatile private var phrases = 0

/** A phrase is playing for [ms] from now: the grain keeps out of its way until it is done. */
private fun phraseFor(ms: Int) {
    phraseUntil = maxOf(phraseUntil, android.os.SystemClock.uptimeMillis() + ms)
    phrases++
}

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
            val blocks = beats.map { it.block }.distinct().toIntArray()
            if (!m.areAllPrimitivesSupported(*blocks)) return false
            val takes = m.getPrimitiveDurations(*blocks)
            val made = android.os.VibrationEffect.startComposition()
            var long = 0
            for (beat in beats) {
                made.addPrimitive(beat.block, beat.firm, beat.afterMs)
                long += beat.afterMs + takes[blocks.indexOf(beat.block)] + MOTOR_LAG_MS
            }
            m.vibrate(made.compose(), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            // the trackpad's grain, playing while a finger glides, would cut it off at its next stretch
            // (and a little longer than the motor says: a firm click rings on a little)
            phraseFor(long + RING_ON_MS)
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

    /** One beat of a phrase kept in time with something seen: which block, how firm, and how long after the start it falls. */
    private class Timed(val block: Int, val firm: Float, val atMs: Int)

    /**
     * Plays [beats], each at its own time, as one vibration: the motor keeps the time, so none is
     * late and none is dropped, as beats asked for one by one are when they come faster than the
     * motor plays them (a tick on its own takes a Pixel some 30 ms). The motor says how long each
     * block takes, and takes [MOTOR_LAG_MS] longer; one that takes long pushes the next back and
     * no more. Says how long the phrase runs, or null where the motor can't.
     */
    private fun inTime(beats: List<Timed>): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        val m = motor ?: return null
        return try {
            val blocks = beats.map { it.block }.distinct().toIntArray()
            if (!m.areAllPrimitivesSupported(*blocks)) return null
            val takes = m.getPrimitiveDurations(*blocks)
            val made = android.os.VibrationEffect.startComposition()
            var free = 0        // when the motor is done with the beat before
            for (beat in beats) {
                val start = maxOf(beat.atMs, free)
                made.addPrimitive(beat.block, beat.firm, start - free)
                free = start + takes[blocks.indexOf(beat.block)] + MOTOR_LAG_MS
            }
            m.vibrate(made.compose(), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            free
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * The picture sliding over to another monitor, played as the slide starts: ticks on the way,
     * as Essentials' tabs give under a swipe, each where the slide has gone another stretch
     * ([SLIDE_TICKS]). So they come close and firm while it moves fast, further apart and fainter
     * as it slows, and a soft step lands it. The trackpad's grain keeps out of its way meanwhile.
     */
    fun slideOver() {
        val runs = inTime(slideBeats)
        if (runs != null) phraseFor(runs) else slide()
    }

    /** A phrase is playing that a lesser one should not cut off (the slide's ticks under the thumb). */
    val busy: Boolean get() = phrasePlaying()

    private val slideBeats by lazy {
        SLIDE_TICKS.map { (share, firm) -> Timed(TICK, firm, com.t3lluz.weave.ui.theme.AppMotion.Link.swipeMs(share)) } +
            Timed(LOW_TICK, SLIDE_LANDS_FIRM, com.t3lluz.weave.ui.theme.AppMotion.Link.swipeMs(SLIDE_LANDS))
    }

    /** The picture went over to another monitor with nothing to see slide: a soft step, lighter than a tap's. */
    fun slide() {
        if (!phrase(Beat(LOW_TICK, 0.6f))) tick()
    }

    /** A slider crossed one of its marks: the faintest tick there is. */
    fun notch() {
        if (!phrase(Beat(LOW_TICK, 0.35f))) tick()
    }

    /** The pointer meets the edge between two monitors, which holds it: one light, clear tick. */
    fun edgeMeet() {
        if (!phrase(Beat(TICK, 0.32f))) tick()
    }

    /** A finger meets the scroll strip: the lightest tick, the cloth under it. */
    fun stripTouch() {
        phrase(Beat(TICK, 0.2f))
    }

    /**
     * Pulled or pushed part way to coming free (the scroll strip off its cloth, the pointer over a
     * monitor's edge): a faint, low creak of the give, the [nth] (from 0) firmer than the one before.
     */
    fun creak(nth: Int) {
        phrase(Beat(LOW_TICK, (0.16f + 0.1f * nth).coerceAtMost(0.5f)))
    }

    /**
     * Come free (the scroll strip off its cloth, the pointer through a monitor's edge): a firm snap,
     * then a low settle, as a notification gives when it comes loose from its list.
     */
    fun comeFree() {
        if (!phrase(Beat(CLICK, 0.85f), Beat(LOW_TICK, 0.38f, 34))) thresholdActivate()
    }

    /**
     * A tap on the held strip, which only a pull frees: two soft low bumps, the second fainter, a
     * gentle "not like that". No plain fallback: the system's reject is far too stern for it.
     */
    fun stripDecline() {
        phrase(Beat(LOW_TICK, 0.32f), Beat(LOW_TICK, 0.2f, 70))
    }

    /** Let go before it came free: the cloth's give let out, softly. */
    fun stripRecoil() {
        phrase(Beat(QUICK_FALL, 0.24f))
    }

    /**
     * The scroll strip turned past a notch: one faint tick, [firm] (0..1) as firm as the turn is
     * meant to feel; under 0.03 it is not felt and not played. There is no plain fallback: the
     * system's ticks are far firmer, and a stream of them would buzz.
     */
    fun stripNotch(firm: Float) {
        if (firm >= 0.03f) phrase(Beat(TICK, firm.coerceAtMost(1f)))
    }

    /** A computer taken off the list: one dull thud. */
    fun removed() {
        if (!phrase(Beat(THUD, 0.45f))) confirm()
    }

    /** A mouse button going down: one firm click, as the switch under a real one gives. */
    fun press() {
        if (!phrase(Beat(CLICK, 0.62f))) click()
    }

    /** ...and coming back up: lighter, so down and up are told apart. */
    fun release() {
        if (!phrase(Beat(TICK, 0.42f))) tick()
    }

    /** The computer couldn't be reached, or said no: a step down that ends on a dull stop. */
    fun failed() {
        if (!phrase(Beat(QUICK_FALL, 0.4f), Beat(LOW_TICK, 0.85f, 20))) reject()
    }

    /** A computer turned up in the list, or woke: two faint steps up. */
    fun found() {
        if (!phrase(Beat(LOW_TICK, 0.4f), Beat(TICK, 0.35f, 70))) tick()
    }

    /** A shortcut's steps have all run on the computer: two ticks, the second firmer. */
    fun done() {
        if (!phrase(Beat(TICK, 0.4f), Beat(TICK, 0.75f, 85))) confirm()
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
 * What a finger sliding over the pad's cloth feels: one faint texture, the same at any pace and
 * anywhere on the pad. It is there while the finger glides and gone when it stops.
 *
 * A motor held at one strength and one pitch is a hum, and a hum is a machine. A surface is
 * uneven, so the strength and the pitch here wander all the time, each moment a short way from
 * the last and never far from the middle: the pitch over a wide band, so no one tone stands
 * out, the strength over a narrow one, so nothing is felt as a tick.
 *
 * It is played as one long stretch, cut off when the finger rests ([restMs] after its last
 * move), lifts, or slows to a creep. Every start is a small hitch in the motor, so a stretch is
 * only started for a new stroke or when the one playing is about to run out, and the next one
 * carries on from where the last one was.
 *
 * [chance] gives numbers from 0 to 1 and [now] the time in ms; both are handed in so this can
 * be tested without a phone.
 */
class Grain(
    private val stepMs: Int = 20,
    private val chance: () -> Float = { kotlin.random.Random.nextFloat() },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private var speed = 0f          // dp a millisecond, smoothed
    private var lastAt = 0L
    private var moves = 0
    private var gliding = 0         // moves running at more than a creep
    private var endsAt = 0L
    // where the grain's wander stands: the next stretch carries on from here
    private var strength = STRENGTH
    private var sharp = (DULL + SHARP) / 2f

    /** How long after the finger's last move the grain is cut off. */
    val restMs: Long get() = REST_MS

    /** Whether a stretch plays right now. */
    var playing = false
        private set

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
        if (playing) {
            if (speed < CREEP * (1f - GIVE)) {
                // slowed to a creep: the grain goes with it, without waiting
                playing = false
                gliding = 0
                return Stretch(emptyList(), stepMs)
            }
            // left to play; renewed just before it runs out
            if (at < endsAt - 2 * stepMs) return null
            return stretch(at, fresh = false)
        }
        // a stroke's grain starts once the finger has plainly glided a few moves running
        if (speed < CREEP * (1f + GIVE)) {
            gliding = 0
            return null
        }
        if (++gliding < SETTLED_MOVES) return null
        return stretch(at, fresh = true)
    }

    /** A new stretch. A [fresh] stroke's comes up from fainter and duller; one that renews carries straight on. */
    private fun stretch(at: Long, fresh: Boolean): Stretch {
        playing = true
        endsAt = at + STEPS * stepMs.toLong()
        if (fresh) {
            strength = STRENGTH
            sharp = (DULL + SHARP) / 2f
        }
        return Stretch(
            List(STEPS) { i ->
                strength = (strength + (chance() - 0.5f) * 2f * STRENGTH * WANDER * DRIFT).coerceIn(STRENGTH * (1f - WANDER), STRENGTH * (1f + WANDER))
                sharp = (sharp + (chance() - 0.5f) * 2f * (SHARP - DULL) * DRIFT).coerceIn(DULL, SHARP)
                if (!fresh) return@List Step(strength, sharp)
                val over = ((i + 1f) / EASE_STEPS).coerceAtMost(1f)
                val eased = over * over * (3f - 2f * over)
                Step(strength * (FAINT + (1f - FAINT) * eased), DULL + (sharp - DULL) * eased)
            },
            stepMs,
        )
    }

    /** The grain was cut off (the finger rested or lifted): the next stroke starts afresh. */
    fun rested() {
        playing = false
        speed = 0f
        lastAt = 0L
        moves = 0
        gliding = 0
    }

    companion object {
        /** Steps in a stretch: five seconds' worth, cut off long before by a finger that stops. */
        const val STEPS = 250
        /** A slow finger's moves can be this far apart without the grain breaking off between them. */
        const val REST_MS = 80L
        /** How much of each new move the speed takes on: little, so a jerk of the finger doesn't count. */
        const val HEED = 0.16f
        /** Slower than this (dp a ms) is a creep: a finger held nearly still gets nothing. */
        const val CREEP = 0.05f
        /** How far past the creep's border the finger has to go, either way, before the grain starts or stops. */
        const val GIVE = 0.38f
        /** Over how many steps a stroke's grain comes up, and from how much of its strength. */
        const val EASE_STEPS = 8
        const val FAINT = 0.45f
        /** How many moves running the finger must glide before a stroke's grain starts. */
        const val SETTLED_MOVES = 3

        /** How strong the grain is: barely there. The one number to turn for more or less of it. */
        const val STRENGTH = 0.042f
        /** How far the strength wanders from that, either way. */
        const val WANDER = 0.16f
        /** The band the pitch wanders over: wide, so it is a hiss and not a tone. */
        const val DULL = 0.52f
        const val SHARP = 0.88f
        /** How much of its band each may cross from one moment to the next. */
        const val DRIFT = 0.42f

        /** The strongest the grain ever is. */
        const val STRONGEST: Float = STRENGTH * (1f + WANDER)
    }
}

/**
 * Cloth under the finger: the trackpad's surface, felt. Phones that can shape a vibration's
 * strength and pitch over time (Android 16's envelopes; Pixels can) get the real thing. Others
 * with a motor whose strength can be set get the same grain at the motor's own pitch. The rest
 * get nothing, since a plain buzz is not what cloth feels like. It is touch feedback to the
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

    /** The phrases counted when the grain last played: one since has replaced it on the motor. */
    private var heard = phrases

    fun glide(dp: Float) {
        if (!can) return
        main.removeCallbacks(rest)
        main.postDelayed(rest, grain.restMs)
        // a phrase is playing (a tick, a snap, the picture sliding to another monitor): it is not cut off for the grain
        if (phrasePlaying()) return
        // one played over the grain and replaced it: the grain comes back up from faint, as at a stroke's start
        if (heard != phrases) {
            heard = phrases
            grain.rested()
        }
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
        // the grain's own stretch has run out by itself by the time a phrase over it has
        if (!phrasePlaying()) runCatching { vibrator?.cancel() }
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
