package com.t3lluz.weave.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.remote.AiModel
import com.t3lluz.weave.data.remote.AnthropicModels
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.roomColor
import com.t3lluz.weave.ui.theme.AiTone
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.viewmodel.AiProviderStatus
import com.t3lluz.weave.ui.viewmodel.AiSetupViewModel

/**
 * Claude's models, laid out as the dashboard's model picker is on a phone and drawn in Weave's
 * rooms: search on top, then each model line (Fable, Opus, Sonnet, Haiku) with its newest
 * version and the older ones folded under it, and at the foot a room with the model in use,
 * its effort and Done. Nothing here closes the sheet but Done or a swipe. Not signed in yet,
 * it offers Settings → AI ([onManage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPickerSheet(
    viewModel: AiSetupViewModel,
    onDismiss: () -> Unit,
    onManage: (() -> Unit)?,
    /** The line under the title, e.g. what the AI is for here. */
    note: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val haptics = rememberHaptics()
    val cur = state.current
    LaunchedEffect(cur?.ready) { if (cur?.ready == true) viewModel.refreshModels(cur.provider) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp),
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Model", style = HealthDisplay, fontSize = 30.sp, color = TextPrimary)
                    Text(note ?: "Claude makes your shortcuts", color = TextSecondary, fontSize = 14.sp)
                }
                if (cur != null) AiAccessBadge(cur.access, label = if (cur.ready) cur.access.label else "Not set up")
            }
            Spacer(Modifier.height(14.dp))
            if (cur == null || !cur.ready) {
                SignInRoom(cur, onManage)
                return@Column
            }
            var query by remember { mutableStateOf("") }
            SearchPill(query, onChange = { query = it })
            Spacer(Modifier.height(8.dp))
            ModelLines(
                models = modelsFor(state, cur.provider),
                picked = cur.model,
                query = query,
                loading = cur.provider in state.loadingModels,
                onPick = { id -> haptics.tick(); viewModel.setModel(cur.provider, id) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            PickedRoom(cur, state.effort, onEffort = { haptics.tick(); viewModel.setEffort(it) }, onDone = onDismiss)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Search, as a pill in the AI's room colour. */
@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(roomColor(AiTone))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = TextTertiary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(AiTone),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Search Claude's models", color = TextTertiary, fontSize = 15.sp)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                AppIcons.Close, "Clear search", tint = TextTertiary,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

/** A model line: its newest version, and the older ones of the same line. */
private data class ModelLine(val name: String, val newest: AiModel, val older: List<AiModel>)

/** "claude-opus-4-8" is on the Opus line. The API lists newest first, so the first of a line is its newest. */
private fun linesOf(models: List<AiModel>): List<ModelLine> {
    val tiers = listOf("fable", "opus", "sonnet", "haiku")
    return models.groupBy { m -> tiers.firstOrNull { it in m.id } ?: m.id }
        .map { (tier, ms) -> ModelLine(tier.replaceFirstChar(Char::uppercase), ms.first(), ms.drop(1)) }
}

@Composable
private fun ModelLines(
    models: List<AiModel>,
    picked: String,
    query: String,
    loading: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val q = query.trim().lowercase()
    val lines = remember(models) { linesOf(models) }
    // a line whose older version is in use starts open
    var open by rememberSaveable(picked) { mutableStateOf(lines.filter { l -> l.older.any { it.id == picked } }.map { it.name }.toSet()) }
    LazyColumn(modifier.fillMaxWidth()) {
        if (q.isNotEmpty()) {
            val hits = models.filter { "${it.name} ${it.id}".lowercase().contains(q) }
            hits.forEach { m -> item(key = "q-${m.id}") { ModelRow(m, m.id == picked) { onPick(m.id) } } }
            if (hits.isEmpty()) item(key = "none") { Text("No match", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        lines.forEach { line ->
            item(key = "n-${line.newest.id}") { ModelRow(line.newest, line.newest.id == picked) { onPick(line.newest.id) } }
            if (line.older.isNotEmpty()) {
                val isOpen = line.name in open
                item(key = "f-${line.name}") {
                    OlderFold(line.name, line.older.size, isOpen) { open = if (isOpen) open - line.name else open + line.name }
                }
                item(key = "o-${line.name}") {
                    AnimatedVisibility(
                        visible = isOpen,
                        enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                        exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
                    ) {
                        Column(Modifier.padding(start = 14.dp)) {
                            line.older.forEach { m -> ModelRow(m, m.id == picked, small = true) { onPick(m.id) } }
                        }
                    }
                }
            }
        }
        if (loading) {
            item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center) {
                    LoadingSpinner(color = AiTone, size = 16.dp)
                }
            }
        }
    }
}

/** One model: its name and what it is good for; the one in use sits in the AI's colour with a tick. */
@Composable
private fun ModelRow(m: AiModel, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) roomColor(AiTone) else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = if (small) 48.dp else 58.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                m.name, color = if (on) AiTone else TextPrimary, fontSize = if (small) 14.5.sp else 16.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!small) m.note?.let { Text(it, color = TextTertiary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (on) Icon(AppIcons.Check, null, tint = AiTone, modifier = Modifier.size(20.dp))
    }
}

/** "Older Opus", a fold under a line's newest version. */
@Composable
private fun OlderFold(line: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { if (open) haptics.toggleOff() else haptics.toggleOn(); onToggle() }
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) AppIcons.ChevronUp else AppIcons.ChevronDown, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Older $line", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = TextTertiary, fontSize = 12.sp)
    }
}

/** The foot of the sheet: the model in use, its effort, and Done. */
@Composable
private fun PickedRoom(cur: AiProviderStatus, effort: String, onEffort: (String) -> Unit, onDone: () -> Unit) {
    val stops = AnthropicModels.effortsFor(cur.model)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(roomColor(AiTone))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cur.modelLabel, style = HealthDisplay, fontSize = 20.sp, color = AiTone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (stops.size > 1) "${AnthropicModels.EFFORT_NAME[effort] ?: effort} effort" else "No effort setting on this one",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            PillButton(icon = AppIcons.Check, label = "Done", accent = AiTone, emphasized = true, onClick = onDone)
        }
        if (stops.size > 1) {
            Spacer(Modifier.height(16.dp))
            EffortSlider(stops, effort, onEffort)
        }
    }
}

/** Not signed in: what Claude is for here, and the way to Settings → AI. */
@Composable
private fun SignInRoom(cur: AiProviderStatus?, onManage: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(roomColor(AiTone))
            .padding(18.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(AiTone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            if (cur != null) AiProviderLogo(cur.provider, 30.dp) else Icon(AppIcons.Sparkles, null, tint = AiTone, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Sign in to Claude", style = HealthDisplay, fontSize = 20.sp, color = AiTone)
        Text(
            cur?.detail ?: "Sign in with your Claude plan, or add a key",
            color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
        )
        if (onManage != null) {
            Spacer(Modifier.height(14.dp))
            PillButton(icon = AppIcons.Account, label = "Set up Claude", accent = AiTone, emphasized = true, onClick = onManage)
        }
    }
}
