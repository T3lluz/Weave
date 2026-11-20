package com.t3lluz.weave.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.t3lluz.weave.data.remote.AiAccess
import com.t3lluz.weave.data.remote.AiApiClient
import com.t3lluz.weave.data.remote.AiProvider
import com.t3lluz.weave.data.remote.AnthropicModels
import com.t3lluz.weave.ui.components.TileRow
import com.t3lluz.weave.ui.components.AiAccessBadge
import com.t3lluz.weave.ui.components.AiPickerSheet
import com.t3lluz.weave.ui.components.AiProviderLogo
import com.t3lluz.weave.ui.components.EffortSlider
import com.t3lluz.weave.ui.components.LoadingSpinner
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.components.PillField
import com.t3lluz.weave.ui.components.SubScreenHeader
import com.t3lluz.weave.ui.components.subScreenBottomPadding
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.screens.link.RoomTitle
import com.t3lluz.weave.ui.theme.AiTone
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.Warning
import com.t3lluz.weave.ui.util.openUrl
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.viewmodel.AiSetupViewModel
import com.t3lluz.weave.ui.viewmodel.AiTest
import com.t3lluz.weave.ui.viewmodel.AiUiState

/**
 * Settings → AI, as Weave's rooms: the model in use with its effort (Change opens the model
 * sheet, Test asks it for one word), then Claude's sign-in with your plan, and an API key for
 * when you'd rather pay per token.
 */
@Composable
fun AiSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AiSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // the test's answer, and a sign-in that went through, are felt as they land
    LaunchedEffect(state.test) {
        when (state.test) {
            is AiTest.Passed -> haptics.confirm()
            is AiTest.Failed -> haptics.reject()
            else -> Unit
        }
    }
    var signedIn by remember { mutableStateOf(state.claude.connected) }
    LaunchedEffect(state.claude.connected) {
        if (state.claude.connected && !signedIn) haptics.paired()
        signedIn = state.claude.connected
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TileRow.Side)
            .subScreenBottomPadding(),
    ) {
        SubScreenHeader(title = "AI", subtitle = "Claude makes shortcuts from a few words", onNavigateBack = onNavigateBack)
        Spacer(Modifier.height(10.dp))

        ModelRoom(
            state = state,
            onChange = { picking = true },
            onTest = viewModel::test,
            onDismissTest = viewModel::dismissTest,
            onEffort = { haptics.tick(); viewModel.setEffort(it) },
        )
        AccountRoom(state, viewModel)
        val claude = state.current
        if (claude != null && (claude.access != AiAccess.ACCOUNT || state.keys[claude.provider].orEmpty().isNotBlank())) {
            KeyRoom(claude.provider, saved = state.keys[claude.provider].orEmpty(), onSave = { viewModel.saveKey(claude.provider, it) })
        }
    }

    if (picking) {
        AiPickerSheet(viewModel = viewModel, onDismiss = { picking = false }, onManage = null)
    }
}

/** The model in use, its effort, and the two things to do with it. */
@Composable
private fun ModelRoom(
    state: AiUiState,
    onChange: () -> Unit,
    onTest: () -> Unit,
    onDismissTest: () -> Unit,
    onEffort: (String) -> Unit,
) {
    val cur = state.current
    HealthSection(tone = AiTone) {
        RoomTitle(AppIcons.Sparkles, "Model", AiTone)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cur != null) AiProviderLogo(cur.provider, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cur?.modelLabel ?: "Nothing set up", style = HealthDisplay, fontSize = 24.sp, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cur?.provider?.displayName.orEmpty(), color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    if (cur != null) AiAccessBadge(cur.access, label = if (cur.ready) cur.access.label else "Not set up")
                }
            }
        }
        val stops = cur?.model?.let(AnthropicModels::effortsFor).orEmpty()
        if (cur?.ready == true && stops.size > 1) {
            Spacer(Modifier.height(16.dp))
            EffortSlider(stops, state.effort, onEffort)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(icon = AppIcons.Sparkles, label = "Change model", accent = AiTone, emphasized = true, onClick = onChange)
            if (cur?.ready == true && state.test != AiTest.Running) {
                PillButton(icon = AppIcons.Bolt, label = "Test", accent = AiTone, onClick = onTest)
            }
        }
        AnimatedVisibility(visible = state.test != null) {
            state.test?.let { TestLine(it, onDismissTest) }
        }
    }
}

/** What the test found, as one line in the room. A tap clears it. */
@Composable
private fun TestLine(test: AiTest, onDismiss: () -> Unit) {
    val (tone, text) = when (test) {
        AiTest.Running -> AiTone to "Asking it for one word…"
        is AiTest.Passed -> Success to "${test.provider.displayName} answered in ${"%.1f".format(test.seconds)} s"
        is AiTest.Failed -> Error to "It didn't answer: ${test.message}"
    }
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(CircleShape)
            .clickable(enabled = test != AiTest.Running, onClick = onDismiss)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (test == AiTest.Running) {
            LoadingSpinner(color = tone, size = 14.dp)
        } else {
            Icon(if (test is AiTest.Passed) AppIcons.CheckCircleFilled else AppIcons.Warning, null, tint = tone, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/** Claude: sign in with the plan (the code comes back on Claude's page), or signed in as who. */
@Composable
private fun AccountRoom(state: AiUiState, viewModel: AiSetupViewModel) {
    val claude = state.claude
    var code by remember { mutableStateOf("") }
    HealthSection(tone = LinkHandsTone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoomTitle(AppIcons.Account, "Claude account", LinkHandsTone, Modifier.weight(1f))
            if (claude.connected) AiAccessBadge(AiAccess.ACCOUNT, label = "Signed in")
        }
        Text(
            when {
                claude.connected -> listOfNotNull(claude.email ?: "Signed in", claude.plan?.let { "$it plan" }).joinToString(" · ")
                claude.awaitingCode -> "Approve the sign-in on Claude's page, then paste the code it shows."
                else -> "Sign in with Claude Pro, Max, Team or Enterprise. Shortcuts then use your plan, not API credits."
            },
            fontSize = 14.sp, color = TextSecondary, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        when {
            claude.awaitingCode && !claude.connected -> {
                PillField(value = code, onValue = { code = it }, hint = "Paste code#state", tone = LinkHandsTone)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        icon = AppIcons.Check, label = if (claude.busy) "Signing in…" else "Finish", accent = LinkHandsTone, emphasized = true,
                        onClick = { if (code.isNotBlank() && !claude.busy) { viewModel.finishClaudeLogin(code); code = "" } },
                    )
                    PillButton(icon = AppIcons.Close, label = "Cancel", accent = LinkHandsTone, onClick = { if (!claude.busy) { viewModel.cancelClaudeLogin(); code = "" } })
                }
            }
            claude.connected -> PillButton(icon = AppIcons.Close, label = "Sign out", accent = LinkHandsTone, onClick = viewModel::signOutClaude)
            else -> PillButton(icon = AppIcons.Account, label = "Sign in with Claude", accent = LinkHandsTone, emphasized = true, onClick = viewModel::startClaudeLogin)
        }
        claude.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = if (claude.connected) Success else TextSecondary)
        }
    }
}

/** An API key: kept on this phone, masked, with where to get one. Used while not signed in. */
@Composable
private fun KeyRoom(provider: AiProvider, saved: String, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var draft by remember(provider, saved) { mutableStateOf(saved) }
    var visible by remember { mutableStateOf(false) }
    val dirty = draft.trim() != saved
    val formatOk = AiApiClient.looksLikeValidKey(provider, draft)
    HealthSection(tone = LinkScreenTone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoomTitle(AppIcons.Key, "API key", LinkScreenTone, Modifier.weight(1f))
            if (saved.isNotBlank()) AiAccessBadge(AiAccess.API_KEY, label = "Saved")
        }
        Text(
            "Optional. Used only while you're not signed in, billed per token by Anthropic. It stays on this phone.",
            fontSize = 14.sp, color = TextSecondary, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        PillField(
            value = draft, onValue = { draft = it }, hint = AiApiClient.keyPlaceholder(provider), tone = LinkScreenTone,
            secret = !visible, warn = !formatOk,
            trailing = {
                Icon(
                    if (visible) AppIcons.EyeOff else AppIcons.Eye,
                    contentDescription = if (visible) "Hide key" else "Show key",
                    tint = TextTertiary,
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable { haptics.tick(); visible = !visible },
                )
            },
        )
        if (!formatOk) {
            Text(
                "That doesn't look like a Claude key (${AiApiClient.keyPlaceholder(provider)})",
                fontSize = 12.sp, color = Warning, modifier = Modifier.padding(top = 6.dp, start = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dirty && draft.isNotBlank()) {
                PillButton(icon = AppIcons.Check, label = "Save", accent = LinkScreenTone, emphasized = true, onClick = { onSave(draft.trim()) })
            }
            if (saved.isNotBlank()) {
                PillButton(icon = AppIcons.Close, label = "Clear", accent = LinkScreenTone, onClick = { draft = ""; onSave("") })
            }
            AiApiClient.keyUrl(provider)?.let { url ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        "Get a key", color = LinkScreenTone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable { haptics.tick(); context.openUrl(url) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
