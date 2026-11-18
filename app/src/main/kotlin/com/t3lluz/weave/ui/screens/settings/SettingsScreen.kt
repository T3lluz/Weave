package com.t3lluz.weave.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.t3lluz.weave.data.update.info
import com.t3lluz.weave.data.update.prettyVersion
import com.t3lluz.weave.data.update.updateAvailable
import com.t3lluz.weave.ui.theme.UpdateTone
import com.t3lluz.weave.ui.viewmodel.AppUpdateViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import com.t3lluz.weave.data.local.SettingsRepository
import com.t3lluz.weave.ui.components.AiAccessBadge
import com.t3lluz.weave.ui.components.AiProviderLogo
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.components.PillField
import com.t3lluz.weave.ui.components.SubScreenHeader
import com.t3lluz.weave.ui.components.subScreenBottomPadding
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.viewmodel.AiSetupViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val settings: SettingsRepository) : ViewModel()

/**
 * The app's few settings, as rooms: the dashboard whose bridge the laptops announce themselves
 * to, and the AI that makes shortcuts (Settings → AI).
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onAi: () -> Unit,
    onUpdates: () -> Unit,
    updates: AppUpdateViewModel,
    viewModel: SettingsViewModel = hiltViewModel(),
    ai: AiSetupViewModel = hiltViewModel(),
) {
    val saved by viewModel.settings.dashboardServerUrl.collectAsState()
    val aiState by ai.state.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    val dirty = draft.trim().trimEnd('/') != saved

    fun save() {
        viewModel.settings.setDashboardServerUrl(draft)
        focus.clearFocus()
        haptics.confirm()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .subScreenBottomPadding(),
    ) {
        SubScreenHeader(title = "Settings", onNavigateBack = onNavigateBack)
        Spacer(Modifier.height(10.dp))

        HealthSection(tone = LinkScreenTone) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Server, null, tint = LinkScreenTone, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Dashboard", style = HealthDisplay, fontSize = 19.sp, color = LinkScreenTone)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Laptops running tandem announce themselves here, so they show up without typing an address. " +
                    "The first time this phone connects, the laptop asks you once.",
                fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            PillField(
                value = draft, onValue = { draft = it }, hint = "https://your-dashboard", tone = LinkScreenTone,
                keyboardType = KeyboardType.Uri, onDone = { save() },
            )
            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    PillButton(icon = AppIcons.Check, label = "Save", accent = LinkScreenTone, emphasized = true, onClick = { save() })
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        val claude = aiState.current
        HealthSection(tone = LinkShortcutTone, modifier = Modifier.clip(RoundedCornerShape(28.dp)).clickable { haptics.tick(); onAi() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (claude != null) AiProviderLogo(claude.provider, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI", style = HealthDisplay, fontSize = 19.sp, color = LinkShortcutTone)
                    Text(
                        "Makes shortcuts from a few words. ${claude?.modelLabel ?: "Claude"}",
                        fontSize = 13.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (claude != null) AiAccessBadge(claude.access, label = if (claude.ready) claude.access.label else "Not set up")
                }
                Icon(AppIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(6.dp))
        val updateState by updates.state.collectAsState()
        val ready = updateState.info?.takeIf { updateState.updateAvailable }
        HealthSection(tone = UpdateTone, modifier = Modifier.clip(RoundedCornerShape(28.dp)).clickable { haptics.tick(); onUpdates() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Download, null, tint = UpdateTone, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Updates", style = HealthDisplay, fontSize = 19.sp, color = UpdateTone)
                    }
                    Text(
                        if (ready != null) "Weave ${prettyVersion(ready.versionName)} is ready" else "Weave ${updates.currentVersionName} · release notes",
                        fontSize = 13.sp, color = if (ready != null) TextPrimary else TextSecondary, lineHeight = 18.sp,
                    )
                }
                if (ready != null) Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(UpdateTone))
                Icon(AppIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "The laptop's side is tandem. Screen, sound and touches go straight between the two over Tailscale.",
            fontSize = 12.sp, color = TextTertiary, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).align(Alignment.CenterHorizontally),
        )
    }
}
