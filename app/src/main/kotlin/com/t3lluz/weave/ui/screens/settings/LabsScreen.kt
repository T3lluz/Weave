package com.t3lluz.weave.ui.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.t3lluz.weave.ui.components.TileRow
import com.t3lluz.weave.ui.components.SubScreenHeader
import com.t3lluz.weave.ui.components.subScreenBottomPadding
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.screens.link.ToggleRow
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkSoundTone
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.viewmodel.RoamViewModel

/**
 * Settings → Labs: things that work but are still being tried out, each with its switch and
 * nothing more. What a lab can be set to lives where its subject does: the earbuds' settings
 * are in the remote's menu, in the sound room.
 */
@Composable
fun LabsScreen(onNavigateBack: () -> Unit, viewModel: RoamViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    // Android's "Nearby devices" and "Notifications": asked for when the lab is turned on, and
    // it is turned on whatever the answer (the computer can still hand the earbuds over itself)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.setOn(true) }
    val on = settings.on
    val computer = state.computer.ifBlank { "the computer" }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TileRow.Side)
            .subScreenBottomPadding(),
    ) {
        SubScreenHeader(title = "Labs", onNavigateBack = onNavigateBack)
        Spacer(Modifier.height(10.dp))

        HealthSection(tone = LinkSoundTone) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Headphones, null, tint = LinkSoundTone, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Sound follows your earbuds", style = HealthDisplay, fontSize = 19.sp, color = LinkSoundTone, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(LinkSoundTone.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text("Beta", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LinkSoundTone)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Walk away from $computer with your earbuds in, and this phone takes them over and plays $computer's " +
                    "sound through them, while $computer itself goes quiet. Come back, and $computer takes them back. " +
                    "It works from any network your phone reaches $computer on.",
                fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow("Turn it on", if (on) "Its settings are in the remote's menu, under Sound" else null, on, LinkSoundTone) {
                if (on) {
                    viewModel.setOn(false)
                } else {
                    val wanted = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                            add(Manifest.permission.BLUETOOTH_ADVERTISE)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (wanted.isEmpty()) viewModel.setOn(true) else ask.launch(wanted.toTypedArray())
                }
            }
            state.trouble?.let { Text(it, fontSize = 13.sp, color = Warning, lineHeight = 18.sp) }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Android lets nothing but its own shell connect earbuds for you, so $computer makes this phone take them over adb " +
                "(Developer options → Wireless debugging). Without it, connect them in Bluetooth settings and the sound still follows. " +
                "The computer's side needs Linux.",
            fontSize = 12.sp, color = TextTertiary, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}
