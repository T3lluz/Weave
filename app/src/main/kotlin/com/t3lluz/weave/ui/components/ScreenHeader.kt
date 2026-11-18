package com.t3lluz.weave.ui.components

import com.t3lluz.weave.ui.theme.Surface
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.ui.theme.HeaderColor
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.theme.AppIcons

/** Bottom padding for tab content so the last card scrolls clear of the floating nav pill. */
val TabContentBottomPadding = 120.dp

/**
 * Shared 32.sp bold page title for the bottom-nav tabs (Home, Health, AI, Settings).
 * Pushed screens use [SubScreenHeader] instead.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HeaderColor,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 16.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * The top of a pushed screen (Settings, Settings → AI), as the laptop list's top is drawn: a
 * round back button, then the title in the display face with a line under it.
 */
@Composable
fun SubScreenHeader(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Surface)
                    .clickable(role = Role.Button, onClickLabel = "Back") { haptics.click(); onNavigateBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.ArrowBack, "Back", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            trailing?.invoke(this)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = HealthDisplay, fontSize = 34.sp, lineHeight = 38.sp, color = TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Bottom padding for pushed screens: they have no nav pill, only the system bar. */
fun Modifier.subScreenBottomPadding(): Modifier = navigationBarsPadding().padding(bottom = 24.dp)
