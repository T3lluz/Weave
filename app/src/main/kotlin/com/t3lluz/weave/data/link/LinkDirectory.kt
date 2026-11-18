package com.t3lluz.weave.data.link

import com.t3lluz.weave.data.local.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A laptop running tandem, as the dashboard lists it. */
data class LinkHost(
    val id: String,
    val name: String,
    /** Where it listens, best first: its tailnet address, then its name. */
    val addresses: List<String>,
    val port: Int,
    /**
     * Shown to the laptop on connecting; it lets this phone in without asking its owner. The
     * dashboard's public list carries none, so for now the laptop asks once and remembers.
     */
    val ticket: String,
    val online: Boolean,
    /** A phone is controlling it right now. */
    val busy: Boolean,
)

data class LinkDirectoryState(
    val hosts: List<LinkHost> = emptyList(),
    /** False until the dashboard has answered once, so "none found" is not shown while asking. */
    val loaded: Boolean = false,
    /** Why the list could not be read, in words for the screen; null when it could. */
    val problem: String? = null,
)

/**
 * Finds laptops without typing anything: each one announces itself to the dashboard's bridge
 * (`/_api/link`, laptop_link.py there) and this reads who is around. It reads the list the
 * dashboard's own page draws, which has no tickets (those go only to DailyDash, the phone paired
 * with the dashboard), so the first time this phone connects the laptop asks its owner.
 */
@Singleton
class LinkDirectory @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settings: SettingsRepository,
) {
    internal val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .apply { interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor } }
        .build()

    suspend fun laptops(): LinkDirectoryState = withContext(Dispatchers.IO) {
        val base = settings.dashboardServerUrl.value.trim().trimEnd('/')
        if (base.isBlank()) return@withContext LinkDirectoryState(loaded = true, problem = "No dashboard is set up, so laptops can't announce themselves.")
        try {
            val request = Request.Builder().url("$base/_api/link").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> LinkDirectoryState(loaded = true, problem = "The dashboard answered ${response.code}.")
                    else -> LinkDirectoryState(hosts = parseLaptops(JSONObject(body), ownAddresses()), loaded = true)
                }
            }
        } catch (e: IOException) {
            LinkDirectoryState(loaded = true, problem = "The dashboard didn't answer.")
        } catch (e: org.json.JSONException) {
            LinkDirectoryState(loaded = true, problem = "The dashboard's answer made no sense.")
        }
    }
}

/** This phone's own addresses, its tailnet one among them. */
internal fun ownAddresses(): Set<String> = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .toSet()
} catch (_: Exception) {
    emptySet()
}

/**
 * The bridge's answer as laptops. One without an address is left out: there is nowhere to
 * connect to. A laptop is busy while a phone holds a session there, unless every session is
 * from [mine], this phone's own addresses: a session it left a moment ago, not yet gone.
 */
internal fun parseLaptops(o: JSONObject, mine: Set<String> = emptySet()): List<LinkHost> = buildList {
    val array = o.optJSONArray("hosts") ?: return@buildList
    for (i in 0 until array.length()) {
        val h = array.optJSONObject(i) ?: continue
        val addresses = h.optJSONArray("addrs")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        if (addresses.isEmpty()) continue
        val sessions = h.optJSONObject("state")?.optJSONArray("sessions")
        val peers = sessions?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it)?.optString("peer")?.ifBlank { null } } }.orEmpty()
        val busy = h.optBoolean("busy", (sessions?.length() ?: 0) > 0) && !(peers.isNotEmpty() && peers.all { it in mine })
        add(
            LinkHost(
                id = h.optString("id"), name = h.optString("name").ifBlank { addresses.last() },
                addresses = addresses, port = h.optInt("port", LinkProtocol.PORT),
                ticket = h.optString("ticket"), online = h.optBoolean("online"), busy = busy,
            ),
        )
    }
}
