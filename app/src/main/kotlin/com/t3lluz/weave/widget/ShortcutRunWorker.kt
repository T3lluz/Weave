package com.t3lluz.weave.widget

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.t3lluz.weave.data.link.RunOutcome
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs one shortcut for the widget: reaches the laptop, does the steps, flips a two-look
 * shortcut to its other look, and tells the tile how it went. A worker, because a shortcut with
 * waits in it can take longer than a tap on a widget is given.
 */
class ShortcutRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val widget = ShortcutsWidget()
        // Stopped once and started again by the system: the shortcut may have run already, in part
        // or whole. A key pressed twice is worse than one not pressed, so it isn't run again.
        if (runAttemptCount > 0) {
            WidgetRuns.set(id, null)
            widget.updateAll(applicationContext)
            return Result.success()
        }
        val deps = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
        val store = deps.shortcutStore()
        try {
            val shortcut = store.shortcuts.value.firstOrNull { it.id == id }
            val outcome = try {
                if (shortcut == null) RunOutcome.Failed("That shortcut is gone.") else deps.oneShot().run(shortcut.steps)
            } catch (e: CancellationException) {
                throw e   // the system is stopping this work: not a failure to show
            } catch (e: Exception) {
                RunOutcome.Failed("Couldn't run it.")
            }
            when (outcome) {
                RunOutcome.Done -> {
                    // the look it is in now, not the one it had when the tap landed
                    store.shortcuts.value.firstOrNull { it.id == id }?.takeIf { it.toggle != null }?.let { store.save(it.copy(on = !it.on)) }
                    WidgetRuns.set(id, RunPhase.Done)
                }
                is RunOutcome.Failed -> {
                    WidgetRuns.set(id, RunPhase.Failed)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, outcome.why, Toast.LENGTH_SHORT).show() }
                }
            }
            widget.updateAll(applicationContext)
            delay(if (outcome == RunOutcome.Done) DONE_SHOWS_MS else FAILED_SHOWS_MS)
        } finally {
            // however this ends, the tile must not be left spinning
            WidgetRuns.set(id, null)
            withContext(NonCancellable) { widget.updateAll(applicationContext) }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val QUEUE = "weave-widget-runs"
        const val DONE_SHOWS_MS = 650L
        const val FAILED_SHOWS_MS = 1_800L

        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ShortcutRunWorker>().setInputData(workDataOf(KEY_ID to id)).apply {
                // at once, not when the system next gets round to it; before Android 12 that would
                // need a notification, and plain work starts promptly enough there
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()
            // One after another, never side by side: two lines from the same phone make the computer
            // drop the first, cutting a shortcut off partway.
            WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
