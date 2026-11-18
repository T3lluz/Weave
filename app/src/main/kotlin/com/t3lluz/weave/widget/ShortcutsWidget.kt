package com.t3lluz.weave.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.t3lluz.weave.MainActivity
import com.t3lluz.weave.data.link.LinkOneShot
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.Error
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Shortcuts on the home screen: a tap runs one on the laptop without opening Weave. It holds the
 * two to eight picked for it ([ShortcutsWidgetConfigActivity]), in the order picked, laid out to
 * fill it ([widgetLayout]): fewer shortcuts, bigger tiles. A tile wears its shortcut's colour,
 * shows the look a two-look one is in, spins while it runs and goes red for a moment when the
 * laptop can't be reached.
 */
class ShortcutsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        val store = EntryPointAccessors.fromApplication(app, WidgetEntryPoint::class.java).shortcutStore()
        val density = app.resources.displayMetrics.density
        provideContent {
            val all by store.shortcuts.collectAsState()
            val icons by store.icons.collectAsState()
            val runs by WidgetRuns.state.collectAsState()
            val picked = currentState(PICKED).orEmpty().split(',').filter { it.isNotEmpty() }
            val size = LocalSize.current
            val shown = widgetPicks(all, picked) { it.id }
            val layout = widgetLayout(shown.size, size.width.value, size.height.value)
            val facePx = (layout.faceDp * density).toInt()
            val faces by produceState(emptyMap<String, WidgetFace>(), shown, icons, facePx) {
                value = withContext(Dispatchers.IO) { shown.associate { it.id to WidgetFaces.face(app, store, it, facePx) } }
            }
            Column(
                // the gaps are padding round each tile (a row may hold ten things at most, and eight tiles
                // with spacers between them are fifteen), so the panel's own edge gives up half a gap
                GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(PANEL_RADIUS.dp).background(Background).padding((PAD_DP - GAP_DP / 2).dp),
            ) {
                if (shown.isEmpty()) {
                    Empty()
                } else {
                    // each row shares the width between its own tiles: five are three over two wider ones
                    widgetRows(shown, layout).forEach { row ->
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            row.forEach { s ->
                                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding((GAP_DP / 2).dp)) { Tile(s, faces[s.id], runs[s.id], layout) }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** The shortcuts picked for one widget, their ids joined by commas, in order. */
        val PICKED = stringPreferencesKey("picked")
        val SHORTCUT_ID = ActionParameters.Key<String>("shortcut")
        const val PANEL_RADIUS = 28
        const val TILE_RADIUS = 20
    }
}

@Composable
private fun Tile(s: LinkShortcut, face: WidgetFace?, phase: RunPhase?, layout: WidgetLayout) {
    val tone = Color(face?.tone ?: LinkShortcutTone.toArgb())
    val lit = s.toggle != null && s.on
    val fill = when (phase) {
        RunPhase.Running -> lerp(Background, tone, 0.26f)
        RunPhase.Done -> lerp(Background, tone, 0.42f)
        RunPhase.Failed -> lerp(Background, Error, 0.34f)
        null -> lerp(Background, tone, if (lit) 0.34f else 0.13f)
    }
    Box(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(ShortcutsWidget.TILE_RADIUS.dp)
            .background(fill)
            .clickable(actionRunCallback<RunShortcutAction>(actionParametersOf(ShortcutsWidget.SHORTCUT_ID to s.id))),
        contentAlignment = Alignment.Center,
    ) {
        Column(GlanceModifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val faceSize = GlanceModifier.size(layout.faceDp.dp)
            when {
                phase == RunPhase.Running -> CircularProgressIndicator(faceSize, color = ColorProvider(tone))
                // a tile too small for its name still says it to a screen reader
                face != null -> Image(ImageProvider(face.picture), contentDescription = if (layout.labels) null else s.shownLabel, modifier = faceSize)
                else -> Spacer(faceSize)
            }
            if (layout.labels) {
                Spacer(GlanceModifier.height(if (layout.bigText) 7.dp else 5.dp))
                Text(
                    s.shownLabel.ifBlank { "Shortcut" },
                    style = TextStyle(
                        color = ColorProvider(TextPrimary), fontSize = if (layout.bigText) 13.sp else 11.sp,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** No shortcuts yet: the widget says where they come from, and opens Weave. */
@Composable
private fun Empty() {
    Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
        Text(
            "Make a shortcut in Weave and it shows up here",
            style = TextStyle(color = ColorProvider(TextSecondary), fontSize = 13.sp, textAlign = TextAlign.Center),
            modifier = GlanceModifier.padding(12.dp),
        )
    }
}

class ShortcutsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutsWidget()
}

/** A tap on a tile: it spins at once, and the run goes to a worker, which outlives the tap. */
class RunShortcutAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ShortcutsWidget.SHORTCUT_ID] ?: return
        // a second tap while it runs would run it twice (and flip a two-look one back)
        if (!WidgetRuns.begin(id)) return
        ShortcutsWidget().updateAll(context)
        ShortcutRunWorker.enqueue(context, id)
    }
}

internal enum class RunPhase { Running, Done, Failed }

/** What each tile is doing right now, for every widget on the home screen. */
internal object WidgetRuns {
    private val _state = MutableStateFlow<Map<String, RunPhase>>(emptyMap())
    val state: StateFlow<Map<String, RunPhase>> = _state

    /** False when that shortcut is already running. */
    fun begin(id: String): Boolean {
        var began = false
        _state.update { now ->
            began = now[id] != RunPhase.Running
            if (began) now + (id to RunPhase.Running) else now
        }
        return began
    }

    fun set(id: String, phase: RunPhase?) = _state.update { if (phase == null) it - id else it + (id to phase) }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun shortcutStore(): LinkShortcutStore
    fun oneShot(): LinkOneShot
}
