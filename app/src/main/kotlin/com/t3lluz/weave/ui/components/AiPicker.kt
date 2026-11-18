package com.t3lluz.weave.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.remote.AiAccess
import com.t3lluz.weave.data.remote.AiModel
import com.t3lluz.weave.data.remote.AiProvider
import com.t3lluz.weave.data.remote.AnthropicModels
import com.t3lluz.weave.ui.screens.ai.ModelLogo
import com.t3lluz.weave.ui.screens.ai.PickerTab
import com.t3lluz.weave.ui.screens.ai.RailHeading
import com.t3lluz.weave.ui.screens.ai.SearchBox
import com.t3lluz.weave.ui.theme.AiTone
import com.t3lluz.weave.ui.theme.LinkHandsTone
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.Border
import com.t3lluz.weave.ui.theme.Primary
import com.t3lluz.weave.ui.theme.ServerBrand
import com.t3lluz.weave.ui.theme.ServerWell
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.Surface
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.viewmodel.AiProviderStatus
import com.t3lluz.weave.ui.viewmodel.AiUiState
import com.t3lluz.weave.ui.viewmodel.AiSetupViewModel

/*
 * The app's AI picker, DailyDash's, the same everywhere the AI is chosen (Settings → AI, the
 * shortcut sheet): the provider with its maker's logo, how the phone is let in (your account
 * or an API key: [AiAccessBadge]), and its models in one sheet.
 */

/** The maker's logo for a provider. */
fun AiProvider.logoBrand(): String = when (this) {
    AiProvider.ANTHROPIC -> "anthropic"
}

@Composable
fun AiProviderLogo(provider: AiProvider, size: Dp, modifier: Modifier = Modifier) {
    ModelLogo(provider.logoBrand(), size, modifier)
}

/** What a picker calls each provider, next to its name. */
val AiProvider.tagline: String
    get() = when (this) {
        AiProvider.ANTHROPIC -> "Your Claude plan, or an API key"
    }

private fun AiAccess.icon(): ImageVector = when (this) {
    AiAccess.ACCOUNT -> AppIcons.Account
    AiAccess.API_KEY -> AppIcons.Key
    AiAccess.NONE -> AppIcons.Add
}

/** The account wears the hands' green, a key the screen's sky: the remote's own tones. */
fun AiAccess.tone(): Color = when (this) {
    AiAccess.ACCOUNT -> LinkHandsTone
    AiAccess.API_KEY -> LinkScreenTone
    AiAccess.NONE -> TextTertiary
}

/**
 * How the phone is let in to a provider: **Account** (signed in; the plan's usage) or **API key**
 * (billed per token). Shown next to the provider in every picker so the two never look alike.
 */
@Composable
fun AiAccessBadge(access: AiAccess, modifier: Modifier = Modifier, label: String = access.label) {
    val tone = access.tone()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.13f))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(access.icon(), contentDescription = null, tint = tone, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tone, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The model in use as one pill, drawn as Weave's pill buttons are in the AI's colour: Claude's
 * mark, the model's short name ("Opus 5.5") and its effort. A tap opens [AiPickerSheet].
 * [compact] is the mark alone, for a row that needs the room.
 */
@Composable
fun AiPill(
    status: AiProviderStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    compact: Boolean = false,
    effort: String? = null,
) {
    val haptics = rememberHaptics()
    val name = status?.modelLabel?.removePrefix("Claude ") ?: "Pick a model"
    val depth = effort?.takeIf { status != null && AnthropicModels.effortsFor(status.model).isNotEmpty() }
        ?.let { AnthropicModels.EFFORT_SHORT[it] ?: it }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(AiTone.copy(alpha = if (open) 0.26f else 0.16f))
            .border(1.dp, AiTone.copy(alpha = if (open) 0.6f else 0.38f), shape)
            .clickable { haptics.tick(); onClick() }
            .semantics { contentDescription = "Model: $name${depth?.let { ", $it effort" }.orEmpty()}" }
            .padding(start = 5.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) AiProviderLogo(status.provider, 24.dp) else Icon(AppIcons.Sparkles, null, tint = AiTone, modifier = Modifier.size(20.dp))
        if (!compact) {
            Spacer(Modifier.width(7.dp))
            Text(
                name, color = AiTone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
            )
            if (depth != null) {
                Spacer(Modifier.width(5.dp))
                Text(depth, color = AiTone.copy(alpha = 0.65f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(AppIcons.ChevronDown, null, tint = AiTone.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
    }
}

/** A provider's models for the pickers: Claude's own list. */
fun modelsFor(state: AiUiState, provider: AiProvider): List<AiModel> = state.models[provider].orEmpty()
