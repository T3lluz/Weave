package com.t3lluz.weave.ui.screens.link

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkDirectoryState
import com.t3lluz.weave.data.link.LinkHost
import com.t3lluz.weave.data.link.LinkState
import com.t3lluz.weave.data.link.LinkStatus
import com.t3lluz.weave.ui.components.LivePulseDot
import com.t3lluz.weave.ui.components.LoadingSpinner
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.screens.health.pressSink
import com.t3lluz.weave.ui.screens.health.riseIn
import com.t3lluz.weave.ui.screens.health.roomColor
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.LinkScreenTone
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Success
import com.t3lluz.weave.ui.theme.Surface
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.util.openUrl
import com.t3lluz.weave.ui.util.rememberHaptics
import com.t3lluz.weave.ui.util.rememberIsResumed
import com.t3lluz.weave.ui.util.rememberReducedMotion

/** Where a laptop stands, as its little drawing shows it. */
private enum class Glow { Off, On, Busy }

/**
 * Not connected: the laptops the dashboard knows about, as Health draws its subjects. A
 * sentence on top says how things stand ("t3lluz is ready"), and each laptop is a room in the
 * screen's sky with a small laptop drawn in it, its screen lit while it is on and a light
 * running across it while it is being reached. A tap connects. One the dashboard doesn't list
 * can be reached by its address.
 */
@Composable
internal fun LinkFinder(
    state: LinkState,
    config: LinkConfig,
    laptops: LinkDirectoryState,
    onPick: (LinkHost) -> Unit,
    onAddress: (String) -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    onSettings: () -> Unit,
    onWake: () -> Unit = {},
) {
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val picked = config.host.isNotBlank()
    val pickedName = config.name.ifBlank { config.host.substringBefore(',') }
    // a laptop reached by its address is not in the dashboard's list, but it is still the one picked
    val listed = laptops.hosts.any { it.name == pickedName }
    val reduced = rememberReducedMotion()
    val entrance = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(AppMotion.HealthEntrance.MS, easing = LinearEasing)) }
    val rise = { entrance.value }

    fun go() {
        if (address.isBlank()) return
        keyboard?.hide()
        typing = false
        onAddress(address)
    }

    val headline = headlineFor(state, picked, pickedName, laptops)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(
            Modifier
                .align(Alignment.End)
                .padding(end = 12.dp, top = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Surface)
                .pressable("Settings", onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Settings, "Settings", tint = TextPrimary, modifier = Modifier.size(20.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                targetState = headline,
                transitionSpec = {
                    (slideInVertically(AppMotion.Link.swipe()) { it / 2 } + fadeIn(AppMotion.fadeTween())) togetherWith
                        (slideOutVertically(AppMotion.slideTween()) { -it / 2 } + fadeOut(AppMotion.fadeTween()))
                },
                label = "headline",
                modifier = Modifier.riseIn(0, rise).padding(horizontal = 4.dp),
            ) { (title, line) ->
                Column {
                    Text(title, style = HealthDisplay, fontSize = 34.sp, lineHeight = 38.sp, color = TextPrimary)
                    if (line != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(line, color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            var index = 1
            if (picked && !listed) {
                LaptopRoom(
                    pickedName, online = null, busy = false, state = state, picked = true,
                    onClick = onRetry, onRetry = onRetry, onForget = onForget,
                    modifier = Modifier.riseIn(index++, rise),
                    onWake = onWake.takeIf { config.wakeMac.isNotBlank() },
                )
            }
            laptops.hosts.forEach { laptop ->
                val mine = picked && laptop.name == pickedName
                LaptopRoom(
                    laptop.name, online = laptop.online, busy = laptop.busy,
                    state = if (mine) state else LinkState(), picked = mine,
                    onClick = { onPick(laptop) }, onRetry = onRetry, onForget = onForget,
                    modifier = Modifier.riseIn(index++, rise),
                    onWake = onWake.takeIf { mine && config.wakeMac.isNotBlank() },
                )
            }
            if (!laptops.loaded && !picked) {
                Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LoadingSpinner(color = LinkScreenTone, size = 18.dp)
                }
            }
            if (laptops.problem != null) {
                Text(
                    laptops.problem, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).riseIn(index, rise),
                )
            }

            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(
                visible = typing,
                enter = expandVertically(AppMotion.slideTween()) + fadeIn(AppMotion.fadeTween()),
                exit = shrinkVertically(AppMotion.slideTween()) + fadeOut(AppMotion.fadeTween()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(LinkScreenTone),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(roomColor(LinkScreenTone))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        decorationBox = { inner ->
                            if (address.isEmpty()) Text("Tailscale name or 100.x.y.z", color = TextTertiary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    PillButton(icon = AppIcons.Link, label = "Connect", accent = LinkScreenTone, emphasized = true, onClick = { go() })
                }
            }
            if (!typing) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(icon = AppIcons.Edit, label = "Connect by address", accent = TextSecondary, onClick = { typing = true })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The sentence over the list, and a line under it: how things stand, in words. */
private fun headlineFor(state: LinkState, picked: Boolean, pickedName: String, laptops: LinkDirectoryState): Pair<String, String?> {
    val ready = laptops.hosts.filter { it.online }
    return when {
        picked && state.status == LinkStatus.Pending -> "Waiting for a yes" to "Allow this phone on $pickedName."
        picked && (state.status == LinkStatus.Connecting || state.status == LinkStatus.Reconnecting) -> "Reaching $pickedName" to null
        picked && state.tailscaleOff && state.status == LinkStatus.Failed -> "Tailscale is off" to "Turn it on and $pickedName is a tap away."
        picked && state.status == LinkStatus.Failed -> "Couldn't reach $pickedName" to null
        picked && state.status == LinkStatus.Denied -> "$pickedName said no" to null
        !laptops.loaded -> "Looking for laptops" to null
        ready.size == 1 -> "${ready[0].name} is ready" to "Tap it to use it from here."
        ready.size > 1 -> "${ready.size} laptops are ready" to "Tap one to use it from here."
        laptops.hosts.isNotEmpty() -> "No laptop is on" to "They show up here as soon as they wake."
        else -> "No laptop yet" to "Run tandem on a laptop. It shows up here by itself."
    }
}

@Composable
private fun LaptopRoom(
    name: String,
    /** null for a laptop reached by its address: the dashboard says nothing about it. */
    online: Boolean?,
    busy: Boolean,
    state: LinkState,
    picked: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
    /** Wakes it from sleep, for the laptop whose network card the phone knows; null for the rest. */
    onWake: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val status = state.status
    val trying = picked && (status == LinkStatus.Connecting || status == LinkStatus.Reconnecting || status == LinkStatus.Idle)
    val waiting = picked && status == LinkStatus.Pending
    val stuck = picked && (status == LinkStatus.Failed || status == LinkStatus.Denied)
    val source = remember { MutableInteractionSource() }
    HealthSection(
        tone = LinkScreenTone,
        modifier = modifier
            .pressSink(source)
            .clip(RoundedCornerShape(28.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, enabled = !trying && !waiting) { haptics.click(); onClick() }
            .animateContentSize(AppMotion.entranceSpring()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LaptopGlyph(
                when {
                    trying || waiting -> Glow.Busy
                    online == false || stuck -> Glow.Off
                    else -> Glow.On
                },
                Modifier.size(width = 64.dp, height = 46.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = HealthDisplay, fontSize = 23.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (online == true && !stuck && !trying) {
                        LivePulseDot(color = Success)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        when {
                            trying -> "Connecting…"
                            waiting -> "Waiting for a yes"
                            picked && status == LinkStatus.Denied -> "Said no"
                            stuck -> "Couldn't connect"
                            online == null -> "By address"
                            online && busy -> "On · another phone is using it"
                            online -> "On"
                            else -> "Asleep or off"
                        },
                        color = if (stuck) Error else TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!trying && !waiting && !stuck) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(LinkScreenTone.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ChevronRight, null, tint = LinkScreenTone, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (waiting || stuck) {
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    waiting -> "Click the notification on $name, or allow it on the dashboard's laptop page."
                    status == LinkStatus.Denied -> "Ask again, and answer yes on $name this time."
                    else -> state.failure ?: "Is it on, and Tailscale up on both?"
                },
                color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        // asleep, and the phone knows how to wake it
        if (onWake != null && online == false && !stuck && !trying && !waiting) {
            Spacer(Modifier.height(14.dp))
            PillButton(icon = AppIcons.Power, label = "Wake it", accent = LinkScreenTone, emphasized = true, onClick = onWake)
        }
        if (stuck) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onWake != null) PillButton(icon = AppIcons.Power, label = "Wake", accent = LinkScreenTone, emphasized = true, onClick = onWake)
                if (state.tailscaleOff) {
                    PillButton(icon = AppIcons.ExternalLink, label = "Open Tailscale", accent = LinkScreenTone, emphasized = true, onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE)
                        if (launch != null) {
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            context.openUrl("https://play.google.com/store/apps/details?id=$TAILSCALE")
                        }
                    })
                }
                PillButton(icon = AppIcons.Refresh, label = "Try again", accent = LinkScreenTone, emphasized = !state.tailscaleOff, onClick = onRetry)
                PillButton(icon = AppIcons.Close, label = "Forget", accent = TextSecondary, onClick = onForget)
            }
        }
    }
}

private const val TAILSCALE = "com.tailscale.ipn"

/**
 * A small laptop, lid up. On, its screen is lit in the sky; while it is being reached a light
 * runs across the screen; off or asleep, the screen is dark.
 */
@Composable
private fun LaptopGlyph(glow: Glow, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val resumed = rememberIsResumed()
    var frame by remember { mutableLongStateOf(0L) }
    val running = glow == Glow.Busy && !reduced && resumed
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) withFrameMillis { frame = it }
    }
    Canvas(modifier) {
        val tone = LinkScreenTone
        val lidW = size.width * 0.78f
        val lidH = size.height * 0.74f
        val left = (size.width - lidW) / 2f
        val r = 5.dp.toPx()
        // the lid, and the screen in it
        drawRoundRect(tone.copy(alpha = 0.32f), Offset(left, 0f), Size(lidW, lidH), CornerRadius(r))
        val inset = 3.dp.toPx()
        val screenTL = Offset(left + inset, inset)
        val screen = Size(lidW - inset * 2, lidH - inset * 2)
        val lit = when (glow) {
            Glow.On -> tone.copy(alpha = 0.85f)
            Glow.Busy -> tone.copy(alpha = 0.35f)
            Glow.Off -> Color.Black.copy(alpha = 0.55f)
        }
        drawRoundRect(lit, screenTL, screen, CornerRadius(r * 0.6f))
        if (glow == Glow.Busy) {
            val t = if (running) (frame % 1400L) / 1400f else 0.5f
            val band = screen.width * 0.34f
            val x = screenTL.x - band + (screen.width + band) * t
            clipRect(screenTL.x, screenTL.y, screenTL.x + screen.width, screenTL.y + screen.height) {
                drawRoundRect(tone.copy(alpha = 0.9f), Offset(x, screenTL.y), Size(band, screen.height), CornerRadius(r))
            }
        }
        // the base: a slab a little wider than the lid, with a notch for the thumb
        val baseTop = lidH + 1.5.dp.toPx()
        val base = Path().apply {
            moveTo(0f, baseTop)
            lineTo(size.width, baseTop)
            lineTo(size.width - 3.dp.toPx(), size.height)
            lineTo(3.dp.toPx(), size.height)
            close()
        }
        drawPath(base, tone.copy(alpha = 0.45f))
        drawRoundRect(
            Color.Black.copy(alpha = 0.35f),
            Offset(size.width / 2f - 7.dp.toPx(), baseTop),
            Size(14.dp.toPx(), 2.dp.toPx()),
            CornerRadius(1.dp.toPx()),
        )
    }
}
