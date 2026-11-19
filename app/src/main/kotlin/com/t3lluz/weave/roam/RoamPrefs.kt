package com.t3lluz.weave.roam

import android.content.Context
import androidx.core.content.edit
import com.t3lluz.weave.data.link.LinkConfig
import com.t3lluz.weave.data.link.LinkPrefs
import com.t3lluz.weave.data.link.LinkProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone remembers of the lab between runs. */
data class RoamSettings(
    /** The lab is on: this phone plays the computer's sound when its earbuds connect here. */
    val on: Boolean = false,
    /** `auto` or `manual`: whether the earbuds change hands by themselves or only at a tap. */
    val mode: String = "auto",
    val sens: String = "balanced",
    /** The earbuds the computer plays through. Only these start the player. */
    val earbuds: List<RoamEarbuds> = emptyList(),
    /**
     * The computer whose sound follows: the one that was picked when the lab was turned on. Kept
     * here because the remote lets go of its computer whenever its list is shown, and the
     * player has to find this one with Weave closed. [key] is what its token is made from.
     */
    val host: String = "",
    val port: Int = LinkProtocol.PORT,
    val name: String = "",
    val ticket: String = "",
    val key: String = "",
)

/**
 * Kept on the phone, and not only on the computer, because the player has to decide with the
 * app closed: a pair of earbuds connects, and Android asks Weave whether that is its business.
 */
@Singleton
class RoamPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("roam", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read(context))
    val settings: StateFlow<RoamSettings> = _settings

    fun update(change: (RoamSettings) -> RoamSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit {
            putBoolean("on", next.on)
            putString("mode", next.mode)
            putString("sens", next.sens)
            putString("earbuds", RoamStatus.toJson(next.earbuds))
            putString("host", next.host)
            putInt("port", next.port)
            putString("name", next.name)
            putString("ticket", next.ticket)
            putString("key", next.key)
        }
    }

    /**
     * The lab's computer becomes the one picked in the remote now, if one is. True when the lab
     * has a computer after that.
     */
    fun adopt(link: LinkPrefs): Boolean {
        val picked = link.config.value
        if (picked.host.isNotBlank() && picked.key.isNotBlank()) {
            update { it.copy(host = picked.host, port = picked.port, name = picked.name, ticket = picked.ticket, key = picked.key) }
        }
        return _settings.value.key.isNotBlank()
    }

    /**
     * Where the lab's computer is reached now, or null when the lab has none. Its addresses are
     * the freshest this phone has: the remote's, when it is the computer picked there, else what
     * the phone's own list says, else what was saved here.
     */
    fun computer(link: LinkPrefs): LinkConfig? {
        val mine = _settings.value
        if (mine.key.isBlank() || mine.host.isBlank()) return null
        val picked = link.config.value.takeIf { it.key == mine.key && it.host.isNotBlank() }
        val known = link.known.value.firstOrNull { it.key == mine.key }
        val host = picked?.host ?: known?.host ?: mine.host
        val port = picked?.port ?: known?.port ?: mine.port
        if (host != mine.host || port != mine.port) update { it.copy(host = host, port = port) }
        return LinkConfig(host = host, port = port, name = mine.name, ticket = picked?.ticket ?: known?.ticket ?: mine.ticket, key = mine.key)
    }

    /** Whether [address] is a pair this phone plays the computer's sound for. */
    fun knows(address: String?): Boolean = address != null && _settings.value.earbuds.any { it.address.equals(address, ignoreCase = true) }

    companion object {
        /** For the receiver, which runs before anything is injected. */
        fun read(context: Context): RoamSettings {
            val p = context.getSharedPreferences("roam", Context.MODE_PRIVATE)
            return RoamSettings(
                on = p.getBoolean("on", false), mode = p.getString("mode", "auto") ?: "auto", sens = p.getString("sens", "balanced") ?: "balanced",
                earbuds = RoamStatus.earbudsFrom(p.getString("earbuds", null)),
                host = p.getString("host", "").orEmpty(), port = p.getInt("port", LinkProtocol.PORT), name = p.getString("name", "").orEmpty(),
                ticket = p.getString("ticket", "").orEmpty(), key = p.getString("key", "").orEmpty(),
            )
        }
    }
}
