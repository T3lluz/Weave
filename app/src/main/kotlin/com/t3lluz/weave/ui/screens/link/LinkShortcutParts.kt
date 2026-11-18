package com.t3lluz.weave.ui.screens.link

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.t3lluz.weave.data.link.LABEL_MAX
import com.t3lluz.weave.data.link.LinkGlyph
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutKind
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.data.link.LinkStep
import com.t3lluz.weave.data.link.LinkToggle
import com.t3lluz.weave.data.link.describeStep
import com.t3lluz.weave.data.link.waitWords
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.components.horizontalEdgeFade
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.health.HealthSection
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import kotlinx.coroutines.delay

/**
 * A shortcut's steps, in the order they run: each with its number, its picture and what it does.
 * A tap picks the one the room below changes; the one picked moves up and down and a cross
 * takes any out. Under them, another step or a wait.
 */
@Composable
internal fun StepsRoom(
    steps: List<LinkStep>,
    editing: Int,
    laptopName: String,
    store: LinkShortcutStore,
    appName: (String) -> String?,
    onEdit: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (LinkShortcutKind) -> Unit,
) {
    val icons by store.icons.collectAsState()
    val tone = LinkShortcutTone
    HealthSection(tone = tone) {
        RoomTitle(AppIcons.ListChecks, "Steps", tone)
        Text(
            if (steps.size == 1) "Add more to run them one after another: bring an app to the front, then press its keys."
            else "They run top to bottom. Tap one to change it.",
            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
        )
        steps.forEachIndexed { i, st ->
            val picked = i == editing
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (picked) tone.copy(alpha = 0.16f) else Color.Transparent)
                    .pressable("Step ${i + 1}") { onEdit(i) }
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(tone.copy(alpha = if (picked) 0.32f else 0.16f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { StepFace(st, store, icons, size = 22.dp) }
                Spacer(Modifier.width(10.dp))
                val said = describeStep(st, laptopName, appName(st.app))
                Text(
                    said ?: "Pick what it does below",
                    color = if (said == null) TextTertiary else TextPrimary, fontSize = 14.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (picked && i > 0) SmallButton(AppIcons.ArrowUp, "Move up") { onMove(i, i - 1) }
                if (picked && i < steps.lastIndex) SmallButton(AppIcons.ArrowDown, "Move down") { onMove(i, i + 1) }
                if (steps.size > 1) SmallButton(AppIcons.Close, "Remove step ${i + 1}") { onRemove(i) }
            }
        }
        if (steps.size < LinkShortcut.STEPS_MAX) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(icon = AppIcons.Add, label = "Add a step", accent = tone, emphasized = true, onClick = { onAdd(LinkShortcutKind.KEYS) })
                PillButton(icon = AppIcons.Clock, label = "Add a wait", accent = tone, onClick = { onAdd(LinkShortcutKind.WAIT) })
            }
        }
    }
}

@Composable
private fun SmallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).pressable(label, onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** How long a wait waits: a few usual lengths, and a slider for the rest. */
@Composable
internal fun WaitPicker(ms: Int, onChange: (Int) -> Unit) {
    Column {
        Text("Wait ${waitWords(ms)}", style = HealthDisplay, fontSize = 22.sp, color = TextPrimary)
        Text(
            "Steps after an app already wait for it to come to the front. Add time here for one that is slow to start.",
            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        val chips = rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalEdgeFade(chips).horizontalScroll(chips), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(200, 400, 600, 1000, 2000, 3000).forEach { v -> Chip(waitWords(v), ms == v, small = true) { onChange(v) } }
        }
        Slider(
            value = ms.toFloat().coerceIn(100f, 5000f),
            onValueChange = { onChange((it / 50).toInt() * 50) },
            valueRange = 100f..5000f,
            colors = SliderDefaults.colors(thumbColor = LinkShortcutTone, activeTrackColor = LinkShortcutTone, inactiveTrackColor = Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Colours a glyph can be: its app's own (Discord, Spotify, YouTube), the remote's tones, red for "off". */
private val GLYPH_COLORS: List<Long?> = listOf(
    null, 0xFFE4E4E4, 0xFF5AB4F5, 0xFF5CC98A, 0xFFFF9F45, 0xFFC38AF0, 0xFFED4245, 0xFF5865F2, 0xFF1DB954, 0xFFFF0000,
)

/**
 * How the shortcut looks: its step's own picture, or an icon found by words (Iconify's sets,
 * brand logos among them) in a colour. With two looks it flips each time it runs (Deafen,
 * then Undeafen with the headphones struck out); a switch picks which look this room changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookRoom(
    draft: LinkShortcut,
    lookOn: Boolean,
    store: LinkShortcutStore,
    onLookOn: (Boolean) -> Unit,
    onChange: (LinkShortcut) -> Unit,
    search: suspend (String) -> List<String>,
) {
    val icons by store.icons.collectAsState()
    val tone = LinkShortcutTone
    val toggle = draft.toggle
    val editingOn = lookOn && toggle != null
    val glyph = if (editingOn) toggle?.glyph else draft.glyph
    fun setGlyph(g: LinkGlyph?) = onChange(if (editingOn && toggle != null) draft.copy(toggle = toggle.copy(glyph = g)) else draft.copy(glyph = g))

    HealthSection(tone = tone) {
        RoomTitle(AppIcons.Images, "Look", tone)
        ToggleRow("Two looks", "Flips each time it runs, like Deafen and Undeafen", toggle != null, tone) {
            onChange(if (toggle == null) draft.copy(toggle = LinkToggle(label = "", glyph = draft.glyph)) else draft.copy(toggle = null, on = false))
            onLookOn(false)
        }
        if (toggle != null) {
            Spacer(Modifier.height(4.dp))
            Segmented(
                listOf(false to "Usual: ${draft.label.ifBlank { "off" }}", true to "On: ${toggle.label.ifBlank { "on" }}"),
                picked = lookOn, tone = tone,
            ) { onLookOn(it) }
            if (lookOn) {
                Spacer(Modifier.height(10.dp))
                LinkField(toggle.label, { onChange(draft.copy(toggle = toggle.copy(label = it.take(LABEL_MAX)))) }, "Its name while on: Undeafen")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(ShortcutFill), contentAlignment = Alignment.Center) {
                val lead = draft.lead
                when {
                    glyph != null -> GlyphFace(glyph, store, icons, size = 30.dp, badgeApp = draft.appId)
                    lead != null -> StepFace(lead, store, icons, size = 30.dp)
                    else -> Icon(AppIcons.Bolt, null, tint = tone, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (glyph != null) "Its own icon" else "Its step's picture", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    glyph?.id ?: "The app's icon, the site's, or the keys",
                    color = TextTertiary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (glyph != null) PillButton(icon = AppIcons.Close, label = "Clear", accent = tone, onClick = { setGlyph(null) })
        }
        if (glyph != null) {
            Spacer(Modifier.height(12.dp))
            val colors = rememberScrollState()
            Row(Modifier.fillMaxWidth().horizontalEdgeFade(colors).horizontalScroll(colors), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GLYPH_COLORS.forEach { c ->
                    val on = glyph.color == c
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (on) TextPrimary else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(c?.let { Color(it.toInt()) } ?: tone)
                            .clickable { setGlyph(glyph.copy(color = c)) },
                    )
                }
            }
        }

        // find one by words; with nothing typed, ideas for its name
        Spacer(Modifier.height(14.dp))
        var query by remember { mutableStateOf("") }
        // with nothing typed: ideas from the icon it has ("headphones" brings its struck-out one too),
        // else from its name; the first of those that finds anything
        val seeds = if (query.isNotBlank()) listOf(query.trim()) else listOfNotNull(
            glyph?.id?.substringAfter(':')?.replace(Regex("-(outline|rounded|sharp|fill|filled|off|on|slash)$"), "")?.replace('-', ' '),
            (if (editingOn) toggle?.label else draft.label)?.trim(),
        ).filter { it.isNotBlank() }.distinct()
        val seed = seeds.firstOrNull().orEmpty()
        var found by remember { mutableStateOf<List<String>>(emptyList()) }
        var foundFor by remember { mutableStateOf("") }
        var looking by remember { mutableStateOf(false) }
        LaunchedEffect(seeds) {
            if (seeds.isEmpty()) { found = emptyList(); foundFor = ""; return@LaunchedEffect }
            delay(400)
            looking = true
            for (words in seeds) {
                val got = search(words).take(30)
                if (got.isNotEmpty() || words == seeds.last()) { found = got; foundFor = words; break }
            }
            looking = false
        }
        LinkField(query, { query = it }, "Find an icon: headphones, mic off, moon", leading = AppIcons.Search)
        Text(
            when {
                looking -> "Looking…"
                seed.isEmpty() -> "Type a few words to find an icon."
                found.isEmpty() -> "Nothing found for \"$foundFor\"."
                query.isBlank() -> "Ideas for \"$foundFor\""
                else -> "${found.size} icons"
            },
            color = TextTertiary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
        )
        val tint = glyphTone(glyph)
        val context = LocalContext.current
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            found.forEach { id ->
                val on = glyph?.id == id
                val (prefix, name) = id.split(':', limit = 2)
                val request = remember(id) {
                    ImageRequest.Builder(context).data("https://api.iconify.design/$prefix/$name.svg").decoderFactory(SvgDecoder.Factory()).build()
                }
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) tone.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                        .pressable(name.replace('-', ' ')) { setGlyph(LinkGlyph(id, glyph?.color)) },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(model = request, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
