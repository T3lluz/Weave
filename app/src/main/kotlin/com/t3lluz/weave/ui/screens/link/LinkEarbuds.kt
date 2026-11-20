package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.t3lluz.weave.roam.RoamPlayer
import com.t3lluz.weave.roam.RoamStatus
import com.t3lluz.weave.ui.components.Hairline
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.LinkSoundTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.screens.health.pressSink
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.viewmodel.RoamViewModel
import kotlin.math.ceil

/** How early the phone takes the earbuds, as the computer names the three and as a person would. */
private val SENS = listOf("early" to "Early", "balanced" to "Balanced", "late" to "Late")
private val SENS_MEANS = mapOf(
    "early" to "At the first sign of a weak link.",
    "balanced" to "Before the sound breaks up.",
    "late" to "Only when the link is nearly gone.",
)

/** Where the earbuds play, as the switch shows it. */
internal enum class Buds(val side: Int?, val moving: Boolean = false) {
    COMPUTER(0), PHONE(1), TO_PHONE(1, moving = true), TO_COMPUTER(0, moving = true),

    /** The computer isn't playing through them, or is off: neither side is lit. */
    NEITHER(null),
}

internal fun budsFrom(status: RoamStatus?, player: RoamPlayer): Buds = when {
    player.playing -> Buds.PHONE
    status == null -> Buds.NEITHER
    else -> when (status.state) {
        "armed" -> Buds.COMPUTER
        "handing", "waiting" -> Buds.TO_PHONE
        "bridged" -> Buds.PHONE
        "returning" -> Buds.TO_COMPUTER
        else -> Buds.NEITHER
    }
}

/**
 * The earbuds in the sound room, there while the lab is on (Settings → Labs): their name and
 * how strong their link to the computer is, a switch between the two places they can play
 * (a tap on the other side moves them there), a line on how things stand, and whether they
 * change hands by themselves, and how early.
 */
@Composable
internal fun EarbudsRows(laptopName: String, viewModel: RoamViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    if (!settings.on) return
    val state by viewModel.state.collectAsState()
    val player by viewModel.player.collectAsState()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    val haptics = rememberHaptics()
    val status = state.status
    val computer = state.computer.ifBlank { laptopName }
    val auto = settings.mode != "manual"
    val buds = budsFrom(status, player)
    val address = status?.device?.address

    // they got where they were going while you watched: felt as the switch settles
    var wasMoving by remember { mutableStateOf(false) }
    LaunchedEffect(buds) {
        if (wasMoving && !buds.moving && buds != Buds.NEITHER) haptics.confirm()
        wasMoving = buds.moving
    }

    Column {
        // the earbuds are a thing of their own in the room: a line sets them apart
        Hairline(Modifier.padding(top = 10.dp, bottom = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Headphones, null, tint = LinkSoundTone, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                status?.device?.name ?: "Earbuds", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            linkLevel(status)?.let { level -> LinkBars(level) }
        }
        Spacer(Modifier.height(10.dp))
        val trouble = state.trouble
        if (trouble != null) {
            Text(trouble, color = Warning, fontSize = 13.sp, lineHeight = 18.sp)
            return@Column
        }

        // A tap on the side they aren't on moves them there; while they are on their way, it waits.
        val toComputer = buds == Buds.PHONE
        val toPhone = buds == Buds.COMPUTER || (status?.state == "idle" && address != null && !player.playing)
        EarbudsSwitch(
            computer, buds,
            onComputer = if (toComputer) viewModel::backToComputer else null,
            onPhone = when {
                !toPhone -> null
                buds == Buds.COMPUTER -> viewModel::moveHere
                else -> ({ viewModel.listenHere(address!!) })
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            whereTheyAre(status, player, computer, state.online),
            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
        )
        (state.said ?: status?.takeIf { !it.auto && it.state == "armed" }?.let {
            "$computer can't make this phone take them right now" + (if (it.autoWhy.isNotBlank()) ": ${it.autoWhy}." else ".") +
                " Connect them in this phone's Bluetooth settings and the sound still follows."
        })?.let { Text(it, color = Warning, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 4.dp)) }

        Spacer(Modifier.height(6.dp))
        ToggleRow(
            "Move by themselves",
            if (auto) "To this phone when they get out of $computer's reach, and back when you are near" else "Only when you move them, here or from the Earbuds tile",
            auto, LinkSoundTone,
        ) { viewModel.setMode(if (auto) "manual" else "auto") }
        AnimatedVisibility(
            visible = auto,
            enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
            exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
        ) {
            Column {
                Text("The phone takes over", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Segmented(SENS, picked = settings.sens, tone = LinkSoundTone) { viewModel.setSens(it) }
                Spacer(Modifier.height(6.dp))
                Text(
                    (SENS_MEANS[settings.sens] ?: SENS_MEANS.getValue("balanced")) + " If the sound ever breaks up first, $computer moves the point earlier by itself.",
                    color = TextTertiary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

/**
 * Where the earbuds play: the computer or this phone, as a track with a lit pill on the side
 * they are on, which slides across when they move and breathes until they get there. The side
 * they can go to is a button ([onComputer], [onPhone]); null, it is only a place.
 */
@Composable
private fun EarbudsSwitch(computer: String, buds: Buds, onComputer: (() -> Unit)?, onPhone: (() -> Unit)?) {
    val side = buds.side
    val at by animateFloatAsState((side ?: 0).toFloat(), AppMotion.Link.segment(), label = "buds")
    val shown by animateFloatAsState(if (side == null) 0f else 1f, AppMotion.fadeTween(), label = "budsLit")
    val breath = if (buds.moving) {
        rememberInfiniteTransition(label = "budsMoving").animateFloat(0.45f, 1f, AppMotion.pulseSpec(durationMs = 620), label = "budsMoving").value
    } else {
        1f
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(SWITCH_HEIGHT)
            .clip(SwitchShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(SWITCH_PAD),
    ) {
        val cell = maxWidth / 2
        Box(
            Modifier
                .offset { IntOffset((cell.toPx() * at).toInt(), 0) }
                .width(cell)
                .fillMaxHeight()
                .graphicsLayer { alpha = shown * breath }
                .clip(SwitchShape)
                .background(LinkSoundTone.copy(alpha = 0.32f)),
        )
        Row(Modifier.fillMaxWidth()) {
            BudsSide(AppIcons.Laptop, computer, lit = side == 0, onClick = onComputer, label = "Play them on $computer", modifier = Modifier.weight(1f))
            BudsSide(AppIcons.Smartphone, "This phone", lit = side == 1, onClick = onPhone, label = "Play them on this phone", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BudsSide(icon: ImageVector, name: String, lit: Boolean, onClick: (() -> Unit)?, label: String, modifier: Modifier) {
    val haptics = rememberHaptics()
    val ink by animateColorAsState(if (lit) TextPrimary else if (onClick != null) TextSecondary else TextTertiary, AppMotion.colorTween(), label = "budsInk")
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxHeight()
            .clip(SwitchShape)
            .then(
                if (onClick != null) {
                    Modifier
                        .pressSink(source, 0.96f)
                        .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label) {
                            haptics.toggleOn()
                            onClick()
                        }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = if (lit) "$name, where they play now" else name }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // lit, it is white on the colour, as a tile that is on
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = ink, fontSize = 14.sp, fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val SWITCH_HEIGHT = 52.dp
private val SWITCH_PAD = 4.dp
private val SwitchShape = RoundedCornerShape(50)

/** The link's strength as four bars and a word, as a phone shows its signal. */
@Composable
private fun LinkBars(level: Float) {
    val lit = ceil(level * 4f).toInt().coerceIn(1, 4)
    val word = when {
        level >= 0.5f -> "Strong"
        level > 0f -> "Fading"
        else -> "Weak"
    }
    val tone = if (level > 0f) LinkSoundTone else Warning
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$word link to the computer" }) {
        Text(word, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
            val bar = size.width / 4f
            val gap = bar * 0.28f
            for (i in 0 until 4) {
                val h = size.height * (0.4f + 0.2f * i)
                drawRoundRect(
                    if (i < lit) tone else Color.White.copy(alpha = 0.14f),
                    topLeft = Offset(i * bar, size.height - h), size = Size(bar - gap, h),
                    cornerRadius = CornerRadius((bar - gap) / 2f),
                )
            }
        }
    }
}

/** Where the earbuds are, in a sentence. */
internal fun whereTheyAre(status: RoamStatus?, player: RoamPlayer, computer: String, online: Boolean): String = when {
    player.playing -> "Playing $computer's sound here."
    !online -> if (player.running) player.note else "Asking $computer…"
    status == null -> "Asking $computer…"
    else -> when (status.state) {
        "armed" -> "Playing on $computer."
        "handing" -> "On their way to this phone…"
        "waiting" -> "They left $computer. Seeing whether this phone has them…"
        "bridged" -> "On a phone, playing $computer's sound."
        "returning" -> "On their way back to $computer…"
        "idle" -> if (status.device?.address != null) "$computer isn't playing through them. Tap This phone to hear it here." else "$computer isn't playing through earbuds right now."
        else -> "Off on $computer."
    }
}

/**
 * How strong the earbuds' link to the computer is while they are on it, 0 (at the point where
 * the phone takes over) to 1, or null when there is nothing to show. A controller that speaks
 * dBm counts 24 dB over the limit as full; one that says how far under its good range they are
 * counts 0 as full.
 */
internal fun linkLevel(status: RoamStatus?): Float? {
    val signal = status?.signal ?: return null
    if (status.state != "armed") return null
    val span = if (status.dbm) 24.0 else -status.limit
    if (span <= 0.0) return if (signal > status.limit) 1f else 0f
    return ((signal - status.limit) / span).coerceIn(0.0, 1.0).toFloat()
}
