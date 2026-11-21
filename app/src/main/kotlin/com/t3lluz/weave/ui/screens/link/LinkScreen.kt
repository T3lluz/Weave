package com.t3lluz.weave.ui.screens.link

import android.content.ClipData
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
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
import com.t3lluz.weave.ui.components.BackTile
import com.t3lluz.weave.ui.components.IconTile
import com.t3lluz.weave.ui.components.TileRow
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
    // Where the keyboard is heading, known the moment it starts to move. The screen rearranges by
    // this, in step with the keyboard: by whether it is still showing, the mouse buttons and the
    // shortcuts would only come back once it had gone all the way down.
    val imeHeadingUp = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
    val laptopName = state.laptop.ifBlank { config.name.ifBlank { config.host.substringBefore(',') } }
    // the laptop's picture is what the glass over it blurs
    val hazeState = rememberHazeState()

    // The edge between monitors, felt whatever the screen shows (picture, full screen, pad only):
    // a light tick meeting it, two creaks as the push builds, the snap through it. The bump and the
    // creaks wait for the slide's ticks if they are still running under the thumb; the snap never does.
    val edgeHaptics = com.t3lluz.weave.ui.util.rememberHaptics()
    DisposableEffect(session.edge, edgeHaptics) {
        val felt: (com.t3lluz.weave.data.link.MonitorEdge.Felt, Int) -> Unit = { f, nth ->
            when (f) {
                com.t3lluz.weave.data.link.MonitorEdge.Felt.MET -> if (!edgeHaptics.busy) edgeHaptics.edgeMeet()
                com.t3lluz.weave.data.link.MonitorEdge.Felt.CREAK -> if (!edgeHaptics.busy) edgeHaptics.creak(nth)
                com.t3lluz.weave.data.link.MonitorEdge.Felt.FREED -> edgeHaptics.comeFree()
            }
        }
        session.edge.onFelt = felt
        onDispose { if (session.edge.onFelt === felt) session.edge.onFelt = null }
    }

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

    // The link's comings and goings, felt: a knock answered when the computer is there, one soft
    // step down when it drops (it comes back by itself), a tick when it is back.
    val linkHaptics = rememberHaptics()
    var statusWas by remember { mutableStateOf(state.status) }
    LaunchedEffect(state.status) {
        val was = statusWas
        statusWas = state.status
        when {
            state.status == LinkStatus.Online && was == LinkStatus.Reconnecting -> linkHaptics.back()
            state.status == LinkStatus.Online && was != LinkStatus.Online -> {
                if (viewModel.justPaired) linkHaptics.paired() else linkHaptics.connected()
                viewModel.justPaired = false
            }
            state.status == LinkStatus.Reconnecting && was == LinkStatus.Online -> linkHaptics.lost()
            // it couldn't be reached, or its owner said no: a step down that ends on a dull stop
            (state.status == LinkStatus.Failed || state.status == LinkStatus.Denied) && was != state.status -> linkHaptics.failed()
        }
    }

    // Going over to another monitor is felt under the thumb. With the picture showing, the picture
    // says so itself as it slides (LinkPreview); folded away, it is one soft step here.
    val shownMonitor = state.shownMonitor?.name
    var monitorWas by remember { mutableStateOf(shownMonitor) }
    LaunchedEffect(shownMonitor) {
        val pictureShows = config.showPicture || !state.canHidePicture
        if (monitorWas != null && shownMonitor != null && shownMonitor != monitorWas && !pictureShows) linkHaptics.slide()
        monitorWas = shownMonitor
    }

    // While the remote is up, Android's back does not leave it: a swipe in from the side belongs to
    // the trackpad, and one that the system takes anyway must not end the session. The arrow in the
    // corner is the way out; back says so, once in a while.
    var backSaidAt by remember { mutableStateOf(0L) }
    BackHandler {
        when {
            arranging -> arranging = false
            // on its side, back is the way out of full screen, however the phone got there
            landscape && online -> viewModel.orientation = 2
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
    // Upright, the navigation bar is hidden too, the sticky way, with its room kept (the layout pads
    // for it as if it were there). Android lets an app keep the back gesture off only 200 dp of each
    // side, except while the navigation bar is hidden like this: then the remote keeps it off both
    // sides top to bottom (systemGestureExclusion below), so a stroke on the pad or the scroll strip
    // that starts at the edge is never taken for "back" (Fredde, 2026-11-21). A swipe up from the
    // foot still shows the bars and goes home, as it must; the back tile leaves the remote.
    LaunchedEffect(immersive, online, resumed) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive || online) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (!immersive) bars.show(WindowInsetsCompat.Type.statusBars())
            // asked again once the rotated window has settled: the first ask can land before it has the bars
            repeat(3) {
                bars.hide(if (immersive) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.navigationBars())
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
                    linkHaptics.reject()
                    toast = "Nothing is copied on $laptopName"
                } else {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Laptop", event.text)))
                    linkHaptics.confirm()
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
        if (imeHeadingUp) keyView?.hideKeyboard() else keyView?.showKeyboard()
    }

    // Each switch says what it did: the two sounds are easy to mix up.
    val actions = remember(session, laptopName, linkHaptics) {
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
                    if (text.isEmpty()) {
                        linkHaptics.reject()
                        toast = "Nothing is copied on this phone"
                    } else {
                        session.copyToLaptop(text)
                    }
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
                    imeVisible = imeHeadingUp, mods = mods, onMods = { mods = it }, onKey = ::pressed,
                    actions = actions, onKeyboard = ::toggleKeyboard, onUpright = { viewModel.orientation = 2 },
                )
                else -> Upright(
                    viewModel = viewModel, state = state, config = config, geometry = geometry, hazeState = hazeState,
                    laptopName = laptopName, imeVisible = imeHeadingUp, mods = mods, onMods = { mods = it }, onKey = ::pressed,
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
        LaunchedEffect(viewModel.offered != null || viewModel.alreadyPaired != null) {
            if (viewModel.offered != null || viewModel.alreadyPaired != null) linkHaptics.click()
        }
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
@OptIn(ExperimentalLayoutApi::class)
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
        val shape = minOf(most, maxWidth / aspect)
        val open = if (zoomedIn) most else shape
        // folded away, the screen takes no room at all: the shortcuts and the trackpad have it
        // The box moves on the panel's spring, at once when the stream goes to a monitor of another shape:
        // it used to wait for the slide and for the finger to lift, which got in the way (Fredde,
        // 2026-11-21). The pad under it moves with it, even under a finger: the trackpad takes that
        // out of the finger's moves (its origin).
        val pictureHeight = animateDpAsState(if (showPicture) open else 0.dp, AppMotion.Link.panel(), label = "picture")
        // the pill names the monitor the pointer is on while there is no picture to name
        val pointer = session.pointer.collectAsState()
        val pointerOn by remember(state.monitors) { derivedStateOf { monitorUnder(state.monitors, pointer.value) } }
        Column(Modifier.fillMaxSize().systemGestureExclusion().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).imePadding()) {
            // The top row, in the dock's tiles: back, which laptop and screen (opens the sheet),
            // zoom, and at the far end the tiles that fold the shortcuts and the screen away and
            // bring them back. Each wears its own colour, as the dock's do. Nothing sits on the picture.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = TileRow.Side, end = TileRow.Side, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackTile(onNavigateBack, label = "Leave $laptopName")
                Spacer(Modifier.width(TileRow.Gap))
                // the wide tile takes what the others leave, and gives room as the zoom arrives
                StatusTile(
                    state, laptopName, Modifier.weight(1f),
                    monitor = if (showPicture) state.shownMonitor else pointerOn ?: state.shownMonitor, onClick = onMore,
                )
                AnimatedVisibility(
                    visible = showPicture,
                    enter = fadeIn(AppMotion.fadeTween()) + expandHorizontally(AppMotion.Link.panel()),
                    exit = fadeOut(AppMotion.fadeTween()) + shrinkHorizontally(AppMotion.Link.panel()),
                ) {
                    Row {
                        Spacer(Modifier.width(TileRow.Gap))
                        val zoomed = geometry.zoomState.floatValue > 1.01f
                        IconTile(
                            if (zoomed) AppIcons.ZoomOut else AppIcons.ZoomIn, if (zoomed) "Show the whole screen" else "Zoom in",
                            LinkScreenTone, TileRow.Middle, Modifier.width(TileRow.TopTile), on = zoomed,
                        ) {
                            viewModel.zoom = if (zoomed) 1f else max(geometry.fillZoom, 2f)
                        }
                    }
                }
                Spacer(Modifier.width(TileRow.Gap))
                ShortcutsToggle(
                    config.showShortcuts, if (state.canHidePicture) TileRow.Middle else TileRow.TopLast,
                    onToggle = { viewModel.change { it.copy(showShortcuts = !it.showShortcuts) } }, Modifier.width(TileRow.TopTile),
                )
                if (state.canHidePicture) {
                    Spacer(Modifier.width(TileRow.Gap))
                    ScreenToggle(showPicture, TileRow.TopLast, onToggle = { session.setPicture(!showPicture) }, Modifier.width(TileRow.TopTile))
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
                                    if (change != 1f) {
                                        val to = (geometry.zoom * change).coerceIn(1f, 8f)
                                        // pinched back to the whole screen: it stops there, and says so
                                        if (to == 1f && geometry.zoom > 1f) haptics.notch()
                                        viewModel.zoom = to
                                    }
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
                    shortcuts = shortcuts, store = viewModel.shortcutStore, running = viewModel.running,
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
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = TileRow.Side, end = TileRow.Side, top = 6.dp),
                nativePad = LinkSession.TOUCHPAD in state.caps,
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
                Modifier.fillMaxWidth().padding(start = TileRow.Side, end = TileRow.Side, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(TileRow.Gap),
            ) {
                val middle = TileRow.Middle
                DockTile(AppIcons.Keyboard, "Keyboard", LinkHandsTone, TileRow.DockFirst, Modifier.weight(1f), on = imeVisible, switch = true) { onKeyboard() }
                DockTile(AppIcons.Fullscreen, "Full screen", LinkScreenTone, middle, Modifier.weight(1f)) { onSideways() }
                DockTile(
                    if (state.laptopSound) AppIcons.LaptopSound else AppIcons.LaptopSoundOff,
                    if (state.laptopSound) "Laptop's sound plays here" else "Play the laptop's sound here", LinkSoundTone, middle, Modifier.weight(1f),
                    on = state.laptopSound, switch = true,
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
                DockTile(AppIcons.Ellipsis, "More", TextSecondary, TileRow.DockLast, Modifier.weight(1f)) { onMore() }
            }
        }
    }
}

/**
 * The full-screen panel rests [PANEL_INSET] in from the screen's edges, so in a corner it sits
 * inside a phone's own rounded corner instead of running into it, and [HOLE_GAP] clear of the
 * camera. Its shape and measures are in LinkPanel.kt.
 */
private val PANEL_INSET = 16.dp
private val HOLE_GAP = 6.dp

/** The glass the keys sit on in full screen: a key is 6 dp in from its edge, and its corner 12. */
private val KEYS_PAD = 6.dp
private val KeysShape = RoundedCornerShape(18.dp)

/** The camera's holes in the screen, in px of the window, which the picture runs under on its side. */
@Composable
private fun cameraHoles(): List<Rect> {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    // which sides it is on: a phone turned right round has it on the other one, and nothing else says so
    val sides = listOf(cutout.getLeft(density, direction), cutout.getTop(density), cutout.getRight(density, direction), cutout.getBottom(density))
    return remember(view, sides) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) emptyList()
        else view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().map { it.toComposeRect() }
    }
}

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
    val context = LocalContext.current
    val cloth = remember(context) { com.t3lluz.weave.ui.util.PaperFeel(context) }
    // every touch of a control starts the wait again
    var used by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, used) {
        if (open) {
            delay(AppMotion.Link.CONTROLS_STAY_MS)
            open = false
        }
    }

    // the back gesture is kept off both sides top to bottom here too (the bars are hidden: see LinkScreen)
    BoxWithConstraints(Modifier.fillMaxSize().systemGestureExclusion()) {
        // Where the panel's bubble rests, and which way the panel unfolds from there.
        val density = LocalDensity.current
        val holes = cameraHoles()
        // with no picture to touch, the whole phone is the trackpad, and the panel is a control short
        val touchable = config.showPicture || !state.canHidePicture
        val unfolded = PANEL_BUTTON * (if (touchable) 7 else 6) + PANEL_DIVIDER * 2 + PANEL_BUBBLE
        val field = remember(constraints.maxWidth, constraints.maxHeight, holes, unfolded, density) {
            with(density) {
                PanelField(
                    constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), PANEL_BUBBLE.toPx(), PANEL_INSET.toPx(),
                    holes, HOLE_GAP.toPx(), unfolded.toPx(),
                )
            }
        }
        val place = remember(field) { PanelPlace(field, field.from(viewModel.panelSpot)) }
        // the keyboard and the keys over it take the foot of the screen: a panel down there moves up
        val ime = WindowInsets.ime
        var keysHeight by remember { mutableIntStateOf(0) }
        val keys by animateIntAsState(if (imeVisible) keysHeight else 0, AppMotion.Link.panel(), label = "keys")

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
                        // the same pad as upright, without the cloth to see: its taps, its hold and its grain
                        Modifier.trackpad(
                            session, config.speed, config.naturalScroll,
                            onTick = haptics::tick, onHold = haptics::grab, onDrop = haptics::drop,
                            onTap = { fingers, _ -> haptics.taps(fingers) },
                            onGlide = cloth::glide, onFingers = { if (it.isEmpty()) cloth.lift() },
                            onSweep = { haptics.swoosh() },
                        )
                    },
                ),
        )

        // The controls and the shortcuts: one glass panel that unfolds from its handle, wherever
        // on the screen's edges the handle was left, into the room there is from there.
        var holding by remember { mutableStateOf(false) }
        PanelStack(
            Modifier
                .panelPlace(
                    place, floor = { ime.getBottom(density) + keys },
                    onTap = {
                        haptics.tick()
                        open = !open
                    },
                    // held, the handle offers the way out: felt as it does, and let go there to take it
                    onHolding = { on ->
                        if (on) haptics.thresholdActivate()
                        holding = on
                    },
                    onLeave = onUpright,
                    onGrab = {
                        haptics.grab()
                        open = false
                    },
                    onDrop = {
                        haptics.drop()
                        viewModel.panelSpot = it
                    },
                )
                .chromeGlass(hazeState, PanelShape)
                // it grows from its handle: the handle stays put and in view, and the rest is uncovered from it
                .animateContentSize(AppMotion.Link.panel(), alignment = place.corner)
                .padding(PANEL_PAD),
            shelfAbove = place.up,
            controls = {
                // From the handle out: the way out of full screen first, under the thumb that opened
                // the panel, then the hands, the sound and the media keys. The same order whichever
                // end of the panel the handle is at, so the controls unfold away from the screen's edge.
                val touch = viewModel.touchMode
                val controls: List<Pair<String, @Composable () -> Unit>> = buildList {
                    add("exit" to @Composable { PanelButton(0, AppIcons.Minimize, "Exit") { onUpright() } })
                    add("line1" to @Composable { PanelDivider() })
                    if (touchable) {
                        add("touch" to @Composable {
                            PanelButton(1, if (touch) AppIcons.Scan else AppIcons.MousePointer, if (touch) "Touch" else "Trackpad", LinkHandsTone, on = touch, switch = true) {
                                used++
                                viewModel.touchMode = !touch
                            }
                        })
                    }
                    add("keyboard" to @Composable { PanelButton(2, AppIcons.Keyboard, "Keyboard", LinkHandsTone, on = imeVisible, switch = true) { used++; onKeyboard() } })
                    add("sound" to @Composable {
                        PanelButton(3, if (state.laptopSound) AppIcons.LaptopSound else AppIcons.LaptopSoundOff, "Sound", LinkSoundTone, on = state.laptopSound, switch = true) {
                            used++
                            actions.laptopSound(!state.laptopSound)
                        }
                    })
                    add("line2" to @Composable { PanelDivider() })
                    add("play" to @Composable { PanelButton(4, AppIcons.Play, "Play") { used++; session.media("play") } })
                    add("quieter" to @Composable { PanelButton(5, AppIcons.VolumeLow, "Quieter") { used++; session.media("voldown") } })
                    add("louder" to @Composable { PanelButton(6, AppIcons.Volume, "Louder") { used++; session.media("volup") } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val handle = @Composable { PanelHandle(open, holding, onClick = { open = !open }, onLeave = onUpright) }
                    if (!place.left) handle()
                    if (open) (if (place.left) controls.asReversed() else controls).forEach { (name, control) -> key(name) { control() } }
                    if (place.left) handle()
                }
            },
            // out of the way while typing, as upright: the keyboard leaves room for one row
            shelf = if (open && shortcuts.isNotEmpty() && !imeVisible) {
                { PanelShortcuts(shortcuts, viewModel.shortcutStore, viewModel.running, above = place.up, onUsed = { used++ }) { viewModel.runShortcut(it) } }
            } else null,
        )

        AnimatedVisibility(
            visible = imeVisible,
            enter = slideInVertically(AppMotion.slideTween()) { it } + fadeIn(AppMotion.fadeTween()),
            exit = slideOutVertically(AppMotion.slideTween()) { it } + fadeOut(AppMotion.fadeTween()),
            modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
        ) {
            Box(Modifier.onSizeChanged { keysHeight = it.height }.padding(horizontal = 12.dp, vertical = 6.dp).chromeGlass(hazeState, KeysShape)) {
                // as far in from the glass's sides as from its top: a key's corner follows the glass's
                KeyRow(mods, LinkHandsTone, onMods, onKey, side = KEYS_PAD)
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
