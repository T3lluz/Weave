package com.t3lluz.weave

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.t3lluz.weave.data.update.AppUpdateNotifier
import com.t3lluz.weave.data.update.UpdateInstallActivity
import com.t3lluz.weave.data.update.updateAvailable
import com.t3lluz.weave.ui.components.AppUpdateSheet
import com.t3lluz.weave.ui.components.WhatsNewSheet
import com.t3lluz.weave.ui.screens.link.LinkScreen
import com.t3lluz.weave.ui.screens.settings.AiSettingsScreen
import com.t3lluz.weave.ui.screens.settings.SettingsScreen
import com.t3lluz.weave.ui.screens.settings.UpdatesScreen
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.WeaveTheme
import com.t3lluz.weave.data.link.parseInvite
import com.t3lluz.weave.ui.viewmodel.AppUpdateViewModel
import com.t3lluz.weave.ui.viewmodel.LinkViewModel
import dagger.hilt.android.AndroidEntryPoint

/** Where the app is: the remote, its settings, Settings → AI, or Settings → Updates. */
private enum class Place { Remote, Settings, Ai, Updates }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val updates: AppUpdateViewModel by viewModels()
    // the remote's own model (the screen gets the same one): a pairing link goes straight to it
    private val link: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updates.startListening()
        consumeUpdateIntent(intent)
        consumePairIntent(intent)
        // Weave opened on the build it just installed: say what it brought
        updates.handlePostUpdateLaunch(forceFromIntent = consumePostUpdateIntent(intent))
        setContent {
            WeaveTheme {
                // where Settings → AI goes back to: the remote (from the shortcut sheet) or Settings
                var place by rememberSaveable { mutableStateOf(Place.Remote) }
                var aiFrom by rememberSaveable { mutableStateOf(Place.Remote) }
                val updateState by updates.state.collectAsState()
                val showUpdate by updates.showDialog.collectAsState()
                val whatsNew by updates.whatsNew.collectAsState()
                BackHandler(enabled = place != Place.Remote) {
                    place = when (place) {
                        Place.Ai -> aiFrom
                        Place.Updates -> Place.Settings
                        else -> Place.Remote
                    }
                }
                Box {
                    AnimatedContent(
                        targetState = place,
                        transitionSpec = { fadeIn(AppMotion.fadeTween()) togetherWith fadeOut(AppMotion.fadeTween()) },
                        label = "place",
                    ) { here ->
                        when (here) {
                            Place.Remote -> LinkScreen(
                                // the remote is the first screen: back leaves the app as Android's back does
                                onNavigateBack = { moveTaskToBack(true) },
                                onAiSettings = { aiFrom = Place.Remote; place = Place.Ai },
                                onSettings = { place = Place.Settings },
                            )
                            Place.Settings -> SettingsScreen(
                                onNavigateBack = { place = Place.Remote },
                                onAi = { aiFrom = Place.Settings; place = Place.Ai },
                                onUpdates = { place = Place.Updates },
                                updates = updates,
                            )
                            Place.Ai -> AiSettingsScreen(onNavigateBack = { place = aiFrom })
                            Place.Updates -> UpdatesScreen(onNavigateBack = { place = Place.Settings }, viewModel = updates)
                        }
                    }
                    whatsNew?.let { info ->
                        if (!showUpdate) WhatsNewSheet(info, onDismiss = updates::dismissWhatsNew)
                    }
                    if (showUpdate && updateState.updateAvailable) {
                        AppUpdateSheet(
                            state = updateState,
                            currentVersionName = updates.currentVersionName,
                            onDismiss = { updates.dismissDialog(snooze = true) },
                            onUpdate = { info -> updates.update(info)?.let { runCatching { startActivity(it) } } },
                            onCancelDownload = updates::cancelDownload,
                            onRetryInstall = updates::retryInstall,
                            onReopenPrompt = { updates.reopenInstallPrompt() },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // back from Android's "install unknown apps" screen, the waiting update starts
        updates.checkOnResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeUpdateIntent(intent)
        consumePairIntent(intent)
        if (consumePostUpdateIntent(intent)) updates.handlePostUpdateLaunch(forceFromIntent = true)
    }

    /** The update notification: its tap opens the sheet, its Update action starts the update too. */
    /** A computer's pairing link (weave://pair?...) opened Weave: connect to it with its code. */
    private fun consumePairIntent(intent: Intent?) {
        val invite = parseInvite(intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString) ?: return
        intent?.data = null   // once: a rotation must not pair again with a code that is spent
        link.pair(invite)
    }

    private fun consumeUpdateIntent(intent: Intent?) {
        intent ?: return
        val start = intent.getBooleanExtra(AppUpdateNotifier.EXTRA_START_UPDATE, false)
        val show = start || intent.getBooleanExtra(AppUpdateNotifier.EXTRA_SHOW_UPDATE, false)
        if (!show) return
        intent.removeExtra(AppUpdateNotifier.EXTRA_START_UPDATE)
        intent.removeExtra(AppUpdateNotifier.EXTRA_SHOW_UPDATE)
        updates.openFromNotification(start)
    }

    private fun consumePostUpdateIntent(intent: Intent?): Boolean {
        intent ?: return false
        val force = intent.getBooleanExtra(UpdateInstallActivity.EXTRA_RELAUNCHED_AFTER_UPDATE, false) ||
            intent.getBooleanExtra(UpdateInstallActivity.EXTRA_SHOW_WHATS_NEW, false)
        intent.removeExtra(UpdateInstallActivity.EXTRA_RELAUNCHED_AFTER_UPDATE)
        intent.removeExtra(UpdateInstallActivity.EXTRA_SHOW_WHATS_NEW)
        return force
    }
}
