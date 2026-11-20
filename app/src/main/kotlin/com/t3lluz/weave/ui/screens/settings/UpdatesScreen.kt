package com.t3lluz.weave.ui.screens.settings

import com.t3lluz.weave.ui.util.rememberHaptics
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.t3lluz.weave.data.update.AppReleaseNotes
import com.t3lluz.weave.data.update.AppUpdateUiState
import com.t3lluz.weave.data.update.info
import com.t3lluz.weave.data.update.prettyVersion
import com.t3lluz.weave.data.update.updateAvailable
import com.t3lluz.weave.ui.components.TileRow
import com.t3lluz.weave.ui.components.LoadingSpinner
import com.t3lluz.weave.ui.components.NotesText
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.components.SubScreenHeader
import com.t3lluz.weave.ui.components.UpdateProgressBar
import com.t3lluz.weave.ui.components.subScreenBottomPadding
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.screens.link.RoomTitle
import com.t3lluz.weave.ui.screens.link.ToggleRow
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.UpdateTone
import com.t3lluz.weave.ui.viewmodel.AppUpdateViewModel

/**
 * Settings → Updates, as Weave's rooms: the version on this phone and whether a newer one is
 * out (it opens the update sheet), the notification for new builds, then each release's notes,
 * newest first.
 */
@Composable
fun UpdatesScreen(onNavigateBack: () -> Unit, viewModel: AppUpdateViewModel) {
    val state by viewModel.state.collectAsState()
    val notes by viewModel.releaseNotes.collectAsState()
    val notesLoading by viewModel.releaseNotesLoading.collectAsState()
    val notify by viewModel.notifyEnabled.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadReleaseNotes() }
    // Android 13 and up asks before an app may post; the switch asks the first time it is turned on
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setNotifyEnabled(granted)
    }
    fun setNotify(on: Boolean) {
        val needsAsk = on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setNotifyEnabled(on)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TileRow.Side)
            .subScreenBottomPadding(),
    ) {
        SubScreenHeader(title = "Updates", subtitle = "Weave ${viewModel.currentVersionName} on this phone", onNavigateBack = onNavigateBack)
        Spacer(Modifier.height(10.dp))

        HealthSection(tone = UpdateTone) {
            RoomTitle(AppIcons.Download, "This phone", UpdateTone)
            StatusLine(state)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.updateAvailable) {
                    PillButton(icon = AppIcons.Download, label = "See the update", accent = UpdateTone, emphasized = true, onClick = viewModel::openDialog)
                } else if (state !is AppUpdateUiState.Checking) {
                    PillButton(icon = AppIcons.Refresh, label = "Look now", accent = UpdateTone, onClick = viewModel::checkFromSettings)
                }
            }
            Spacer(Modifier.height(6.dp))
            // on only when Android lets Weave post too, so the switch never claims what can't happen
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val on = notify && allowed
            ToggleRow("Tell me when one is out", "Looks every few hours, even with Weave closed", on, UpdateTone) { setNotify(!on) }
        }

        Text(
            "Release notes", style = HealthDisplay, fontSize = 22.sp, color = TextPrimary,
            modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
        )
        when {
            notesLoading && notes.isEmpty() -> Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                LoadingSpinner(color = UpdateTone, size = 18.dp)
            }
            notes.isEmpty() -> Text("No releases to show yet.", color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
            else -> {
                var all by remember { mutableStateOf(false) }
                val haptics = rememberHaptics()
                (if (all) notes else notes.take(3)).forEach { ReleaseRoom(it) }
                if (notes.size > 3) {
                    Row(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(CircleShape)
                            .clickable { if (all) haptics.toggleOff() else haptics.toggleOn(); all = !all }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (all) "Fewer" else "${notes.size - 3} older releases", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Icon(if (all) AppIcons.ChevronUp else AppIcons.ChevronDown, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** Where the update stands, in one line under the room's name. */
@Composable
private fun StatusLine(state: AppUpdateUiState) {
    val info = state.info
    when (state) {
        is AppUpdateUiState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingSpinner(color = UpdateTone, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text("Looking for a new version…", color = TextSecondary, fontSize = 14.sp)
        }
        is AppUpdateUiState.Downloading -> Column {
            Text("Downloading ${prettyVersion(state.info.versionName)}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            UpdateProgressBar(state.progress)
        }
        is AppUpdateUiState.Installing -> Text("Installing ${prettyVersion(state.info.versionName)}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        is AppUpdateUiState.Error -> Text(state.message, color = Error, fontSize = 14.sp, lineHeight = 19.sp)
        AppUpdateUiState.UpToDate -> Text("Up to date", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        else -> if (info != null) {
            Text("Weave ${prettyVersion(info.versionName)} is ready", color = UpdateTone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Not checked yet", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

/** One release: its version in the display face, when it came out, and its notes. A newer one than this phone's is lit. */
@Composable
private fun ReleaseRoom(release: AppReleaseNotes) {
    HealthSection(tone = if (release.isNewerThanInstalled) UpdateTone else TextSecondary) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                prettyVersion(release.versionName), style = HealthDisplay, fontSize = 19.sp,
                color = if (release.isNewerThanInstalled) UpdateTone else TextPrimary, modifier = Modifier.weight(1f),
            )
            release.publishedAt?.take(10)?.let { Text(it, color = TextTertiary, fontSize = 12.sp) }
        }
        if (release.isNewerThanInstalled) Text("Newer than this phone's", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        NotesText(release.releaseNotes.ifBlank { "Fixes and polish." })
    }
}
