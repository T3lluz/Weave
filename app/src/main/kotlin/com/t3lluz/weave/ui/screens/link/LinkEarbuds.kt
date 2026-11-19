package com.t3lluz.weave.ui.screens.link

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.t3lluz.weave.roam.RoamPlayer
import com.t3lluz.weave.roam.RoamStatus
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.LinkSoundTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.viewmodel.RoamViewModel
import kotlin.math.roundToInt

/** How early the phone takes the earbuds, as the computer names the three and as a person would. */
private val SENS = listOf("early" to "Early", "balanced" to "Balanced", "late" to "Late")
private val SENS_MEANS = mapOf(
    "early" to "At the first sign of a weak link.",
    "balanced" to "Before the sound breaks up.",
    "late" to "Only when the link is nearly gone.",
)

/**
 * The earbuds' rows of the sound room, there while the lab is on (Settings → Labs): where the
 * earbuds are, a button that moves them to this phone or back, whether they change hands by
 * themselves, and how early.
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
    val status = state.status
    val computer = state.computer.ifBlank { laptopName }
    val auto = settings.mode != "manual"

    Column {
        Spacer(Modifier.height(14.dp))
        Text(status?.device?.name ?: "Earbuds", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(state.trouble ?: whereTheyAre(status, player, computer, state.online), color = if (state.trouble != null) Warning else TextPrimary, fontSize = 15.sp, lineHeight = 20.sp)
        strength(status)?.let { Text(it, color = TextTertiary, fontSize = 12.sp, lineHeight = 16.sp) }
        if (state.trouble != null) return@Column

        val address = status?.device?.address
        when {
            player.playing || status?.state == "bridged" -> ActionRow(AppIcons.Laptop, "Move them back to $computer", LinkSoundTone, viewModel::backToComputer)
            status?.state == "armed" -> ActionRow(AppIcons.Headphones, "Move them to this phone", LinkSoundTone, viewModel::moveHere)
            status?.state == "idle" && address != null -> ActionRow(AppIcons.Play, "Play $computer's sound through them here", LinkSoundTone) { viewModel.listenHere(address) }
        }
        (state.said ?: status?.takeIf { !it.auto && it.state == "armed" }?.let {
            "$computer can't make this phone take them right now" + (if (it.autoWhy.isNotBlank()) ": ${it.autoWhy}." else ".") +
                " Connect them in this phone's Bluetooth settings and the sound still follows."
        })?.let { Text(it, color = Warning, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp)) }

        ToggleRow(
            "Move by themselves",
            if (auto) "To this phone when they get out of $computer's reach, and back when you are near" else "Off: only when you move them, here or from the Earbuds tile",
            auto, LinkSoundTone,
        ) { viewModel.setMode(if (auto) "manual" else "auto") }
        AnimatedVisibility(visible = auto) {
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

/** Where the earbuds are, in a sentence. */
internal fun whereTheyAre(status: RoamStatus?, player: RoamPlayer, computer: String, online: Boolean): String = when {
    player.playing -> "On this phone, playing $computer's sound."
    !online -> if (player.running) player.note else "Asking $computer…"
    status == null -> "Asking $computer…"
    else -> when (status.state) {
        "armed" -> "On $computer."
        "handing" -> "On their way to this phone."
        "waiting" -> "They left $computer. Seeing whether a phone has them."
        "bridged" -> "On a phone, playing $computer's sound."
        "returning" -> "On their way back to $computer."
        "idle" -> "$computer isn't playing through earbuds right now."
        else -> "Off on $computer."
    }
}

/** The link's strength while the earbuds are on the computer, and where the phone takes over. */
internal fun strength(status: RoamStatus?): String? {
    val signal = status?.signal ?: return null
    if (status.state != "armed") return null
    val unit = if (status.dbm) " dBm" else ""
    val word = when {
        status.dbm && signal > status.limit + 12 || !status.dbm && signal > status.limit / 2 -> "strong"
        signal > status.limit -> "getting weak"
        else -> "weak"
    }
    val auto = if (status.mode == "manual") "" else " The phone takes over at ${status.limit.roundToInt()}$unit."
    return "Link $word (${signal.roundToInt()}$unit).$auto"
}
