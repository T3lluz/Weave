package com.t3lluz.weave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.ui.theme.Border
import com.t3lluz.weave.ui.theme.Primary
import com.t3lluz.weave.ui.theme.Surface
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.screens.health.pressSink
import com.t3lluz.weave.ui.util.rememberHaptics

private val PillShape = RoundedCornerShape(999.dp)

/**
 * A compact icon + label action for headers and toolbars, where a full-width
 * [MacroButton] would be too heavy. [emphasized] tints it with [accent] for the
 * primary action in a row.
 *
 * Fires its own haptic tick, so callers must not add one. The pill keeps its compact
 * look, but the tap target around it is at least 40dp tall.
 */
@Composable
fun PillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Primary,
    emphasized: Boolean = false,
) {
    val haptics = rememberHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // it gives under the finger as the tiles do, and the taller tap area with it
            .pressSink(interactionSource, 0.94f)
            .heightIn(min = 40.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(PillShape)
                .background(if (emphasized) accent.copy(alpha = 0.16f) else Surface)
                .border(1.dp, if (emphasized) accent.copy(alpha = 0.38f) else Border, PillShape)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (emphasized) accent else TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (emphasized) accent else TextPrimary,
            )
        }
    }
}
