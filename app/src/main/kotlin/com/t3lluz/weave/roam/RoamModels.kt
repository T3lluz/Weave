package com.t3lluz.weave.roam

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A pair of earbuds the computer has played through: its Bluetooth address and what it is called. */
data class RoamEarbuds(val address: String, val name: String)

/**
 * Where the earbuds are, as the computer tells it ("roam" in computer/weave/roam.py).
 * [state] is `off`, `idle` (no earbuds are the computer's output), `armed` (they are, and their
 * link is watched), `handing`, `waiting`, `bridged` (this phone has them and plays the
 * computer's sound) or `returning`.
 */
data class RoamStatus(
    val on: Boolean = false,
    /** `auto`: the link's strength decides when the earbuds change hands. `manual`: only a tap does. */
    val mode: String = "auto",
    /** How early the phone takes over: `early`, `balanced` or `late`. */
    val sens: String = "balanced",
    val state: String = "off",
    val device: RoamEarbuds? = null,
    /** Every pair the computer has had as its output: the ones this phone plays for. */
    val devices: List<RoamEarbuds> = emptyList(),
    /** The link's strength while the earbuds are on the computer, and where it counts as too weak. */
    val signal: Double? = null,
    val limit: Double = 0.0,
    /** The strength is in dBm; otherwise it is how far under the controller's good range (0 is fine). */
    val dbm: Boolean = false,
    /** The computer can make this phone take the earbuds by itself (its helper runs here over adb). */
    val auto: Boolean = false,
    val autoWhy: String = "",
    /** What this phone sends out over Bluetooth while it has the earbuds, for the computer to hear it near. */
    val beacon: String? = null,
    val why: String = "",
) {
    companion object {
        fun from(o: JSONObject): RoamStatus = RoamStatus(
            on = o.optBoolean("on"),
            mode = if (o.optString("mode") == "manual") "manual" else "auto",
            sens = o.optString("sens", "balanced"),
            state = o.optString("state", "off"),
            device = o.optJSONObject("device")?.let(::earbuds),
            devices = o.optJSONArray("devices")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::earbuds) } }.orEmpty(),
            signal = if (o.isNull("signal")) null else o.optDouble("signal").takeIf { !it.isNaN() },
            limit = o.optDouble("limit", 0.0),
            dbm = o.optBoolean("dbm"),
            auto = o.optBoolean("auto"),
            autoWhy = o.optString("autoWhy"),
            beacon = o.optString("beacon").takeIf { !o.isNull("beacon") && it.isNotBlank() },
            why = o.optString("why"),
        )

        private fun earbuds(o: JSONObject): RoamEarbuds? {
            val address = o.optString("addr").uppercase()
            return if (address.isBlank()) null else RoamEarbuds(address, o.optString("name").ifBlank { "Earbuds" })
        }

        fun toJson(list: List<RoamEarbuds>): String =
            JSONArray(list.map { JSONObject().put("addr", it.address).put("name", it.name) }).toString()

        fun earbudsFrom(json: String?): List<RoamEarbuds> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::earbuds) }
        }.getOrDefault(emptyList())
    }
}

/** The bytes of a beacon the computer gave as hex; null for anything that isn't one. */
internal fun beaconBytes(hex: String?): ByteArray? {
    if (hex == null || hex.length !in 2..32 || hex.length % 2 != 0) return null
    return runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}

/** What the player is doing, for the screen and the notification. */
data class RoamPlayer(
    val running: Boolean = false,
    /** The earbuds are on this phone and the computer's sound plays through them. */
    val playing: Boolean = false,
    val online: Boolean = false,
    val earbuds: String = "",
    val note: String = "",
)

/**
 * What the player and the screens share. A plain object and not an injected one: the remote's
 * own line reads [playing] on its reading thread, for every piece of sound.
 */
object RoamHub {
    /** The player has the computer's sound: the remote leaves it alone, or it would play twice. */
    @Volatile var playing = false

    val player = MutableStateFlow(RoamPlayer())
}
