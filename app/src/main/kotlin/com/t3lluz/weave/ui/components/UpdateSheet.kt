package com.t3lluz.weave.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.update.AppUpdateInfo
import com.t3lluz.weave.data.update.AppUpdateUiState
import com.t3lluz.weave.data.update.WhatsNewInfo
import com.t3lluz.weave.data.update.inProgress
import com.t3lluz.weave.data.update.info
import com.t3lluz.weave.data.update.prettyVersion
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.roomColor
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.OnAccent
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.UpdateTone
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.util.openUrl
import com.t3lluz.weave.ui.util.rememberHaptics
import java.util.Locale

/**
 * The update, as one sheet that follows it from offer to reinstall (DailyDash's, after
 * Essentials' updater), drawn in Weave's rooms: the new version in the display face, its notes
 * in a room, and under them the one action that makes sense right now. Update turns into the
 * download's bar the moment it is tapped, the bar into "installing", and that into "tap Install
 * in Android's prompt" when Android asks. Closing the sheet mid-download leaves it running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateSheet(
    state: AppUpdateUiState,
    currentVersionName: String,
    onDismiss: () -> Unit,
    onUpdate: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryInstall: () -> Unit,
    onReopenPrompt: () -> Unit,
) {
    val info = state.info ?: return
    val context = LocalContext.current
    val busy = state.inProgress
    // the download landing and Android's prompt coming up are felt; so is an update that fell over
    val stepHaptics = rememberHaptics()
    var stepWas by remember { mutableStateOf(state::class) }
    LaunchedEffect(state::class) {
        val was = stepWas
        stepWas = state::class
        when {
            state is AppUpdateUiState.Error -> stepHaptics.reject()
            state is AppUpdateUiState.Installing && was == AppUpdateUiState.Downloading::class -> stepHaptics.confirm()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp)
                .navigationBarsPadding(),
        ) {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text(title(state), color = TextSecondary, fontSize = 14.sp)
                Text("Weave ${prettyVersion(info.versionName)}", style = HealthDisplay, fontSize = 32.sp, color = TextPrimary)
                Text(
                    listOfNotNull(formatApkSize(info.apkBytes), "you have $currentVersionName").joinToString(" · "),
                    color = TextTertiary, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            NotesRoom(info.releaseNotes, Modifier.heightIn(max = 280.dp))
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(AppMotion.fadeTween()) togetherWith fadeOut(AppMotion.fadeTween()) },
                label = "updateStep",
            ) { step ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (step) {
                        is AppUpdateUiState.Available -> {
                            BigPill("Update now", AppIcons.Download, primary = true) { onUpdate(step.info) }
                            BigPill("Later", null) { onDismiss() }
                        }
                        is AppUpdateUiState.NeedsPermission -> {
                            StepNote("Android needs to let Weave install updates first. Allow it on the next screen and come back; the update starts by itself.")
                            BigPill("Allow and update", AppIcons.Download, primary = true) { onUpdate(step.info) }
                            BigPill("Later", null) { onDismiss() }
                        }
                        is AppUpdateUiState.Downloading -> {
                            ProgressLine(
                                progress = step.progress,
                                label = buildString {
                                    append("Downloading")
                                    val total = step.totalBytes
                                    if (total != null && total > 0) {
                                        append(" · ${formatMb(step.downloadedBytes)} of ${formatMb(total)}")
                                    } else if (step.downloadedBytes > 0) {
                                        append(" · ${formatMb(step.downloadedBytes)}")
                                    }
                                },
                                trailing = step.progress?.let { "${(it * 100).toInt()}%" },
                            )
                            BigPill("Cancel", AppIcons.Close) { onCancelDownload() }
                        }
                        is AppUpdateUiState.Installing -> {
                            if (step.awaitingConfirmation) {
                                ProgressLine(progress = null, label = "Waiting for you in Android's prompt")
                                StepNote("Tap Install there. Weave closes, updates and opens again with what's new.")
                                BigPill("Show Android's prompt", null) { onReopenPrompt() }
                            } else {
                                ProgressLine(progress = null, label = "Installing")
                                StepNote("Weave closes and reopens by itself. If Android asks first, tap Install.")
                            }
                        }
                        is AppUpdateUiState.ReadyToInstall -> {
                            step.note?.let { StepNote(it, color = Warning) }
                            BigPill("Install update", AppIcons.Download, primary = true) { onRetryInstall() }
                            BigPill("Later", null) { onDismiss() }
                        }
                        is AppUpdateUiState.Error -> {
                            StepNote(step.message, color = Error)
                            step.info?.let { failed -> BigPill("Try again", AppIcons.Refresh, primary = true) { onUpdate(failed) } }
                            BigPill("Close", null) { onDismiss() }
                        }
                        else -> Unit
                    }
                }
            }
            if (!busy && info.htmlUrl.isNotBlank()) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 6.dp)
                        .clip(CircleShape)
                        .clickable { context.openUrl(info.htmlUrl) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("This release on GitHub", color = TextTertiary, fontSize = 12.5.sp)
                    Spacer(Modifier.width(5.dp))
                    Icon(AppIcons.ExternalLink, null, tint = TextTertiary, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** Once, after Weave opens on a build it just installed: what that build brought. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(info: WhatsNewInfo, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text("Updated", color = TextSecondary, fontSize = 14.sp)
                Text("What's new in ${prettyVersion(info.versionName)}", style = HealthDisplay, fontSize = 30.sp, lineHeight = 34.sp, color = TextPrimary)
            }
            Spacer(Modifier.height(14.dp))
            NotesRoom(info.releaseNotes, Modifier.heightIn(max = 360.dp))
            Spacer(Modifier.height(14.dp))
            BigPill("Got it", AppIcons.Check, primary = true, onClick = onDismiss)
        }
    }
}

/** Release notes in a room of the update colour. */
@Composable
fun NotesRoom(notes: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(roomColor(UpdateTone))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        NotesText(notes.ifBlank { "Fixes and polish." })
    }
}

/**
 * Release notes as Weave shows them: a bullet per change, headings in the update colour.
 * Takes the markdown GitHub gives back and drops what a phone doesn't need: link targets,
 * bold marks, comments.
 */
@Composable
fun NotesText(markdown: String, modifier: Modifier = Modifier) {
    // the sheet and the Updates page link the release themselves
    val lines = markdown.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("<!--") && !plain(it).trim().equals("View release on GitHub", ignoreCase = true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { raw ->
            val text = plain(raw)
            when {
                raw.startsWith("#") -> Text(
                    plain(raw.trimStart('#').trim()), color = UpdateTone, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                raw.startsWith("- ") || raw.startsWith("* ") -> Row {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(UpdateTone))
                    Spacer(Modifier.width(10.dp))
                    Text(plain(raw.drop(2)), color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
                else -> Text(text, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

private fun plain(s: String): String = s
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace("**", "")
    .replace("__", "")
    .replace(Regex("""`([^`]*)`"""), "$1")

/** A full-width pill: the step's main action filled in the update colour, the others quiet. */
@Composable
private fun BigPill(label: String, icon: ImageVector?, primary: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(if (primary) UpdateTone else roomColor(UpdateTone))
            .clickable(role = Role.Button) { haptics.tick(); onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) OnAccent else TextPrimary
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** An update's download or install: filling when the size is known, sweeping when not. */
@Composable
fun UpdateProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val bar = modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
    val track = Color.White.copy(alpha = 0.08f)
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = bar, color = UpdateTone, trackColor = track, drawStopIndicator = {}, gapSize = 0.dp)
    } else {
        LinearProgressIndicator(modifier = bar, color = UpdateTone, trackColor = track, gapSize = 0.dp)
    }
}

@Composable
private fun ProgressLine(progress: Float?, label: String, trailing: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        UpdateProgressBar(progress)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
    }
}

@Composable
private fun StepNote(text: String, color: Color = TextSecondary) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
}

private fun title(state: AppUpdateUiState): String = when (state) {
    is AppUpdateUiState.Downloading -> "Downloading"
    is AppUpdateUiState.Installing -> if (state.awaitingConfirmation) "Confirm the update" else "Installing"
    is AppUpdateUiState.ReadyToInstall -> "Ready to install"
    is AppUpdateUiState.Error -> "The update didn't finish"
    else -> "An update is ready"
}

private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

internal fun formatApkSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
