package com.t3lluz.weave.data.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wakes a sleeping laptop: the magic packet (six 0xFF bytes, then its network card's address
 * sixteen times) broadcast on the network, which its Wi-Fi or Ethernet card listens for while
 * the rest sleeps. Only from the same network: asleep, the laptop is off the tailnet.
 */
object WakeOnLan {
    /** The packet for [mac] ("bc:f4:d4:73:13:83"), or null for an address that isn't one. */
    fun packet(mac: String): ByteArray? {
        val bytes = mac.split(':', '-').takeIf { it.size == 6 }?.map { it.toIntOrNull(16) ?: return null } ?: return null
        val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) for (j in 0 until 6) out[6 + i * 6 + j] = bytes[j].toByte()
        return out
    }

    /** Sends it a few times, to the network's own broadcast address and to everyone, on the usual ports. */
    suspend fun send(mac: String, broadcast: String) = withContext(Dispatchers.IO) {
        val data = packet(mac) ?: return@withContext false
        val targets = listOfNotNull(broadcast.takeIf { it.isNotBlank() }, "255.255.255.255").distinct()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    for (host in targets) for (port in intArrayOf(9, 7)) {
                        runCatching { socket.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port)) }
                    }
                    delay(120)
                }
            }
            true
        }.getOrDefault(false)
    }
}
