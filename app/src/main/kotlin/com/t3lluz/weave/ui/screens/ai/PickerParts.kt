package com.t3lluz.weave.ui.screens.ai

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.ui.theme.AiAccent
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Border
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.SelectedFill
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.TileDark
import com.t3lluz.weave.ui.util.rememberIsResumed
import com.t3lluz.weave.ui.util.rememberReducedMotion

// Pieces of DailyDash's AI tab (HermesChrome, HermesRail, HermesModelPicker) that the remote's
// menus and the AI picker are drawn with.

@Composable
internal fun MenuRow(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (danger) Error else TextPrimary, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, null, tint = if (danger) Error else TextSecondary, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
    )
}

@Composable
internal fun RailHeading(label: String) {
    Text(
        label.uppercase(),
        color = TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TileDark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(AiAccent),
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = TextTertiary, fontSize = 14.sp)
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "Clear search",
                tint = TextTertiary,
                modifier = Modifier.size(16.dp).clickable { onValueChange("") },
            )
        }
    }
}

/**
 * A word that is happening: a band of light crosses it, as T3 Code shimmers the step an
 * agent is on. The band moves in the draw phase only, and stands still with animations off
 * or the app in the background.
 */
@Composable
internal fun ShimmerText(text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier, fontWeight: FontWeight = FontWeight.SemiBold) {
    val moving = !rememberReducedMotion() && rememberIsResumed()
    val sweep = if (moving) {
        rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, AppMotion.shimmerSpec(), label = "shimmerSweep")
    } else {
        null
    }
    Text(
        text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val t = sweep?.value ?: return@drawWithContent
                val band = size.width * 0.5f
                val x = -band + (size.width + band) * t
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.85f), 1f to Color.Transparent,
                        startX = x, endX = x + band,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

/** A picker's tab: a provider's logo and name, a dot on the one in use. */
@Composable
internal fun PickerTab(label: String, selected: Boolean, picked: Boolean, onClick: () -> Unit, dim: Boolean = false, logo: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SelectedFill else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else Border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { role = Role.Tab; stateDescription = if (selected) "selected" else "" }
            .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        logo()
        Spacer(Modifier.width(6.dp))
        Text(
            label, color = when { selected -> TextPrimary; dim -> TextTertiary; else -> TextSecondary },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        if (picked) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(Success))
        }
    }
}
