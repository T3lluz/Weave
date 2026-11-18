package com.t3lluz.weave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.remote.AnthropicModels
import com.t3lluz.weave.ui.theme.AiTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.util.rememberHaptics

/**
 * Claude's depth, low to max, as the dashboard's model picker draws it: one block cell per
 * level, filled up to the one in use, which wears a ring; the levels named under the cells and
 * a line saying what the picked one costs. A tap or a drag moves it; the choice is made when
 * the finger lifts, and a screen reader sets it as it would any slider.
 */
@Composable
fun EffortSlider(stops: List<String>, effort: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, tone: Color = AiTone) {
    if (stops.size < 2) return
    val at = stops.indexOf(effort).takeIf { it >= 0 } ?: 0
    var dragging by remember { mutableIntStateOf(-1) }
    val shown = (if (dragging >= 0) dragging else at).coerceIn(0, stops.lastIndex)
    val shownEffort = stops[shown]
    val haptics = rememberHaptics()
    // the gesture blocks outlive a pick, so they read where the slider stands now
    val atNow by rememberUpdatedState(at)
    val pick by rememberUpdatedState { i: Int ->
        val to = i.coerceIn(0, stops.lastIndex)
        if (to != atNow) onPick(stops[to])
    }
    fun indexAt(x: Float, width: Int) = ((x / width) * stops.size).toInt().coerceIn(0, stops.lastIndex)
    fun name(e: String) = AnthropicModels.EFFORT_NAME[e] ?: e
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Effort", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(name(shownEffort), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                AnthropicModels.EFFORT_HINT[shownEffort].orEmpty(),
                color = TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Effort"
                    stateDescription = name(shownEffort)
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..stops.lastIndex.toFloat(), (stops.size - 2).coerceAtLeast(0))
                    setProgress { v -> pick(Math.round(v)); true }
                }
                .pointerInput(stops) {
                    detectTapGestures { p ->
                        haptics.tick()
                        pick(indexAt(p.x, size.width))
                    }
                }
                .pointerInput(stops) {
                    detectHorizontalDragGestures(
                        onDragStart = { p -> dragging = indexAt(p.x, size.width) },
                        onDragEnd = {
                            val i = dragging
                            dragging = -1
                            if (i >= 0) pick(i)
                        },
                        onDragCancel = { dragging = -1 },
                    ) { change, _ ->
                        val i = indexAt(change.position.x, size.width)
                        if (i != dragging) { dragging = i; haptics.tick() }
                    }
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stops.forEachIndexed { i, _ ->
                    val cell = RoundedCornerShape(7.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .then(if (i == shown) Modifier.border(2.dp, TextPrimary, cell).padding(3.dp) else Modifier)
                            .clip(if (i == shown) RoundedCornerShape(4.dp) else cell)
                            .background(if (i > shown) Color.White.copy(alpha = 0.08f) else tone.copy(alpha = if (i == shown) 1f else 0.6f)),
                    )
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stops.forEachIndexed { i, e ->
                    Text(
                        AnthropicModels.EFFORT_SHORT[e] ?: e,
                        color = if (i == shown) TextPrimary else TextTertiary,
                        fontSize = 12.sp, fontWeight = if (i == shown) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
