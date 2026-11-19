package com.t3lluz.weave.ui.screens.link

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.ui.theme.AppMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** A finger still for this long starts a drag; lifting it ends it. */
private const val HOLD_TO_DRAG_MS = 380L

/** A touch shorter than this, that did not move, is a tap. */
private const val TAP_MS = 320L

/** Finger travel, in dp, for one notch of the wheel. */
private const val DP_PER_NOTCH = 26f

/**
 * Two fingers flicked sideways, where the phone has to tell that itself: this far, within this
 * long of landing, and this many times further sideways than up or down. Slower or shorter, it
 * is a scroll sideways.
 */
private const val SWEEP_DP = 72f
private const val FLICK_MS = 380L
private const val FLICK_SIDEWAYS = 2.5f

/**
 * Which way fingers that have gone [travel] have swept, once that is [far] enough and
 * [sideways] times further across than up or down: true to the right, false to the left, null
 * when it is neither yet.
 */
internal fun sweep(travel: Offset, far: Float, sideways: Float = 1.5f): Boolean? =
    if (abs(travel.x) >= far && abs(travel.x) > abs(travel.y) * sideways) travel.x > 0 else null

/** Two-finger speed, in notches per millisecond, that starts a roll, and where a roll ends. */
private const val FLING_MIN = 0.006f
private const val FLING_END = 0.0012f

/**
 * A laptop trackpad under the thumb. One finger moves the pointer (faster strokes travel
 * further), a tap clicks, a held finger drags, two fingers scroll and the page keeps rolling
 * after they lift, a two-finger tap is the right button and a three-finger tap the middle one.
 *
 * With [nativePad] the computer has a touchpad of its own for the phone's fingers: from two
 * fingers up they are sent as they are, and its desktop reads them as it reads its own pad. So
 * scrolling rolls on there, two fingers swept sideways go back and forward in a browser, a
 * pinch zooms, and three or four fingers do whatever that desktop does with them. Without one
 * (Windows, an older daemon) two fingers scroll as a mouse's wheel, and a quick sweep sideways
 * presses the mouse's own back or forward button.
 */
fun Modifier.trackpad(
    session: LinkSession,
    speed: Float,
    naturalScroll: Boolean,
    onTick: () -> Unit,
    onHold: () -> Unit,
    onTouched: () -> Unit = {},
    /** Every finger on the pad, by its own number and where it is; empty once they have all lifted. */
    onFingers: (List<Pair<Long, Offset>>) -> Unit = {},
    /** The computer reads two fingers and more itself, on a touchpad of its own. */
    nativePad: Boolean = false,
    /** Two fingers flicked sideways pressed back (true) or forward: only where the phone does that itself. */
    onSweep: (back: Boolean) -> Unit = {},
): Modifier = pointerInput(session, speed, naturalScroll, nativePad) {
    val slop = viewConfiguration.touchSlop
    val notch = DP_PER_NOTCH * density
    val sweepFar = SWEEP_DP * density
    val mm = 25.4f / (160f * density)
    coroutineScope {
        var roll: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            roll?.cancel()   // a finger on the pad stops the page, as it does on a phone
            onTouched()
            onFingers(listOf(down.id.value to down.position))
            val startedAt = SystemClock.uptimeMillis()
            var fingers = 1
            var travelled = Offset.Zero
            var moved = false
            var scrolled = false
            var dragging = false
            var swept = false
            var scrollTravel = Offset.Zero
            // from two fingers up: where each was when the second landed, and when that was
            val landed = HashMap<Long, Offset>()
            var landedAt = 0L
            // the fingers are on the computer's touchpad right now; they were at some point
            var onPad = false
            var usedPad = false
            // one of them has left where it landed: a stroke, not a tap (asked again each time the fingers come back to two)
            var strayed = false
            // scroll speed in notches per millisecond, smoothed, for the roll after the fingers lift
            var speedX = 0f
            var speedY = 0f
            var scrolledAt = 0L
            down.consume()
            // a gesture cut short (the pad leaves the screen, its settings change) still lets the cloth go
            try {
                while (true) {
                    val waiting = !moved && !dragging && fingers == 1
                    val event: PointerEvent? = if (waiting) {
                        val left = HOLD_TO_DRAG_MS - (SystemClock.uptimeMillis() - startedAt)
                        if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                    } else {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        dragging = true
                        onHold()
                        session.button("left", true)
                        continue
                    }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    // the cloth dents under each finger
                    onFingers(pressed.map { it.id.value to it.position })
                    if (pressed.size > fingers) fingers = pressed.size
                    if (pressed.size < 2) {
                        landed.clear()
                        strayed = false
                        // one finger left of several: off the computer's pad, or it would move the pointer there
                        if (onPad) {
                            onPad = false
                            session.touch(emptyList(), naturalScroll)
                        }
                    } else {
                        if (landed.isEmpty()) landedAt = pressed[0].uptimeMillis
                        pressed.forEach { if (it.id.value !in landed) landed[it.id.value] = it.position }
                    }
                    if (pressed.size == 1 && (fingers == 1 || dragging)) {
                        val change = pressed[0]
                        val delta = change.positionChange()
                        travelled += delta
                        if (!moved && hypot(travelled.x, travelled.y) > slop) moved = true
                        if (moved || dragging) {
                            val ms = (change.uptimeMillis - change.previousUptimeMillis).coerceIn(1, 40)
                            val dpPerMs = hypot(delta.x, delta.y) / density / ms
                            // slow strokes stay precise, fast ones cross the screen
                            val gain = speed * (0.75f + 1.5f * dpPerMs.coerceAtMost(2.5f))
                            session.move(delta.x / density * gain, delta.y / density * gain)
                        }
                    } else if (pressed.size >= 2) {
                        var sum = Offset.Zero
                        pressed.forEach { sum += it.positionChange() }
                        val delta = sum / pressed.size.toFloat()
                        scrollTravel += delta
                        if (!scrolled && hypot(scrollTravel.x, scrollTravel.y) > slop) scrolled = true
                        // Any one finger leaving its place counts, not only all of them going the
                        // same way: in a pinch the fingers part and their middle stays put.
                        if (!strayed && pressed.any { (it.position - landed.getValue(it.id.value)).getDistance() > slop }) {
                            strayed = true
                            scrolled = true
                        }
                        if (strayed && nativePad) {
                            if (!onPad) {
                                // laid down where they landed, so the stroke up to here counts too
                                onPad = true
                                usedPad = true
                                session.touch(pressed.map { Triple(it.id.value, landed.getValue(it.id.value).x * mm, landed.getValue(it.id.value).y * mm) }, naturalScroll)
                            }
                            session.touch(pressed.map { Triple(it.id.value, it.position.x * mm, it.position.y * mm) }, naturalScroll)
                        } else if (scrolled && pressed.size == 2 && fingers == 2 && !swept) {
                            // (a third finger ends the scroll: lifting back to two must not jolt the page)
                            // A quick sweep plainly sideways is back or forward, as on a laptop's pad:
                            // the page goes the way the fingers do, and the one before it follows.
                            val quick = pressed[0].uptimeMillis - landedAt < FLICK_MS && !dragging
                            (if (quick) sweep(scrollTravel, sweepFar, FLICK_SIDEWAYS) else null)?.let { right ->
                                swept = true
                                speedX = 0f
                                speedY = 0f
                                val back = right == naturalScroll
                                session.click(if (back) "back" else "forward")
                                onSweep(back)
                            }
                            if (swept) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val sign = if (naturalScroll) 1f else -1f
                            // a mostly vertical stroke stays vertical
                            val dx = if (abs(delta.x) > abs(delta.y) * 0.6f) delta.x else 0f
                            val stepX = -sign * dx / notch
                            val stepY = sign * delta.y / notch
                            session.scroll(stepX, stepY)
                            val ms = (pressed[0].uptimeMillis - pressed[0].previousUptimeMillis).coerceIn(1, 40)
                            speedX = speedX * 0.6f + stepX / ms * 0.4f
                            speedY = speedY * 0.6f + stepY / ms * 0.4f
                            scrolledAt = pressed[0].uptimeMillis
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            } finally {
                onFingers(emptyList())
                if (onPad) session.touch(emptyList(), naturalScroll)
                // a drag cut short (the phone turned, the sheet opened) must not leave the laptop's button held
                if (dragging) session.button("left", false)
            }
            // a drag's button was let go in the finally above; a scroll may roll on, a tap clicks
            if (!dragging && scrolled) {
                // fingers that stopped before lifting meant to stop; a flick keeps rolling
                // (on the computer's own touchpad its programs roll the page on themselves)
                if (!usedPad && !swept && SystemClock.uptimeMillis() - scrolledAt < 70 && hypot(speedX, speedY) > FLING_MIN) {
                    roll = launch {
                        var x = speedX
                        var y = speedY
                        while (hypot(x, y) > FLING_END) {
                            session.scroll(x * AppMotion.Link.FLING_FRAME_MS, y * AppMotion.Link.FLING_FRAME_MS)
                            x *= AppMotion.Link.FLING_KEEP
                            y *= AppMotion.Link.FLING_KEEP
                            delay(AppMotion.Link.FLING_FRAME_MS)
                        }
                    }
                }
            } else if (!dragging && !moved && SystemClock.uptimeMillis() - startedAt < TAP_MS) {
                onTick()
                session.click(
                    when (fingers) {
                        1 -> "left"
                        2 -> "right"
                        else -> "middle"
                    },
                )
            }
        }
    }
}

/**
 * The laptop's screen as a touchscreen, for the full-screen view: a tap clicks where it lands,
 * a drag scrolls the page with the finger, and a held finger is the right button. [toScreen]
 * turns a point of this surface into a point of the laptop's monitor (0..1), or null outside it.
 */
fun Modifier.touchscreen(
    session: LinkSession,
    toScreen: (Offset) -> Offset?,
    onTick: () -> Unit,
    onHold: () -> Unit,
): Modifier = pointerInput(session) {
    val slop = viewConfiguration.touchSlop
    val notch = DP_PER_NOTCH * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startedAt = SystemClock.uptimeMillis()
        var travelled = Offset.Zero
        var moved = false
        var held = false
        down.consume()
        toScreen(down.position)?.let { session.pointAt(it.x, it.y, click = false) }
        while (true) {
            val event: PointerEvent? = if (!moved && !held) {
                val left = HOLD_TO_DRAG_MS - (SystemClock.uptimeMillis() - startedAt)
                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                held = true
                onHold()
                session.click("right")
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            var sum = Offset.Zero
            pressed.forEach { sum += it.positionChange() }
            val delta = sum / pressed.size.toFloat()
            travelled += delta
            if (!moved && hypot(travelled.x, travelled.y) > slop) moved = true
            if (moved && !held) {
                val dx = if (abs(delta.x) > abs(delta.y) * 0.6f) delta.x else 0f
                session.scroll(-dx / notch, delta.y / notch)
            }
            event.changes.forEach { it.consume() }
        }
        if (!moved && !held && SystemClock.uptimeMillis() - startedAt < TAP_MS) {
            onTick()
            session.click("left")
        }
    }
}

/** A mouse button you can hold: down on press, up on release, so a second finger can drag. */
fun Modifier.mouseButton(session: LinkSession, button: String, onTick: () -> Unit, onPressed: (Boolean) -> Unit): Modifier =
    pointerInput(session, button) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            onTick()
            onPressed(true)
            session.button(button, true)
            // let go however the press ends: a lift, or the button leaving the screen mid-press
            try {
                waitForUpOrCancellation()
            } finally {
                session.button(button, false)
                onPressed(false)
            }
        }
    }

/**
 * Where the phone's keyboard types into. It is never drawn: it only holds focus so the
 * keyboard has somewhere to send text, and passes on each piece as it is committed (no
 * suggestions, nothing left composing), as the console's terminal does.
 */
@SuppressLint("ViewConstructor")
class LinkKeyView(context: Context) : View(context) {
    var onText: (String) -> Unit = {}
    var onKey: (String) -> Unit = {}

    /**
     * The keyboard corrects and suggests as it does anywhere: it edits a copy of what was typed
     * since the last reset, and only the difference goes to the laptop ("teh" corrected to "the"
     * is two backspaces and "he"). Off, each key goes straight over, as into a password field.
     */
    var autocorrect = false
        set(on) {
            if (field == on) return
            field = on
            restart()
        }

    /** What the laptop has been sent since the last reset, as the keyboard's copy had it. */
    private var sent = ""
    private var connection: BaseInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    fun showKeyboard() {
        requestFocus()
        imm?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Forget what was typed: a click on the laptop, Enter, or arrow keys moved its cursor, and
     * the keyboard's copy no longer matches what is there.
     */
    fun reset() {
        if (sent.isEmpty() && connection?.editable.isNullOrEmpty()) return
        restart()
    }

    private fun restart() {
        sent = ""
        connection?.editable?.clear()
        imm?.restartInput(this)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        if (autocorrect) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
            sent = ""
            return MirrorConnection().also { connection = it }
        }
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(1, 64)) { onKey("backspace") }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onKey("enter")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) keyName(event.keyCode)?.let { onKey(it); return true }
                return keyName(event.keyCode) != null || super.sendKeyEvent(event)
            }

            private fun flush() {
                val content = editable ?: return
                if (content.isNotEmpty()) onText(content.toString())
                content.clear()
            }
        }.also { connection = it }
    }

    /**
     * The keyboard's copy of what was typed. After each edit the laptop gets what changed: back
     * to where the old and new text part ways, then the rest of the new.
     */
    private inner class MirrorConnection : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
            super.commitText(text, newCursorPosition).also { sync() }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { sync() }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            super.setComposingRegion(start, end).also { sync() }

        override fun finishComposingText(): Boolean = super.finishComposingText().also { sync() }

        /**
         * The keyboard moved the cursor (a slide on the space bar, say): the laptop's moves with it,
         * as arrow keys, and the copy starts afresh there, since only typing at its end is mirrored.
         */
        override fun setSelection(start: Int, end: Int): Boolean {
            val content = editable ?: return false
            val at = android.text.Selection.getSelectionEnd(content)
            val to = end.coerceIn(0, content.length)
            if (to == at) return super.setSelection(start, end)
            repeat(kotlin.math.abs(to - at).coerceAtMost(200)) { onKey(if (to < at) "left" else "right") }
            restart()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // nothing of ours to take back: what is before the cursor on the laptop goes
            if (editable.isNullOrEmpty()) {
                repeat(beforeLength.coerceIn(1, 64)) { onKey("backspace") }
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength).also { sync() }
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            onKey("enter")
            restart()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val name = keyName(event.keyCode) ?: return super.sendKeyEvent(event)
            val content = editable
            if (name == "backspace" && !content.isNullOrEmpty()) {
                content.delete(content.length - 1, content.length)
                sync()
                return true
            }
            onKey(name)
            // a line ended or the cursor moved: the copy no longer matches the laptop
            if (name != "backspace") restart()
            return true
        }

        /**
         * Tells the keyboard where the text and cursor are now, as a real text field does after each
         * edit: keyboards built on Android's own (Pixelboard, AOSP) follow the cursor by it, and
         * without it their suggestions and corrections go wrong.
         */
        private fun report() {
            val content = editable ?: return
            imm?.updateSelection(
                this@LinkKeyView,
                android.text.Selection.getSelectionStart(content), android.text.Selection.getSelectionEnd(content),
                getComposingSpanStart(content), getComposingSpanEnd(content),
            )
        }

        private fun sync() {
            report()
            val now = editable?.toString().orEmpty()
            if (now == sent) return
            // a line break typed into the copy is Enter on the laptop, and a fresh copy after it
            val cut = now.indexOf('\n')
            val upTo = if (cut >= 0) now.substring(0, cut) else now
            val (back, type) = mirrorEdit(sent, upTo)
            repeat(back) { onKey("backspace") }
            if (type.isNotEmpty()) onText(type)
            sent = upTo
            if (cut >= 0) {
                onKey("enter")
                restart()
            } else if (sent.length > MIRROR_MAX && sent.endsWith(' ')) {
                // a long run: start a fresh copy at a word's end, where nothing is left to correct
                restart()
            }
        }
    }

    /** A hardware keyboard paired with the phone types on the laptop too. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem) return super.onKeyDown(keyCode, event)
        keyName(keyCode)?.let { onKey(it); return true }
        val char = event.unicodeChar
        if (char != 0) {
            onText(String(Character.toChars(char)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun keyName(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_ESCAPE -> "esc"
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_MOVE_HOME -> "home"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_PAGE_UP -> "pageup"
        KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
        else -> null
    }
}

/**
 * What turns [was] into [now] on the laptop, typing at the end of the line: backspaces back to
 * where the two part ways, then the rest of [now]. "teh " corrected to "the " is 3 and "he ".
 */
internal fun mirrorEdit(was: String, now: String): Pair<Int, String> {
    var same = 0
    val most = minOf(was.length, now.length)
    while (same < most && was[same] == now[same]) same++
    return (was.length - same) to now.substring(same)
}

/** How long the keyboard's copy grows before it starts afresh at the next word's end. */
private const val MIRROR_MAX = 400

/** The key a single typed character stands for, so ctrl + "c" is the chord and not a letter. */
fun chordKey(text: String): String? {
    if (text.length != 1) return null
    val c = text[0].lowercaseChar()
    return when {
        c in 'a'..'z' || c in '0'..'9' -> c.toString()
        c == ' ' -> "space"
        c == ',' -> "comma"
        c == '.' -> "dot"
        c == '-' -> "minus"
        c == '/' -> "slash"
        c == '=' -> "equal"
        else -> null
    }
}
