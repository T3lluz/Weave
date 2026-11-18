package com.t3lluz.weave.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.t3lluz.weave.MainActivity
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.ui.components.PillButton
import com.t3lluz.weave.ui.components.SquircleShape
import com.t3lluz.weave.ui.screens.health.HealthDisplay
import com.t3lluz.weave.ui.screens.link.SHORTCUT_H
import com.t3lluz.weave.ui.screens.link.ShortcutTile
import com.t3lluz.weave.ui.screens.link.TileFx
import com.t3lluz.weave.ui.screens.link.pressable
import com.t3lluz.weave.ui.theme.AppIcons
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import com.t3lluz.weave.ui.theme.SurfaceChrome
import com.t3lluz.weave.ui.theme.TextPrimary
import com.t3lluz.weave.ui.theme.TextSecondary
import com.t3lluz.weave.ui.theme.TextTertiary
import com.t3lluz.weave.ui.theme.WeaveTheme
import com.t3lluz.weave.ui.util.rememberHaptics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which shortcuts a home screen widget shows: two to eight, tapped in the order they should
 * stand, with the widget drawn above as it will look. It opens three ways: from Weave, for a new
 * widget ([newWidget]: Done asks the home screen to add one); from the home screen's own widget
 * list, when one is dropped; and from a widget's edit button.
 */
@AndroidEntryPoint
class ShortcutsWidgetConfigActivity : ComponentActivity() {
    @Inject lateinit var store: LinkShortcutStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgets = AppWidgetManager.getInstance(this)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val fresh = widgetId == AppWidgetManager.INVALID_APPWIDGET_ID
        // any app can open this screen with any number: only a widget of Weave's own is picked for
        if (!fresh && widgets.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, ShortcutsWidgetReceiver::class.java)) {
            finish()
            return
        }
        // backing out of a widget being dropped on the home screen leaves no widget
        if (!fresh) setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // the widget's own shape, for the picture of it; a new one starts 4 × 2
        val shape = if (fresh) null else widgets.getAppWidgetOptions(widgetId).let { o ->
            // upright, a widget is as narrow and as tall as it gets
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w > 0 && h > 0) w.toFloat() to h.toFloat() else null
        }
        val (widthDp, heightDp) = shape ?: (DEFAULT_W_DP to DEFAULT_H_DP)

        setContent {
            WeaveTheme {
                val all by store.shortcuts.collectAsState()
                val icons by store.icons.collectAsState()
                val scope = rememberCoroutineScope()
                val haptics = rememberHaptics()
                var picked by remember { mutableStateOf<List<String>?>(null) }
                // what the widget holds now; a new one starts with the shelf's first eight
                LaunchedEffect(Unit) {
                    val saved = if (fresh) null else {
                        val glanceId = GlanceAppWidgetManager(this@ShortcutsWidgetConfigActivity).getGlanceIdBy(widgetId)
                        getAppWidgetState(this@ShortcutsWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)[ShortcutsWidget.PICKED]
                    }
                    picked = saved?.split(',')?.filter { id -> all.any { it.id == id } }?.take(MAX_SHORTCUTS)?.takeIf { it.isNotEmpty() }
                        ?: all.take(MAX_SHORTCUTS).map { it.id }
                }
                val chosen = picked.orEmpty()
                val shown = remember(chosen, all) { chosen.mapNotNull { id -> all.firstOrNull { it.id == id } } }
                // one shortcut is all there is: it may stand alone
                val least = minOf(MIN_SHORTCUTS, all.size)
                val enough = chosen.size >= least

                fun done() {
                    if (!enough) {
                        haptics.reject()
                        return
                    }
                    val ids = chosen.joinToString(",")
                    if (fresh) {
                        // the home screen asks where it goes; the picks ride along and land when it says yes
                        val landed = PendingIntent.getBroadcast(
                            this, 0, Intent(this, WidgetPinnedReceiver::class.java).putExtra(WidgetPinnedReceiver.EXTRA_PICKED, ids),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                        widgets.requestPinAppWidget(ComponentName(this, ShortcutsWidgetReceiver::class.java), null, landed)
                        finish()
                        return
                    }
                    scope.launch {
                        savePicks(this@ShortcutsWidgetConfigActivity, widgetId, ids)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }

                Column(Modifier.fillMaxSize().background(Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(22.dp))
                    Text("Widget shortcuts", style = HealthDisplay, fontSize = 28.sp, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (all.isEmpty()) "There are no shortcuts yet. Make one in Weave first."
                        else "Pick two to eight, in the order you want them. They fill the widget: the fewer, the bigger.",
                        color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    if (all.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        WidgetPicture(shown, store, icons, widthDp, heightDp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                chosen.size >= MAX_SHORTCUTS -> "${chosen.size} of $MAX_SHORTCUTS: full"
                                enough -> "${chosen.size} of $MAX_SHORTCUTS"
                                else -> "Pick at least $least"
                            },
                            color = if (enough) TextTertiary else LinkShortcutTone, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(all, key = { it.id }) { s ->
                            val at = chosen.indexOf(s.id)
                            val fx = remember { TileFx() }
                            val fade by animateFloatAsState(if (at >= 0) 1f else 0.42f, AppMotion.Link.press(), label = "picked")
                            Box(
                                Modifier
                                    .pressable(if (at >= 0) "Take ${s.label} off the widget" else "Put ${s.label} on the widget") {
                                        when {
                                            at >= 0 -> picked = chosen - s.id
                                            chosen.size < MAX_SHORTCUTS -> picked = chosen + s.id
                                            else -> haptics.reject()   // eight is what a widget holds
                                        }
                                    }
                                    .height(SHORTCUT_H),
                            ) {
                                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }) { ShortcutTile(s, store, icons, fx) }
                                if (at >= 0) {
                                    // its place on the widget
                                    Box(
                                        Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp).clip(CircleShape).background(LinkShortcutTone),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${at + 1}", color = Background, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
                        if (all.isEmpty()) {
                            PillButton(icon = AppIcons.Bolt, label = "Open Weave", accent = LinkShortcutTone, emphasized = true, onClick = {
                                startActivity(Intent(this@ShortcutsWidgetConfigActivity, MainActivity::class.java))
                                finish()
                            })
                        } else {
                            val ready by animateFloatAsState(if (enough) 1f else 0.45f, AppMotion.Link.press(), label = "ready")
                            PillButton(
                                icon = AppIcons.Check, label = if (fresh) "Add to the home screen" else "Done", accent = LinkShortcutTone,
                                emphasized = true, onClick = ::done, modifier = Modifier.graphicsLayer { alpha = ready },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** A 4 × 2 widget on a Pixel, when there is no widget yet to measure. */
        private const val DEFAULT_W_DP = 373f
        private const val DEFAULT_H_DP = 200f

        /** Opens the picker for a widget that doesn't exist yet. */
        fun newWidget(context: Context) = context.startActivity(Intent(context, ShortcutsWidgetConfigActivity::class.java))

        internal suspend fun savePicks(context: Context, widgetId: Int, ids: String) {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
            updateAppWidgetState(context, glanceId) { it[ShortcutsWidget.PICKED] = ids }
            ShortcutsWidget().update(context, glanceId)
        }
    }
}

/** The widget as it will look with [shown] on it, laid out by the same rule the real one uses. */
@Composable
private fun WidgetPicture(shown: List<LinkShortcut>, store: LinkShortcutStore, icons: Int, widthDp: Float, heightDp: Float) {
    val layout = widgetLayout(shown.size.coerceAtLeast(1), widthDp, heightDp)
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio((widthDp / heightDp).coerceIn(0.6f, 4.2f))
            .clip(SquircleShape(28.dp))
            .background(SurfaceChrome)
            .padding(PAD_DP.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing picked yet", color = TextTertiary, fontSize = 13.sp)
            }
            return@Column
        }
        widgetRows(shown, layout).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                row.forEach { s ->
                    key(s.id) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { ShortcutTile(s, store, icons, remember { TileFx() }) }
                    }
                }
            }
        }
    }
}

/** The home screen added the widget asked for from Weave: the shortcuts picked for it go on it. */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val ids = intent.getStringExtra(EXTRA_PICKED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || ids == null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ShortcutsWidgetConfigActivity.savePicks(context.applicationContext, widgetId, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PICKED = "picked"
    }
}
