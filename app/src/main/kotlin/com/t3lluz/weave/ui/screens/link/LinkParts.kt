package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.link.LinkState
import com.t3lluz.weave.data.link.LinkStatus
import com.t3lluz.weave.data.link.switchDirection
import com.t3lluz.weave.ui.components.LivePulseDot
import com.t3lluz.weave.ui.components.IconTile
import com.t3lluz.weave.ui.components.TileRow
import com.t3lluz.weave.ui.components.ToneTile
import com.t3lluz.weave.ui.components.chromeGlass
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.util.rememberHaptics
import dev.chrisbanes.haze.HazeState

/** A monitor's name as a person says it: "LC27G5xT · HDMI" is the LC27G5xT, the panel is "Laptop screen". */
internal fun shortLabel(label: String?, name: String?): String =
    label?.substringBefore(" · ")?.ifBlank { null } ?: name.orEmpty()

/**
 * Gives a little under the finger and springs back; the scale is read in the layer, never in
 * composition. Turned off ([enabled] false), it neither gives nor ticks, and reads as off. A
 * switch hands in the state it is in ([on]) and says under the finger which way it went. A
 * button gives to [depth]; a row as wide as its room gives much less ([ROW_DEPTH]).
 */
@Composable
internal fun Modifier.pressable(label: String, onClick: () -> Unit): Modifier = pressable(label, enabled = true, onClick = onClick)

@Composable
internal fun Modifier.pressable(
    label: String, enabled: Boolean, on: Boolean? = null, depth: Float = AppMotion.Link.PRESSED_SCALE, onClick: () -> Unit,
): Modifier {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) depth else 1f, AppMotion.Link.press(), label = "press")
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = if (on != null) Role.Switch else Role.Button, onClickLabel = label) {
            when (on) {
                null -> haptics.tick()
                true -> haptics.toggleOff()
                false -> haptics.toggleOn()
            }
            onClick()
        }
}

/**
 * The top row's wide tile: which laptop, and under its name which of its monitors and how far
 * away it is, as a laptop's room in the list says it. The monitor's name slides in from the
 * side the picture came from, so the tile moves with the swipe. Opens the sheet.
 */
@Composable
internal fun StatusTile(
    state: LinkState,
    laptopName: String,
    modifier: Modifier = Modifier,
    /** The monitor to name: the one the picture shows, or with the screen hidden, the one the pointer is on. */
    monitor: com.t3lluz.weave.data.link.LinkMonitor? = state.shownMonitor,
    onClick: () -> Unit,
) {
    val back = state.status == LinkStatus.Reconnecting
    ToneTile("Laptop and screen", LinkScreenTone, TileRow.Middle, modifier, pressedScale = 0.96f, onClick = onClick) { _, _ ->
        Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 10.dp)) {
            Text(
                laptopName, style = HealthDisplay, color = TextPrimary, fontSize = 16.sp, lineHeight = 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LivePulseDot(color = if (back) Warning else Success)
                Spacer(Modifier.width(6.dp))
                AnimatedContent(
                    targetState = monitor,
                    transitionSpec = {
                        val (dx, _) = switchDirection(initialState, targetState)
                        if (dx == 0) {
                            fadeIn(AppMotion.fadeTween()) togetherWith fadeOut(AppMotion.fadeTween())
                        } else {
                            (slideInHorizontally(AppMotion.Link.swipe()) { it * dx } + fadeIn(AppMotion.fadeTween())) togetherWith
                                (slideOutHorizontally(AppMotion.Link.swipe()) { -it * dx } + fadeOut(AppMotion.fadeTween()))
                        } using SizeTransform(clip = true)
                    },
                    label = "monitor",
                    // the name slides inside its own space, and gives way before the ping does
                    modifier = Modifier.weight(1f, fill = false).clipToBounds(),
                ) { m ->
                    val name = if (back) "Reconnecting" else shortLabel(m?.label, state.monitor)
                    Text(name, color = TextSecondary, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!back) {
                    state.pingMs?.let { Text(" · $it ms", color = TextTertiary, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * A tile of the dock: its icon in its own colour; the icon says it all, its name is for screen
 * readers. One that is a [switch] is lit while [on]. [shape] lets the end tiles take the
 * trackpad's corners.
 */
@Composable
internal fun DockTile(
    icon: ImageVector, label: String, tone: Color, shape: Shape, modifier: Modifier = Modifier,
    on: Boolean = false, switch: Boolean = false, onClick: () -> Unit,
) {
    IconTile(icon, label, tone, shape, modifier, on, switch, TileRow.DockHeight, onClick)
}

/**
 * A control of the full-screen panel. Each arrives a beat after the one before it. It is as
 * tall as the handle and takes the panel's inner corner, so lit it is a pill that runs round
 * the panel's own curve. Its words are white: the glass is clear enough that grey would sink
 * into a bright picture behind it.
 */
@Composable
internal fun PanelButton(index: Int, icon: ImageVector, label: String, tone: Color = TextPrimary, on: Boolean = false, switch: Boolean = false, onClick: () -> Unit) {
    val arrived = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrived.animateTo(1f, AppMotion.Link.arrive(index)) }
    val fill by animateColorAsState(if (on) tone.copy(alpha = 0.26f) else Color.Transparent, AppMotion.colorTween(), label = "panel")
    val ink by animateColorAsState(if (on) tone else TextPrimary, AppMotion.colorTween(), label = "panelInk")
    Column(
        Modifier
            .graphicsLayer {
                alpha = arrived.value
                val s = 0.82f + 0.18f * arrived.value
                scaleX = s
                scaleY = s
            }
            .pressable(label, enabled = true, on = on.takeIf { switch }, onClick = onClick)
            .size(PANEL_BUTTON, PANEL_HANDLE)
            .clip(PanelInnerShape)
            .background(fill),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = ink, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun PanelDivider() {
    Box(Modifier.padding(horizontal = (PANEL_DIVIDER - 1.dp) / 2).size(width = 1.dp, height = 26.dp).background(Color.White.copy(alpha = 0.14f)))
}

/** How wide a control and a line between them are: the panel's width is known from them before it is unfolded. */
internal val PANEL_BUTTON = 62.dp
internal val PANEL_DIVIDER = 9.dp

/** A line that says what just happened, dropping in under the status bar. */
@Composable
internal fun LinkToast(message: String?, hazeState: HazeState?, modifier: Modifier = Modifier) {
    // the words stay while it leaves
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(AppMotion.Link.swipe()) { -it * 2 } + fadeIn(AppMotion.fadeTween()),
        exit = slideOutVertically(AppMotion.slideTween()) { -it * 2 } + fadeOut(AppMotion.fadeTween()),
        modifier = modifier.statusBarsPadding().padding(top = 58.dp, start = 24.dp, end = 24.dp),
    ) {
        Text(
            last,
            color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier
                .chromeGlass(hazeState, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * The keys a phone keyboard lacks, over it. Ctrl, alt, super and shift wait for the next key.
 * [side]: in from the screen's edge as the trackpad and the dock are, upright; on the glass in
 * full screen, as far in as from its top, so a key's corner follows the glass's.
 */
@Composable
internal fun KeyRow(mods: Set<String>, tone: Color, onMods: (Set<String>) -> Unit, onKey: (String) -> Unit, side: Dp = TileRow.Side) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = side, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip("esc", tone) { onKey("esc") }
        KeyChip("tab", tone) { onKey("tab") }
        listOf("ctrl", "alt", "super", "shift").forEach { mod ->
            KeyChip(mod, tone, on = mod in mods, switch = true) { onMods(if (mod in mods) mods - mod else mods + mod) }
        }
        KeyChip("←", tone) { onKey("left") }
        KeyChip("↓", tone) { onKey("down") }
        KeyChip("↑", tone) { onKey("up") }
        KeyChip("→", tone) { onKey("right") }
        KeyChip("del", tone) { onKey("delete") }
        KeyChip("home", tone) { onKey("home") }
        KeyChip("end", tone) { onKey("end") }
        KeyChip("pgup", tone) { onKey("pageup") }
        KeyChip("pgdn", tone) { onKey("pagedown") }
        (1..12).forEach { n -> KeyChip("F$n", tone) { onKey("f$n") } }
    }
}

@Composable
private fun KeyChip(label: String, tone: Color, on: Boolean = false, switch: Boolean = false, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) tone.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f), AppMotion.colorTween(), label = "key")
    Box(
        Modifier
            .pressable(label, enabled = true, on = on.takeIf { switch }, onClick = onClick)
            .height(38.dp)
            .widthIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) tone else TextPrimary,
            fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
        )
    }
}

/** How far a row as wide as its room gives under the finger: a button's give would jump. */
internal const val ROW_DEPTH = 0.97f

/** A row of a room that is a switch: what it does, one short line on what that means, and the switch in the room's colour. */
@Composable
internal fun ToggleRow(title: String, sub: String?, on: Boolean, tone: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(title, enabled = true, on = on, depth = ROW_DEPTH, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
            if (sub != null) Text(sub, color = TextSecondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = tone, checkedThumbColor = Color.White, checkedBorderColor = tone,
                uncheckedTrackColor = Color.White.copy(alpha = 0.06f), uncheckedBorderColor = Color.White.copy(alpha = 0.16f), uncheckedThumbColor = TextTertiary,
            ),
        )
    }
}

/** A row of a room that does something once: a tinted icon and what it does. */
@Composable
internal fun ActionRow(icon: ImageVector, title: String, tone: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(title, enabled = true, depth = ROW_DEPTH, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/**
 * One of a few, as a track with a highlight that slides to the picked one. The highlight's
 * place is read while laying out, so the slide never recomposes.
 */
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, picked: T, tone: Color, onPick: (T) -> Unit) {
    val haptics = rememberHaptics()
    val index = options.indexOfFirst { it.first == picked }.coerceAtLeast(0)
    val at = animateFloatAsState(index.toFloat(), AppMotion.Link.segment(), label = "segment")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp),
    ) {
        val cell = maxWidth / options.size
        Box(
            Modifier
                .offset { IntOffset((cell.toPx() * at.value).toInt(), 0) }
                .width(cell)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(tone.copy(alpha = 0.3f)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        // the highlight sliding over is the answer: no ripple on top of it
                        .clickable(interactionSource = null, indication = null, role = Role.RadioButton) {
                            if (i != index) haptics.tick()
                            onPick(value)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (i == index) TextPrimary else TextSecondary,
                        fontSize = 13.sp, fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A room's name, in the display face and its colour, with its icon. */
@Composable
internal fun RoomTitle(icon: ImageVector, title: String, tone: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = HealthDisplay, fontSize = 19.sp, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
