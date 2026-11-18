package com.t3lluz.weave

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.data.update.AppUpdateWorker
import com.t3lluz.weave.data.update.PackageReplacedReceiver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.t3lluz.weave.widget.ShortcutsWidget
import javax.inject.Inject

@HiltAndroidApp
class WeaveApp : Application() {
    @Inject lateinit var shortcuts: LinkShortcutStore

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        PackageReplacedReceiver.ensureChannel(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // every few hours, even with the app closed: a new release shows up as a notification
        scope.launch { AppUpdateWorker.schedule(this@WeaveApp) }
        // a shortcut made, changed, flipped or given its picture shows on the home screen's widgets too
        scope.launch {
            combine(shortcuts.shortcuts, shortcuts.icons) { list, icons -> list to icons }
                .drop(1).debounce(250).collect { ShortcutsWidget().updateAll(this@WeaveApp) }
        }
    }
}
