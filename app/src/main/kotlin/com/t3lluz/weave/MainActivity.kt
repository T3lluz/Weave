package com.t3lluz.weave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.t3lluz.weave.ui.screens.link.LinkScreen
import com.t3lluz.weave.ui.screens.settings.AiSettingsScreen
import com.t3lluz.weave.ui.screens.settings.SettingsScreen
import com.t3lluz.weave.ui.theme.AppMotion
import com.t3lluz.weave.ui.theme.WeaveTheme
import dagger.hilt.android.AndroidEntryPoint

/** Where the app is: the remote, its settings, or Settings → AI. */
private enum class Place { Remote, Settings, Ai }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeaveTheme {
                // where Settings → AI goes back to: the remote (from the shortcut sheet) or Settings
                var place by rememberSaveable { mutableStateOf(Place.Remote) }
                var aiFrom by rememberSaveable { mutableStateOf(Place.Remote) }
                BackHandler(enabled = place != Place.Remote) {
                    place = if (place == Place.Ai) aiFrom else Place.Remote
                }
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
                        )
                        Place.Ai -> AiSettingsScreen(onNavigateBack = { place = aiFrom })
                    }
                }
            }
        }
    }
}
