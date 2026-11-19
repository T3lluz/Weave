package com.t3lluz.weave.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.t3lluz.weave.data.link.LinkPrefs
import com.t3lluz.weave.roam.RoamHub
import com.t3lluz.weave.roam.RoamLine
import com.t3lluz.weave.roam.RoamPrefs
import com.t3lluz.weave.roam.RoamService
import com.t3lluz.weave.roam.RoamStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject

/** What the Labs screen shows of the earbuds lab: the computer's word on it, while the screen is open. */
data class RoamScreenState(
    val computer: String = "",
    /** The computer is answering. */
    val online: Boolean = false,
    val status: RoamStatus? = null,
    /** Why nothing can be shown or changed: no computer picked, or one whose Weave can't do this. */
    val trouble: String? = null,
    /** The computer's last word on something asked for here, such as why it couldn't. */
    val said: String? = null,
)

@HiltViewModel
class RoamViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val linkPrefs: LinkPrefs,
    private val roamPrefs: RoamPrefs,
) : ViewModel(), RoamLine.Listener {
    private val _state = MutableStateFlow(RoamScreenState())
    val state: StateFlow<RoamScreenState> = _state
    val settings = roamPrefs.settings
    val player = RoamHub.player

    private var line: RoamLine? = null

    /** A change made here that the computer hasn't heard yet: it goes first when the line is up. */
    @Volatile private var unsent: JSONObject? = null

    /** The screen came up: ask the computer where the earbuds are, and keep hearing. */
    fun open() {
        if (line != null) return
        // the lab's own computer, or with none yet, the one picked in the remote
        if (roamPrefs.settings.value.key.isBlank()) roamPrefs.adopt(linkPrefs)
        _state.update { RoamScreenState(computer = roamPrefs.settings.value.name) }
        line = RoamLine(context, linkPrefs, roamPrefs, play = false, listener = this).also { it.start() }
    }

    fun close() {
        line?.stop()
        line = null
        _state.update { it.copy(online = false) }
    }

    override fun online(on: Boolean) {
        _state.update { it.copy(online = on, trouble = if (on) null else it.trouble) }
        if (!on) return
        val message = JSONObject().put("t", "roam").put("watch", true)
        unsent?.let { message.put("set", it) }
        if (line?.send(message) == true) unsent = null
    }

    override fun status(status: RoamStatus, release: Boolean) {
        _state.update { it.copy(status = status) }
        // the computer's word stands, unless a change made here is still on its way to it
        if (unsent == null) {
            roamPrefs.update { it.copy(on = status.on, mode = status.mode, sens = status.sens, earbuds = status.devices.ifEmpty { it.earbuds }) }
        }
    }

    override fun said(text: String) {
        _state.update { it.copy(said = text) }
    }

    override fun refused(why: String) {
        _state.update { it.copy(online = false, trouble = why) }
    }

    fun setOn(on: Boolean) {
        if (on) {
            // turned on for the computer picked in the remote now, if another was the lab's before
            val before = roamPrefs.settings.value.key
            if (roamPrefs.adopt(linkPrefs) && roamPrefs.settings.value.key != before) {
                close()
                roamPrefs.update { it.copy(on = true, earbuds = emptyList()) }
                unsent = JSONObject().put("on", true)
                open()
                return
            }
        }
        roamPrefs.update { it.copy(on = on) }
        change(JSONObject().put("on", on))
        // (a line that gave up for want of a computer tries again now that there may be one)
        if (on && _state.value.trouble != null) {
            close()
            open()
        }
    }

    /** `auto`: the earbuds change hands by themselves. `manual`: only when asked to here. */
    fun setMode(mode: String) {
        roamPrefs.update { it.copy(mode = mode) }
        change(JSONObject().put("mode", mode))
    }

    fun setSens(sens: String) {
        roamPrefs.update { it.copy(sens = sens) }
        change(JSONObject().put("sens", sens))
    }

    private fun change(what: JSONObject) {
        val all = unsent ?: JSONObject()
        what.keys().forEach { all.put(it, what.get(it)) }
        unsent = all
        if (line?.send(JSONObject().put("t", "roam").put("set", all)) == true) unsent = null
    }

    /** Hands the earbuds to this phone now, wherever the link's strength is. */
    fun moveHere() {
        _state.update { it.copy(said = null) }
        line?.send(JSONObject().put("t", "roam").put("now", true))
    }

    fun backToComputer() {
        _state.update { it.copy(said = null) }
        line?.send(JSONObject().put("t", "roam").put("back", true))
    }

    /** With the earbuds already on this phone: play the computer's sound through them. */
    fun listenHere(address: String) = RoamService.start(context, RoamService.ACTION_LISTEN, address)

    override fun onCleared() = close()
}
