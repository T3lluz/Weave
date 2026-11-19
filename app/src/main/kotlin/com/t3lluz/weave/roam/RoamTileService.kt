package com.t3lluz.weave.roam

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.t3lluz.weave.data.link.LinkPrefs
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import javax.inject.Inject

/**
 * The Earbuds tile in quick settings: a tap moves the earbuds from the computer to this phone,
 * and a tap while they are here sends them back. The same two things the Labs screen's
 * buttons do, without opening Weave.
 */
@AndroidEntryPoint
class RoamTileService : TileService() {
    @Inject lateinit var linkPrefs: LinkPrefs
    @Inject lateinit var roamPrefs: RoamPrefs
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() = show()

    override fun onClick() {
        if (!roamPrefs.settings.value.on) {
            say("Turn it on in Weave first: Settings, Labs.")
            return
        }
        if (RoamHub.player.value.running) {
            if (!RoamService.askBack()) say("The computer isn't answering.")
        } else {
            ask(JSONObject().put("t", "roam").put("now", true))
        }
        main.postDelayed({ show() }, 1_500)
    }

    /** One question to the computer over a line of its own, which ends a moment after it is asked. */
    private fun ask(message: JSONObject) {
        var line: RoamLine? = null
        val end = Runnable { line?.stop() }
        line = RoamLine(
            applicationContext, linkPrefs, roamPrefs, play = false,
            listener = object : RoamLine.Listener {
                override fun online(on: Boolean) {
                    if (on) line?.send(message)
                }

                override fun said(text: String) = say(text)

                override fun refused(why: String) = say(why)
            },
        ).also { it.start() }
        main.postDelayed(end, 5_000)
    }

    private fun say(text: String) {
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    private fun show() {
        val tile = qsTile ?: return
        val here = RoamHub.player.value.running
        tile.state = when {
            !roamPrefs.settings.value.on -> Tile.STATE_UNAVAILABLE
            here -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (here) "On this phone" else "Move to this phone"
        tile.updateTile()
    }
}
