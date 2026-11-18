package com.t3lluz.weave

import android.app.Application
import com.t3lluz.weave.data.update.AppUpdateWorker
import com.t3lluz.weave.data.update.PackageReplacedReceiver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class WeaveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PackageReplacedReceiver.ensureChannel(this)
        // every few hours, even with the app closed: a new release shows up as a notification
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { AppUpdateWorker.schedule(this@WeaveApp) }
    }
}
