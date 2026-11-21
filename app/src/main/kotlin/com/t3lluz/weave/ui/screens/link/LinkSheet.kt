package com.t3lluz.weave.ui.screens.link

import android.appwidget.AppWidgetManager
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.t3lluz.weave.data.link.LinkSession
import com.t3lluz.weave.widget.ShortcutsWidgetConfigActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkMonitor
import com.t3lluz.weave.data.link.LinkState
import com.t3lluz.weave.ui.components.LivePulseDot
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkClipboardTone
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import com.t3lluz.weave.ui.theme.LinkSoundTone
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.viewmodel.LinkViewModel

/**
 * Everything the dock doesn't hold, as Health's rooms: the screen shown (a map of the laptop's
 * monitors as they stand on the desk, a tap shows one), sound both ways and the media keys,
 * the trackpad, and the one-off actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(
    viewModel: LinkViewModel,
    state: LinkState,
    config: LinkConfig,
    laptopName: String,
    actions: LinkActions,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val sheetHaptics = com.t3lluz.weave.ui.util.rememberHaptics()
    val session = viewModel.session
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(laptopName, style = HealthDisplay, fontSize = 30.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LivePulseDot(color = Success)
                        Spacer(Modifier.width(7.dp))
                        Text(listOfNotNull("Connected", state.pingMs?.let { "$it ms away" }).joinToString(" · "), color = TextSecondary, fontSize = 14.sp)
                    }
                }
                PillButton(icon = AppIcons.LinkOff, label = "Disconnect", accent = TextSecondary, onClick = { onDismiss(); viewModel.disconnect() })
            }
            Spacer(Modifier.height(6.dp))

            HealthSection(tone = LinkScreenTone) {
                RoomTitle(AppIcons.Monitor, "Screen", LinkScreenTone)
                val picture = config.showPicture || !state.canHidePicture
                if (state.canHidePicture) {
                    ToggleRow("Show the screen", "Off: trackpad and keyboard only", config.showPicture, LinkScreenTone) {
                        session.setPicture(!config.showPicture)
                    }
                }
                if (state.monitors.size > 1 && picture) {
                    MonitorMap(state) { name -> session.showMonitor(name) }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("Follow the pointer", "Show whichever screen the pointer is on", state.followPointer, LinkScreenTone) {
                        session.showMonitor(if (state.followPointer) state.monitor else null)
                    }
                    if (LinkSession.POINTER in state.caps) {
                        ToggleRow("Hold at the edge", "Push a little further to cross to the next screen, with a peek of it first", config.edgeHold, LinkScreenTone) {
                            session.setEdgeHold(!config.edgeHold)
                        }
                    }
                }
                if (picture) {
                    Spacer(Modifier.height(10.dp))
                    Text("Picture", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
                    Segmented(
                        listOf("low" to "Light", "mid" to "Balanced", "high" to "Sharp"),
                        picked = state.quality ?: config.quality, tone = LinkScreenTone,
                    ) { session.setQuality(it) }
                    Spacer(Modifier.height(6.dp))
                    val picked = mapOf("low" to "Light", "mid" to "Balanced", "high" to "Sharp")[config.quality] ?: "your pick"
                    Text(
                        if ((state.quality ?: config.quality) != config.quality) "Lighter while the connection is slow. It goes back to $picked by itself." else "Light suits mobile data.",
                        color = TextTertiary, fontSize = 12.sp,
                    )
                }
            }

            HealthSection(tone = LinkShortcutTone) {
                RoomTitle(AppIcons.Bolt, "Shortcuts", LinkShortcutTone)
                ToggleRow("Show the shortcuts", "Off: the trackpad gets their room", config.showShortcuts, LinkShortcutTone) {
                    viewModel.change { it.copy(showShortcuts = !it.showShortcuts) }
                }
                // pick which ones, then the home screen asks where the widget goes
                val context = LocalContext.current
                if (remember { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }) {
                    ActionRow(AppIcons.Grid, "Put some on the home screen", LinkShortcutTone) { ShortcutsWidgetConfigActivity.newWidget(context) }
                }
            }

            HealthSection(tone = LinkSoundTone) {
                RoomTitle(AppIcons.Volume, "Sound", LinkSoundTone)
                // the keys first: they are what a hand comes here for most
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(
                        Triple(AppIcons.SkipBack, "Previous", "prev"),
                        Triple(AppIcons.Play, "Play or pause", "play"),
                        Triple(AppIcons.SkipForward, "Next", "next"),
                        Triple(AppIcons.VolumeOff, "Mute", "mute"),
                        Triple(AppIcons.VolumeLow, "Quieter", "voldown"),
                        Triple(AppIcons.Volume, "Louder", "volup"),
                    ).forEach { (icon, label, command) -> MediaKey(icon, label) { session.media(command) } }
                }
                ToggleRow("Hear $laptopName here", "It keeps playing there too", state.laptopSound, LinkSoundTone) { actions.laptopSound(!state.laptopSound) }
                // only matters while it plays here
                AnimatedVisibility(
                    visible = state.laptopSound,
                    enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                    exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
                ) {
                    ToggleRow("Make quiet sound louder", "Lifts a laptop mixed for headphones", config.levelSound, LinkSoundTone) { session.setLevelSound(!config.levelSound) }
                }
                ToggleRow("Play this phone's sound there", "This phone's speaker goes quiet meanwhile", state.phoneAudio, LinkSoundTone) { actions.phoneAudio(!state.phoneAudio) }
                // sound that follows the earbuds, while that lab is on (Settings → Labs)
                if ("roam" in state.caps) EarbudsRows(laptopName)
            }

            HealthSection(tone = LinkHandsTone) {
                RoomTitle(AppIcons.MousePointer, "Trackpad", LinkHandsTone)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Pointer speed", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(14.dp))
                    Slider(
                        value = config.speed,
                        onValueChange = { v ->
                            // a faint tick at every quarter of speed, as a dial has marks
                            if ((v * 4f).toInt() != (config.speed * 4f).toInt()) sheetHaptics.notch()
                            viewModel.change { it.copy(speed = v) }
                        },
                        valueRange = 0.6f..4f,
                        colors = SliderDefaults.colors(thumbColor = LinkHandsTone, activeTrackColor = LinkHandsTone, inactiveTrackColor = Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.weight(1f),
                    )
                }
                ToggleRow("Draw the pointer here", "It moves the moment your finger does, ahead of the picture", config.localPointer, LinkHandsTone) {
                    viewModel.change { it.copy(localPointer = !it.localPointer) }
                    // the laptop leaves its own pointer out of the picture, or puts it back, from the next connection
                    session.reconnect()
                }
                if (config.localPointer) {
                    Text("Pointer size", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    Segmented(
                        listOf(0.8f to "Small", 1f to "Usual", 1.3f to "Large", 1.7f to "Huge"),
                        picked = config.pointerSize, tone = LinkHandsTone,
                    ) { size -> viewModel.change { it.copy(pointerSize = size) } }
                    Spacer(Modifier.height(6.dp))
                }
                ToggleRow("Scroll like a phone", "The page moves with your fingers", config.naturalScroll, LinkHandsTone) {
                    viewModel.change { it.copy(naturalScroll = !it.naturalScroll) }
                }
                ToggleRow("Scroll strip", "Slide one finger along the pad's edge to scroll", config.scrollStrip, LinkHandsTone) {
                    viewModel.change { it.copy(scrollStrip = !it.scrollStrip) }
                }
                AnimatedVisibility(
                    visible = config.scrollStrip,
                    enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                    exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
                ) {
                    ScrollStripRows(config, onNotch = sheetHaptics::notch) { change -> viewModel.change(change) }
                }
                ToggleRow("Phone keyboard as usual", "Suggestions, autocorrect and swiping; plain keys in a terminal", config.nativeKeyboard, LinkHandsTone) {
                    viewModel.change { it.copy(nativeKeyboard = !it.nativeKeyboard) }
                }
                ToggleRow("Keyboard rises by itself", "When a click lands where you type", config.autoKeyboard, LinkHandsTone) {
                    viewModel.change { it.copy(autoKeyboard = !it.autoKeyboard) }
                }
                ToggleRow("Start zoomed in", "The picture fills its space and follows the pointer", config.fillPreview, LinkHandsTone) {
                    viewModel.zoom = null
                    viewModel.change { it.copy(fillPreview = !it.fillPreview) }
                }
            }

            // the clipboard in its own colour, as its tile in the dock
            HealthSection(tone = LinkClipboardTone) {
                RoomTitle(AppIcons.ClipboardSwap, "Clipboard", LinkClipboardTone)
                if (LinkSession.CLIP_SYNC in state.caps) {
                    ToggleRow("Keep the clipboards in sync", "Copy on either one, paste on the other", config.clipSync, LinkClipboardTone) {
                        actions.clipSync(!config.clipSync)
                    }
                }
                ActionRow(AppIcons.ClipboardPaste, "Copy what's copied on $laptopName", LinkClipboardTone) { onDismiss(); actions.fetchClipboard() }
                ActionRow(AppIcons.ClipboardCopy, "Send what's copied here to $laptopName", LinkClipboardTone) { onDismiss(); actions.sendClipboard() }
            }

            HealthSection(tone = TextSecondary) {
                ActionRow(AppIcons.Lock, "Lock $laptopName", TextSecondary) { onDismiss(); session.lock() }
                ActionRow(AppIcons.Settings, "Weave's settings", TextSecondary) { onDismiss(); onSettings() }
            }
        }
    }
}

/** A round media key in the sound room. */
@Composable
private fun MediaKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.pressable(label, onClick).size(46.dp).clip(CircleShape).background(LinkSoundTone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(19.dp))
    }
}

/**
 * The laptop's monitors laid out as they stand on the desk, scaled to the room: the one shown
 * is lit, a tap shows another. Without a layout (an older tandem) they are a row.
 */
@Composable
private fun MonitorMap(state: LinkState, onPick: (String) -> Unit) {
    val mons = state.monitors
    val laid = mons.all { it.layoutWidth > 0 && it.layoutHeight > 0 }
    if (!laid) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mons.forEach { m -> MonitorTile(m, m.name == state.monitor && !state.followPointer, (state.shown ?: state.monitor) == m.name, Modifier.weight(1f).height(56.dp)) { onPick(m.name) } }
        }
        return
    }
    val minX = mons.minOf { it.x }
    val minY = mons.minOf { it.y }
    val spanW = mons.maxOf { it.x + it.layoutWidth } - minX
    val spanH = mons.maxOf { it.y + it.layoutHeight } - minY
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val scale = minOf(maxWidth.value / spanW, MAP_HEIGHT.value / spanH)
        val mapW = (spanW * scale).dp
        val mapH = (spanH * scale).dp
        Box(Modifier.align(Alignment.Center).size(mapW, mapH)) {
            mons.forEach { m ->
                MonitorTile(
                    // lit with the picture: the monitor a switch is going to lights once it lands
                    m, picked = !state.followPointer && m.name == state.monitor, shown = m.name == (state.shown ?: state.monitor),
                    modifier = Modifier
                        .offset(((m.x - minX) * scale).dp + gap / 2, ((m.y - minY) * scale).dp + gap / 2)
                        .size((m.layoutWidth * scale).dp - gap, (m.layoutHeight * scale).dp - gap),
                ) { onPick(m.name) }
            }
        }
    }
}

private val MAP_HEIGHT: Dp = 96.dp

@Composable
private fun MonitorTile(m: LinkMonitor, picked: Boolean, shown: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (shown) LinkScreenTone.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f), AppMotion.colorTween(), label = "monitor")
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(fill).pressable("Show ${shortLabel(m.label, m.name)}", onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shortLabel(m.label, m.name), color = if (shown) TextPrimary else TextSecondary,
            fontSize = 11.sp, fontWeight = if (shown) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 13.sp,
        )
        if (picked) {
            Icon(AppIcons.Lock, null, tint = TextPrimary, modifier = Modifier.align(Alignment.TopEnd).size(10.dp))
        }
    }
}

/**
 * The scroll strip's settings, under its switch: which edge, how fast, and how it feels. The
 * strip on the pad turns into its new place as they change, so the room needs no picture of it.
 */
@Composable
private fun ScrollStripRows(config: LinkConfig, onNotch: () -> Unit, change: ((LinkConfig) -> LinkConfig) -> Unit) {
    Column {
        Text("Which edge", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        Segmented(
            listOf(true to "Left edge", false to "Right edge"),
            picked = config.scrollStripLeft, tone = LinkHandsTone,
        ) { left -> change { it.copy(scrollStripLeft = left) } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            Text("Strip speed", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(14.dp))
            Slider(
                value = config.scrollStripSpeed,
                onValueChange = { v ->
                    if ((v * 4f).toInt() != (config.scrollStripSpeed * 4f).toInt()) onNotch()
                    change { it.copy(scrollStripSpeed = v) }
                },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = LinkHandsTone, activeTrackColor = LinkHandsTone, inactiveTrackColor = Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.weight(1f),
            )
        }
        ToggleRow("Roll on after a flick", "Off: the page stops when your finger lifts", config.scrollStripRoll, LinkHandsTone) {
            change { it.copy(scrollStripRoll = !it.scrollStripRoll) }
        }
        ToggleRow("Feel it under your finger", "A snap as it comes free of the cloth, then a tick at every notch", config.scrollStripTicks, LinkHandsTone) {
            change { it.copy(scrollStripTicks = !it.scrollStripTicks) }
        }
    }
}
