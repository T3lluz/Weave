package com.t3lluz.weave.data.link

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An invitation from a computer: what `weave pair` there puts in its QR code. The code lets
 * this phone in once without the computer asking its owner; the addresses are every way the
 * computer can be reached, tried in order.
 */
data class LinkInvite(
    val name: String, val addresses: List<String>, val port: Int, val code: String,
    /** The computer's own id, as its welcome gives it too; blank from a computer that doesn't say. */
    val id: String = "",
)

/**
 * Reads an invitation from what a scan or a tapped link gives: the page's address with the
 * invitation after `#` (`https://t3lluz.com/weave/pair#n=..&a=..&p=..&c=..`), or the app's own
 * `weave://pair?..`. Null for anything else, or one with no address or no code.
 */
fun parseInvite(text: String?): LinkInvite? {
    val raw = text?.trim().orEmpty()
    val isLink = raw.startsWith("weave://pair") || Regex("^https?://[^/]+/weave/pair([/?#].*)?$").matches(raw)
    if (!isLink) return null
    val query = raw.substringAfter('#', "").ifEmpty { raw.substringAfter('?', "") }
    val fields = query.split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "")
        if (key.isEmpty() || '=' !in part) null
        else key to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrDefault("")
    }.toMap()
    val addresses = fields["a"].orEmpty().split(',').map { it.trim() }.filter { ADDRESS.matches(it) }
    val code = fields["c"].orEmpty()
    // the code is what the computer made: letters, digits, - and _. Anything else is not one of its
    if (addresses.isEmpty() || !CODE.matches(code)) return null
    val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: LinkProtocol.PORT
    return LinkInvite(
        fields["n"].orEmpty().take(60).ifBlank { addresses.last() }, addresses, port, code,
        id = fields["i"].orEmpty().takeIf { CODE.matches(it) }.orEmpty(),
    )
}

/**
 * The computer of [known] that [invite] is for, when this phone has it already: pairing it again
 * would only make a second entry for the same machine.
 *
 * By the computer's own id when both say one. Where one of them doesn't (a computer paired
 * before ids, or by its address), by an address and the port they share. Two with different
 * ids are two computers, whatever addresses they share: home networks hand out the same ones.
 */
internal fun alreadyKnown(invite: LinkInvite, known: List<KnownComputer>): KnownComputer? =
    known.firstOrNull { it.ident.isNotBlank() && it.ident == invite.id }
        ?: known.firstOrNull { k ->
            (k.ident.isBlank() || invite.id.isBlank()) && k.port == invite.port &&
                k.host.split(',').map { it.trim() }.any { it.isNotEmpty() && it in invite.addresses }
        }

private val CODE = Regex("^[A-Za-z0-9_-]{1,64}$")

/** A host name or an IPv4 address: nothing with a slash, a space or a scheme gets through. */
private val ADDRESS = Regex("^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$")

/** Whether [address] is one of a home or office network (10.x, 172.16-31.x, 192.168.x), not a tailnet's. */
fun isLocalNetwork(address: String): Boolean {
    val p = address.trim().split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4 || p.any { it !in 0..255 }) return false
    return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
}

/** One computer's answer to "weave?", as a laptop of the list; null when it isn't one. */
internal fun parseFound(reply: String, from: String): LinkHost? = runCatching {
    val o = JSONObject(reply)
    if (o.optInt("weave") != 1) return null
    val name = o.optString("name").take(60).ifBlank { from }
    LinkHost(
        id = "lan:$from", name = name, addresses = listOf(from), port = o.optInt("port", LinkProtocol.PORT),
        ticket = "", online = true, busy = o.optBoolean("busy"),
    )
}.getOrNull()

/**
 * Finds computers running Weave on this phone's own network: it asks everyone on the Wi-Fi
 * ("weave?", a UDP broadcast) and listens for answers for [waitMs]. Nothing found is no error:
 * off Wi-Fi, or on a network that keeps its devices apart, nobody hears the question.
 */
suspend fun findOnNetwork(waitMs: Int = 700): List<LinkHost> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, LinkHost>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 150
            val ask = "weave?".toByteArray()
            val targets = buildSet {
                add(InetAddress.getByName("255.255.255.255"))
                // each network's own broadcast address too: some routers drop the all-ones one
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach(::add)
            }
            targets.forEach { to -> runCatching { socket.send(DatagramPacket(ask, ask.size, to, LinkProtocol.PORT)) } }
            val until = System.currentTimeMillis() + waitMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val from = packet.address.hostAddress ?: continue
                parseFound(String(packet.data, 0, packet.length), from)?.let { found.putIfAbsent(from, it) }
            }
        }
    }
    found.values.toList()
}

/**
 * The computers this phone knows, with the ones the network just answered for. An answer is
 * tied to a known computer only by its address: a name is whatever the sender says it is, and
 * a stranger on the Wi-Fi answering to a computer's name must never be taken for it. Anything
 * else found is its own entry, connected to only when tapped, with a token of its own.
 */
internal fun mergeFound(listed: List<LinkHost>, found: List<LinkHost>): List<LinkHost> {
    val out = listed.toMutableList()
    for (f in found) {
        val at = out.indexOfFirst { h -> f.addresses.any { it in h.addresses } && h.port == f.port }
        if (at < 0) out += f
        else out[at] = out[at].copy(online = true, busy = out[at].busy || f.busy)
    }
    return out
}

/**
 * A computer this phone knows that the dashboard lists too is one entry.
 *
 * One that was picked from the dashboard's list is the dashboard's entry with the same id: it
 * has the computer's newest addresses and its ticket, and carries the key the phone gave it (so
 * it is not asked about again). An id is the computer's own on the dashboard; another computer
 * can't announce under it.
 *
 * Nothing else ties them. An entry that only shares a name or an address with a known computer
 * could be anybody's (a list of addresses is whatever its sender wrote), and the phone's token
 * for that computer must not go where such an entry points. So the known computer stays as the
 * phone saved it. An entry that shares an address of its own with it (a tailnet one, a name) is
 * left out rather than shown beside it as a second one; one that shares only a home-network
 * address is shown, because those repeat from house to house.
 *
 * Returns the known computers left over, and what is shown of the dashboard's list.
 */
internal fun joinKnown(known: List<LinkHost>, board: List<LinkHost>): Pair<List<LinkHost>, List<LinkHost>> {
    val taken = HashSet<String>()
    val joined = board.mapNotNull { d ->
        val k = known.firstOrNull { k -> k.id !in taken && k.board.isNotBlank() && k.board == d.id }
        when {
            k != null -> d.copy(key = k.key, board = k.board).also { taken += k.id }
            known.any { o -> o.port == d.port && o.addresses.any { it in d.addresses && !isLocalNetwork(it) } } -> null
            else -> d
        }
    }
    return known.filterNot { it.id in taken } to joined
}

/** Whether [laptop] is the computer saved as [host] and [port]: by an address they share, never by its name. */
internal fun isSaved(laptop: LinkHost, host: String, port: Int): Boolean {
    val saved = host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return laptop.port == port && laptop.addresses.any { it in saved }
}

/** Whether anything answers at one of [addresses]: a known computer that is on, wherever it is. */
internal suspend fun answers(addresses: List<String>, port: Int, waitMs: Int = 700): Boolean = withContext(Dispatchers.IO) {
    addresses.any { address ->
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(address, port), waitMs) }
            true
        }.getOrDefault(false)
    }
}
