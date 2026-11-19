package com.t3lluz.weave.ui.screens.link

import android.content.ClipData
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkEvent
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.monitorUnder
import com.t3lluz.weave.data.link.LinkState
import com.t3lluz.weave.data.link.LinkStatus
import com.t3lluz.weave.ui.components.SquircleShape
import com.t3lluz.weave.ui.components.chromeGlass
import com.t3lluz.weave.ui.screens.ai.MenuRow
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.theme.LinkClipboardTone
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.LinkSoundTone
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Surface
import androidx.compose.ui.text.font.FontFamily
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.util.LaunchedWhileResumed
import com.t3lluz.weave.ui.util.findActivity
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.util.rememberIsResumed
import com.t3lluz.weave.ui.viewmodel.LinkViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The laptop, from the phone, in Health's look. Not connected, it lists the laptops that
 * announced themselves to the dashboard ([LinkFinder]): a tap connects. Upright: the laptop's
 * screen in the top third, a trackpad room under it with a glass dock over its foot, and the
 * phone's own keyboard for typing (it rises by itself when a click lands in a text field). On
 * its side: the laptop's screen fills the phone, with the controls in a glass panel that folds
 * away. When the pointer crosses to another monitor the picture swipes over to it ([LinkPreview]).
 * The laptop runs `tandem` (its own repo); screen, sound and touches go straight between the
 * two over the tailnet. Reached from Settings and `EXTRA_OPEN` `laptop`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkScreen(
    onNavigateBack: () -> Unit,
    onAiSettings: () -> Unit = {},
    onSettings: () -> Unit = {},
    viewModel: LinkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val laptops by viewModel.laptops.collectAsState()
    val session = viewModel.session
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // a link that dropped stays on the remote while it comes back
    val online = state.status == LinkStatus.Online || state.status == LinkStatus.Reconnecting
    val imeVisible = WindowInsets.isImeVisible
    val laptopName = state.laptop.ifBlank { config.name.ifBlank { config.host.substringBefore(',') } }
    // the laptop's picture is what the glass over it blurs
    val hazeState = rememberHazeState()

    var keyView by remember { mutableStateOf<LinkKeyView?>(null) }
    var mods by remember { mutableStateOf(setOf<String>()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    // the shortcut row being arranged; the sheet open on a shortcut is the view-model's (`shortcutSheet`)
    var arranging by remember { mutableStateOf(false) }
    fun openShortcut(s: LinkShortcut?) {
        viewModel.shortcutSheet = ShortcutSheetState(s, LinkSession.CATALOG in state.caps)
    }
    // the keyboard came up by itself, so it may go down by itself
    var raisedForField by remember { mutableStateOf(false) }
    val geometry = remember { PreviewGeometry() }

    // While the remote is up, Android's back does not leave it: a swipe in from the side belongs to
    // the trackpad, and one that the system takes anyway must not end the session. The arrow in the
    // corner is the way out; back says so, once in a while.
    var backSaidAt by remember { mutableStateOf(0L) }
    BackHandler {
        when {
            arranging -> arranging = false
            landscape && viewModel.orientation == 1 -> viewModel.orientation = 2
            online -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - backSaidAt > BACK_SAYS_EVERY_MS) {
                    backSaidAt = now
                    toast = "Back is off while the remote is up. The arrow at the top leaves."
                }
            }
            else -> onNavigateBack()
        }
    }

    // Connected while the screen is in front; a rotation keeps the connection.
    val resumed = rememberIsResumed()
    DisposableEffect(resumed) {
        if (resumed) viewModel.start()
        onDispose { if (!activity.isChangingConfigurations) viewModel.stop() }
    }
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(viewModel.orientation) {
        activity.requestedOrientation = when (viewModel.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val immersive = landscape && online
    LaunchedEffect(immersive, resumed) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // asked again once the rotated window has settled: the first ask can land before it has the bars
            repeat(3) {
                bars.hide(WindowInsetsCompat.Type.systemBars())
                delay(350)
            }
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Copied on the phone, there to paste on the laptop, while the two are kept in step. Android lets only
    // the app whose window has the focus read the clipboard, and things are mostly copied in another app:
    // so this looks each time Weave's window gets the focus back, and listens while it has it. The time a
    // clip was made is read first and remembered (LinkPrefs.clipSeen), so the clipboard itself (and
    // Android's "pasted from" note) is only read when there is a clip Weave hasn't looked at yet.
    val syncing = online && config.clipSync && LinkSession.CLIP_SYNC in state.caps
    val inFront = rememberIsResumed() && LocalWindowInfo.current.isWindowFocused
    val clips = remember { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    /** Sends the phone's clip to the laptop when it is one not looked at before; whether it did. */
    fun sendNewClip(): Boolean {
        val desc = clips.primaryClipDescription ?: return false
        if (desc.timestamp == session.clipSeen) return false
        // the first look ever (sync was on before this was kept): what is there stays put
        val first = session.clipSeen == -1L
        val skip = first ||
            // a password manager marks what it copies: that never leaves the phone by itself
            desc.extras?.getBoolean(CLIP_IS_SENSITIVE) == true ||
            (!desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML))
        val text = if (skip) "" else clips.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty() || text == session.clipSynced) {
            session.clipSeen = desc.timestamp
            return false
        }
        // looked at only once it has gone: between two tries at connecting it is kept for the next look
        if (!session.copyToLaptop(text, quiet = true)) return false
        session.clipSeen = desc.timestamp
        return true
    }
    DisposableEffect(syncing, inFront) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener { sendNewClip() }
        if (syncing && inFront) clips.addPrimaryClipChangedListener(listener)
        onDispose { clips.removePrimaryClipChangedListener(listener) }
    }
    // (and again when the link is back up: a clip that couldn't go while it was down goes then)
    LaunchedEffect(syncing, inFront, state.status == LinkStatus.Online) {
        if (!syncing || !inFront) return@LaunchedEffect
        // The window is told it has the focus a moment before the clipboard lets it read: on
        // coming back from another app the first look can find nothing there. So a few looks.
        for (wait in longArrayOf(0, 120, 300, 700)) {
            delay(wait)
            if (clips.primaryClipDescription != null) {
                sendNewClip()
                break
            }
        }
    }

    // Looking for laptops while there is none to show; once connected, one look keeps the saved ticket current.
    LaunchedWhileResumed(online) {
        viewModel.findLaptops()
        while (true) {
            delay(if (online) 3_000 else 4_000)
            if (online) session.ping() else viewModel.findLaptops()
        }
    }

    val autoKeyboard by rememberUpdatedState(config.autoKeyboard)
    val imeUp by rememberUpdatedState(imeVisible)
    // autocorrect stays out of terminals: a corrected command is a wrong one
    var inTerminal by remember { mutableStateOf(false) }
    // whether the last click was in a text field, so only a click into one raises the keyboard
    var wasInField by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        viewModel.events.collect { event ->
            when (event) {
                is LinkEvent.Toast -> toast = event.message
                is LinkEvent.TextFocus -> {
                    // a click moved the laptop's cursor: what the keyboard had typed is no longer where it was
                    keyView?.reset()
                    inTerminal = event.terminal
                }
                else -> Unit
            }
            when (event) {
                // The laptop looks after every click and says whether a text field has focus, each time.
                // The keyboard rises only when a click moves into a text field from somewhere you don't
                // type: clicking around inside a field, or an app that keeps one focused, never brings
                // back a keyboard that was put away.
                is LinkEvent.TextFocus -> {
                    val into = event.focused && !wasInField
                    wasInField = event.focused
                    if (autoKeyboard) {
                        if (into && !imeUp) {
                            raisedForField = true
                            keyView?.showKeyboard()
                        } else if (!event.focused && raisedForField) {
                            raisedForField = false
                            keyView?.hideKeyboard()
                        }
                    }
                }
                // copied on the laptop while the two are kept in step: here too, without a word
                // (when both have copied something new the phone's wins: it goes over, and this is dropped)
                is LinkEvent.Clipboard -> if (event.sync) {
                    if (!sendNewClip()) clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Laptop", event.text)))
                } else if (event.text.isEmpty()) {
                    toast = "Nothing is copied on $laptopName"
                } else {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Laptop", event.text)))
                    toast = "Copied from $laptopName"
                }
                is LinkEvent.Toast -> Unit
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(AppMotion.Link.TOAST_MS)
            toast = null
        }
    }
    LaunchedEffect(imeVisible) { if (!imeVisible) raisedForField = false }
    LaunchedEffect(online) { if (online) viewModel.fillMissingIcons() }
    // The keyboard belongs to the state it was raised in: the list's address field, or the laptop.
    LaunchedEffect(online) {
        // the shortcut sheet is kept: it shows again when the link is back (from Settings → AI, say)
        if (!online) {
            sheet = false
            arranging = false
        }
        raisedForField = false
        keyView?.hideKeyboard()
    }

    fun typed(text: String) {
        val chord = if (mods.isEmpty()) null else chordKey(text)
        if (chord != null) {
            session.key(chord, mods)
            mods = emptySet()
        } else {
            session.text(text)
        }
    }

    fun pressed(name: String) {
        session.key(name, mods)
        if (mods.isNotEmpty()) mods = emptySet()
    }

    fun toggleKeyboard() {
        raisedForField = false
        if (imeVisible) keyView?.hideKeyboard() else keyView?.showKeyboard()
    }

    // Each switch says what it did: the two sounds are easy to mix up.
    val actions = remember(session, laptopName) {
        LinkActions(
            laptopSound = { on ->
                session.setLaptopSound(on)
                toast = if (on) "$laptopName's sound plays here now" else "$laptopName's sound stays on $laptopName"
            },
            phoneAudio = { on ->
                session.setPhoneAudio(on)
                toast = if (on) "This phone's sound plays on $laptopName now" else "This phone's sound is back here"
            },
            sendClipboard = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    if (text.isEmpty()) toast = "Nothing is copied on this phone" else session.copyToLaptop(text)
                }
            },
            fetchClipboard = { session.askClipboard() },
            clipSync = { on ->
                // what is on the phone's clipboard when it is turned on stays put: only what is copied from here on goes over
                // (an empty clipboard counts as looked at too: Android empties it after an hour)
                if (on) session.clipSeen = clips.primaryClipDescription?.timestamp ?: 0L
                session.setClipSync(on)
                toast = if (on) "What you copy here or on $laptopName is there on both" else "The clipboards go their own way again"
            },
        )
    }

    Box(Modifier.fillMaxSize().background(if (immersive) Color.Black else Background)) {
        AnimatedContent(
            targetState = online,
            transitionSpec = {
                (fadeIn(AppMotion.Link.picture()) + scaleIn(AppMotion.Link.swipe(), initialScale = 0.96f)) togetherWith fadeOut(AppMotion.fadeTween())
            },
            label = "link",
        ) { connected ->
            when {
                !connected -> LinkFinder(
                    state = state, config = config, laptops = laptops,
                    onPick = viewModel::connectTo, onAddress = viewModel::connectTo,
                    onRetry = { session.reconnect() }, onForget = viewModel::forget,
                    onSettings = onSettings,
                    onWake = {
                        viewModel.wake()
                        toast = "Waking $laptopName. Give it a few seconds."
                    },
                    onScan = { scanInvite(context, viewModel::offer) { toast = it } },
                    onRemove = { key ->
                        viewModel.remove(key)
                        toast = "Removed from this phone"
                    },
                )
                landscape -> Sideways(
                    viewModel = viewModel, state = state, config = config, geometry = geometry, hazeState = hazeState,
                    imeVisible = imeVisible, mods = mods, onMods = { mods = it }, onKey = ::pressed,
                    actions = actions, onKeyboard = ::toggleKeyboard, onUpright = { viewModel.orientation = 2 },
                )
                else -> Upright(
                    viewModel = viewModel, state = state, config = config, geometry = geometry, hazeState = hazeState,
                    laptopName = laptopName, imeVisible = imeVisible, mods = mods, onMods = { mods = it }, onKey = ::pressed,
                    actions = actions, onKeyboard = ::toggleKeyboard, onMore = { sheet = true },
                    onSideways = { viewModel.orientation = 1 }, onNavigateBack = viewModel::disconnect,
                    arranging = arranging, onArranging = { arranging = it },
                    onAddShortcut = { openShortcut(null) }, onEditShortcut = { openShortcut(it) },
                )
            }
        }
        // The phone's keyboard types into this; it is never seen.
        AndroidView(
            factory = { LinkKeyView(it).also { view -> keyView = view } },
            update = { view ->
                view.onText = ::typed
                view.onKey = ::pressed
                view.autocorrect = config.nativeKeyboard && !inTerminal
            },
            modifier = Modifier.size(1.dp).alpha(0f),
        )
        LinkToast(toast, hazeState, Modifier.align(Alignment.TopCenter))
        // A code was scanned or a link opened: nothing connects until the owner says so.
        viewModel.offered?.let { invite ->
            PairQuestion(invite, onPair = viewModel::acceptOffer, onDismiss = viewModel::declineOffer)
        }
        // The code was for a computer this phone has already: one machine is one entry.
        viewModel.alreadyPaired?.let { computer ->
            AlreadyPaired(
                computer.name, connected = viewModel.isConnectedTo(computer),
                onConnect = viewModel::connectAlreadyPaired, onDismiss = viewModel::dismissAlreadyPaired,
            )
        }
    }

    if (sheet && online) {
        LinkSheet(
            viewModel = viewModel, state = state, config = config, laptopName = laptopName, actions = actions,
            onDismiss = { sheet = false },
            onSettings = onSettings,
        )
    }
    val shortcutSheet = viewModel.shortcutSheet
    if (shortcutSheet != null && online) {
        LinkShortcutSheet(
            viewModel = viewModel, state = state, laptopName = laptopName, sheet = shortcutSheet,
            onAiSettings = onAiSettings,
            onDismiss = { viewModel.shortcutSheet = null },
        )
    }
}

/** The switches that tell you what they did, shared by the dock, the glass panel and the sheet. */
internal class LinkActions(
    val laptopSound: (Boolean) -> Unit,
    val phoneAudio: (Boolean) -> Unit,
    val sendClipboard: () -> Unit,
    val fetchClipboard: () -> Unit,
    val clipSync: (Boolean) -> Unit,
)

// ── upright: screen on top, trackpad under it ──────────────────────────────────

@Composable
private fun Upright(
    viewModel: LinkViewModel,
    state: LinkState,
    config: LinkConfig,
    geometry: PreviewGeometry,
    hazeState: HazeState,
    laptopName: String,
    imeVisible: Boolean,
    mods: Set<String>,
    onMods: (Set<String>) -> Unit,
    onKey: (String) -> Unit,
    actions: LinkActions,
    onKeyboard: () -> Unit,
    onMore: () -> Unit,
    onSideways: () -> Unit,
    onNavigateBack: () -> Unit,
    arranging: Boolean,
    onArranging: (Boolean) -> Unit,
    onAddShortcut: () -> Unit,
    onEditShortcut: (LinkShortcut) -> Unit,
) {
    val session = viewModel.session
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val shortcuts by viewModel.shortcuts.collectAsState()
    // the last shortcut gone (from the row, its menu or its sheet), there is nothing left to arrange
    LaunchedEffect(shortcuts.isEmpty()) { if (shortcuts.isEmpty() && arranging) onArranging(false) }
    // a tandem that can't report the pointer or send the sound alone always shows the picture
    val showPicture = config.showPicture || !state.canHidePicture
    var clipMenu by remember { mutableStateOf(false) }
    val reconnecting = state.status == LinkStatus.Reconnecting
    var padDown by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Zoomed in (or set to start that way), the picture takes its full share of the screen; at its
        // whole size it is only as tall as the monitor's shape needs, so an ultrawide leaves no black
        // bands and the trackpad gets the room. The shape is the monitor the picture shows (it changes
        // when a swipe lands), and the box's height moves in the layout pass (the picture inside it
        // lays itself out again for that short while).
        val most = maxHeight * 0.29f
        val mon = state.shownMonitor
        val aspect = if (mon != null && mon.width > 0 && mon.height > 0) mon.width.toFloat() / mon.height else 16f / 10f
        val zoomedIn = viewModel.zoom?.let { it > 1.01f } ?: config.fillPreview
        // A monitor of another shape changes the box once its slide is over, never during it, and
        // never while a finger is on the pad (its top edge would move under the finger). Zoom acts at once.
        val shape = minOf(most, maxWidth / aspect)
        var settledShape by remember { mutableStateOf(shape) }
        LaunchedEffect(shape, padDown) {
            if (settledShape != shape && !padDown) {
                delay(AppMotion.Link.SWIPE_SETTLE_MS)
                settledShape = shape
            }
        }
        val open = if (zoomedIn) most else settledShape
        // folded away, the screen takes no room at all: the shortcuts and the trackpad have it
        val pictureHeight = animateDpAsState(if (showPicture) open else 0.dp, AppMotion.Link.panel(), label = "picture")
        // the pill names the monitor the pointer is on while there is no picture to name
        val pointer = session.pointer.collectAsState()
        val pointerOn by remember(state.monitors) { derivedStateOf { monitorUnder(state.monitors, pointer.value) } }
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            // The header: back, which laptop and screen (opens the sheet), zoom, and at the far right the
            // button that folds the screen away and brings it back. Nothing sits on the picture.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(AppIcons.ArrowBack, "Back") { onNavigateBack() }
                Spacer(Modifier.width(8.dp))
                // the pill keeps to its own width at the start; the buttons stand at the far end
                Box(Modifier.weight(1f)) {
                    StatusPill(state, laptopName, monitor = if (showPicture) state.shownMonitor else pointerOn ?: state.shownMonitor, onClick = onMore)
                }
                AnimatedVisibility(
                    visible = showPicture,
                    enter = fadeIn(AppMotion.fadeTween()) + expandHorizontally(AppMotion.Link.panel()),
                    exit = fadeOut(AppMotion.fadeTween()) + shrinkHorizontally(AppMotion.Link.panel()),
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        val zoomed = geometry.zoomState.floatValue > 1.01f
                        RoundButton(if (zoomed) AppIcons.ZoomOut else AppIcons.ZoomIn, if (zoomed) "Show the whole screen" else "Zoom in") {
                            viewModel.zoom = if (zoomed) 1f else max(geometry.fillZoom, 2f)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                ShortcutsToggle(config.showShortcuts, onToggle = { viewModel.change { it.copy(showShortcuts = !it.showShortcuts) } })
                if (state.canHidePicture) {
                    Spacer(Modifier.width(8.dp))
                    ScreenToggle(showPicture, onToggle = { session.setPicture(!showPicture) })
                }
            }
            // The laptop's screen, edge to edge for the most picture.
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val h = pictureHeight.value.roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(placeable.width, h) { placeable.place(0, 0) }
                    },
            ) {
                // the picture fades as its box closes, and fades in as it opens
                FadingPicture(showPicture) {
                    LinkPreview(
                        session = session, geometry = geometry,
                        zoom = viewModel.zoom, fill = config.fillPreview,
                        error = state.videoError, note = if (reconnecting) "Reconnecting" else null,
                        drawPointer = config.localPointer && LinkSession.LOCAL_CURSOR in state.caps,
                        pointerSize = config.pointerSize,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .hazeSource(hazeState)
                            .pointerInput(session) {
                                detectTapGestures(onTap = { at ->
                                    geometry.toScreen(at)?.let { p ->
                                        haptics.tick()
                                        session.pointAt(p.x, p.y, click = true)
                                    }
                                })
                            }
                            .pointerInput(session) {
                                detectTransformGestures { _, pan, change, _ ->
                                    if (change != 1f) viewModel.zoom = (geometry.zoom * change).coerceIn(1f, 8f)
                                    geometry.nudge(pan)
                                }
                            },
                    )
                }
            }

            // the shortcuts, over the pad; folded away from the header, and out of the way while typing
            AnimatedVisibility(
                visible = !imeVisible && config.showShortcuts,
                enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
            ) {
                // a row under the screen; with the screen folded away, a grid four across
                ShortcutRow(
                    shortcuts = shortcuts, store = viewModel.shortcutStore,
                    arranging = arranging, onArranging = onArranging,
                    onRun = { viewModel.runShortcut(it) }, onAdd = onAddShortcut, onEdit = onEditShortcut,
                    onSetOn = { s, on -> viewModel.setShortcutOn(s, on) },
                    onRemove = { viewModel.removeShortcut(it.id) },
                    grid = !showPicture,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            LinkTrackpad(
                viewModel = viewModel, config = config,
                hint = !viewModel.touched && !imeVisible,
                buttons = !imeVisible,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                nativePad = LinkSession.TOUCHPAD in state.caps,
                onTouch = { padDown = it },
            )
            AnimatedVisibility(
                visible = imeVisible,
                enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
            ) {
                KeyRow(mods, LinkHandsTone, onMods, onKey)
            }
            // The dock: Health's meter tiles, each in its colour; the end tiles take the trackpad's corners.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val first = RoundedCornerShape(topStart = 18.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 28.dp)
                val middle = RoundedCornerShape(14.dp)
                val last = RoundedCornerShape(topStart = 14.dp, topEnd = 18.dp, bottomEnd = 28.dp, bottomStart = 14.dp)
                DockTile(AppIcons.Keyboard, "Keyboard", LinkHandsTone, first, Modifier.weight(1f), on = imeVisible) { onKeyboard() }
                DockTile(AppIcons.Fullscreen, "Full screen", LinkScreenTone, middle, Modifier.weight(1f)) { onSideways() }
                DockTile(
                    if (state.laptopSound) AppIcons.LaptopSound else AppIcons.LaptopSoundOff,
                    if (state.laptopSound) "Laptop's sound plays here" else "Play the laptop's sound here", LinkSoundTone, middle, Modifier.weight(1f),
                    on = state.laptopSound,
                ) { actions.laptopSound(!state.laptopSound) }
                Box(Modifier.weight(1f)) {
                    DockTile(AppIcons.ClipboardSwap, "Clipboard", LinkClipboardTone, middle, Modifier.fillMaxWidth()) { clipMenu = true }
                    DropdownMenu(expanded = clipMenu, onDismissRequest = { clipMenu = false }, modifier = Modifier.background(Surface)) {
                        MenuRow("Send what's copied here to $laptopName", AppIcons.ClipboardCopy) { clipMenu = false; actions.sendClipboard() }
                        MenuRow("Copy what's copied on $laptopName", AppIcons.ClipboardPaste) { clipMenu = false; actions.fetchClipboard() }
                        if (LinkSession.CLIP_SYNC in state.caps) {
                            MenuRow(if (config.clipSync) "Stop keeping them in sync" else "Keep both clipboards in sync", AppIcons.Repeat) {
                                clipMenu = false; actions.clipSync(!config.clipSync)
                            }
                        }
                    }
                }
                DockTile(AppIcons.Ellipsis, "More", TextSecondary, last, Modifier.weight(1f)) { onMore() }
            }
        }
    }
}

/** The full-screen panel's glass: a squircle folded to its handle, the same corners unfolded. */
private val PanelShape = SquircleShape(26.dp)

// ── on its side: the laptop's screen fills the phone ────────────────────────────

@Composable
private fun Sideways(
    viewModel: LinkViewModel,
    state: LinkState,
    config: LinkConfig,
    geometry: PreviewGeometry,
    hazeState: HazeState,
    imeVisible: Boolean,
    mods: Set<String>,
    onMods: (Set<String>) -> Unit,
    onKey: (String) -> Unit,
    actions: LinkActions,
    onKeyboard: () -> Unit,
    onUpright: () -> Unit,
) {
    val session = viewModel.session
    val haptics = rememberHaptics()
    val shortcuts by viewModel.shortcuts.collectAsState()
    var open by remember { mutableStateOf(true) }
    // every touch of a control starts the wait again
    var used by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, used) {
        if (open) {
            delay(AppMotion.Link.CONTROLS_STAY_MS)
            open = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!config.showPicture && state.canHidePicture) {
            LinkTrackpad(
                viewModel = viewModel, config = config, hint = !viewModel.touched && !imeVisible, buttons = !imeVisible,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState).padding(12.dp),
                nativePad = LinkSession.TOUCHPAD in state.caps,
            )
        } else LinkPreview(
            session = session, geometry = geometry, zoom = 1f, fill = false, error = state.videoError,
            note = if (state.status == LinkStatus.Reconnecting) "Reconnecting" else null,
            drawPointer = config.localPointer && LinkSession.LOCAL_CURSOR in state.caps,
            pointerSize = config.pointerSize,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .then(
                    if (viewModel.touchMode) {
                        Modifier.touchscreen(session, geometry::toScreen, onTick = haptics::tick, onHold = haptics::click)
                    } else {
                        Modifier.trackpad(session, config.speed, config.naturalScroll, onTick = haptics::tick, onHold = haptics::click)
                    },
                ),
        )

        // The controls and the shortcuts: one glass panel that unfolds from its handle.
        PanelStack(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .chromeGlass(hazeState, PanelShape)
                // it grows from its handle: the handle stays put and in view, and the rest is uncovered from it
                .animateContentSize(AppMotion.Link.panel(), alignment = Alignment.TopEnd)
                .padding(5.dp),
            controls = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (open) {
                        val touch = viewModel.touchMode
                        // with no picture to touch, the whole phone is the trackpad
                        if (config.showPicture || !state.canHidePicture) {
                            PanelButton(0, if (touch) AppIcons.Scan else AppIcons.MousePointer, if (touch) "Touch" else "Trackpad", LinkHandsTone, on = touch) {
                                used++
                                viewModel.touchMode = !touch
                            }
                        }
                        PanelButton(1, AppIcons.Keyboard, "Keyboard", LinkHandsTone, on = imeVisible) { used++; onKeyboard() }
                        PanelButton(2, if (state.laptopSound) AppIcons.LaptopSound else AppIcons.LaptopSoundOff, "Sound", LinkSoundTone, on = state.laptopSound) {
                            used++
                            actions.laptopSound(!state.laptopSound)
                        }
                        PanelDivider()
                        PanelButton(3, AppIcons.Play, "Play") { used++; session.media("play") }
                        PanelButton(4, AppIcons.VolumeLow, "Quieter") { used++; session.media("voldown") }
                        PanelButton(5, AppIcons.Volume, "Louder") { used++; session.media("volup") }
                        PanelDivider()
                        PanelButton(6, AppIcons.Minimize, "Exit") { onUpright() }
                    }
                    PanelHandle(open) { open = !open }
                }
            },
            shelf = if (open && shortcuts.isNotEmpty()) {
                { PanelShortcuts(shortcuts, viewModel.shortcutStore, onUsed = { used++ }) { viewModel.runShortcut(it) } }
            } else null,
        )

        AnimatedVisibility(
            visible = imeVisible,
            enter = slideInVertically(AppMotion.slideTween()) { it } + fadeIn(AppMotion.fadeTween()),
            exit = slideOutVertically(AppMotion.slideTween()) { it } + fadeOut(AppMotion.fadeTween()),
            modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).chromeGlass(hazeState, RoundedCornerShape(18.dp))) {
                KeyRow(mods, LinkHandsTone, onMods, onKey)
            }
        }
    }
}

/** The laptop's picture fading out as its box closes and in as it opens. */
@Composable
private fun FadingPicture(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.Link.picture()),
        exit = fadeOut(AppMotion.fadeTween()),
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

/**
 * "Pair with this computer?", before anything is sent to it. A link can come from anywhere (a web
 * page can open one), so the owner sees the name it claims and the address it would connect to.
 */
@Composable
private fun PairQuestion(invite: com.t3lluz.weave.data.link.LinkInvite, onPair: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Pair with ${invite.name}?", style = HealthDisplay, fontSize = 22.sp) },
        text = {
            Column {
                Text("This phone will connect to it and be able to see and control it.", fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    invite.addresses.take(3).joinToString("\n") + if (invite.addresses.size > 3) "\nand ${invite.addresses.size - 3} more" else "",
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text("Only pair with a code from your own computer's screen.", fontSize = 13.sp, lineHeight = 18.sp, color = TextTertiary)
            }
        },
        confirmButton = { PillButton(icon = AppIcons.Check, label = "Pair", accent = LinkScreenTone, emphasized = true, onClick = onPair) },
        dismissButton = { PillButton(icon = AppIcons.Close, label = "Not now", accent = TextSecondary, onClick = onDismiss) },
    )
}

/** A scanned code turned out to be for a computer this phone has already: said, with the way on. */
@Composable
private fun AlreadyPaired(name: String, connected: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(if (connected) "Already connected to $name" else "$name is already paired", style = HealthDisplay, fontSize = 22.sp) },
        text = {
            Column {
                Text(
                    if (connected) "This phone is using it right now. There is nothing to pair."
                    else "This phone has it already, so it isn't added a second time. You can connect to it as it is saved.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("To pair it afresh, remove it from the list first, then scan a code.", fontSize = 13.sp, lineHeight = 18.sp, color = TextTertiary)
            }
        },
        confirmButton = {
            if (connected) PillButton(icon = AppIcons.Check, label = "OK", accent = LinkScreenTone, emphasized = true, onClick = onDismiss)
            else PillButton(icon = AppIcons.Link, label = "Connect", accent = LinkScreenTone, emphasized = true, onClick = onConnect)
        },
        dismissButton = { if (!connected) PillButton(icon = AppIcons.Close, label = "Not now", accent = TextSecondary, onClick = onDismiss) },
    )
}

/** How often at most a swallowed back says where the way out is. */
private const val BACK_SAYS_EVERY_MS = 20_000L

/** [android.content.ClipDescription.EXTRA_IS_SENSITIVE], by its value: the constant itself is API 33. */
private const val CLIP_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
