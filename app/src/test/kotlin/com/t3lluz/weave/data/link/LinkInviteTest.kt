package com.t3lluz.weave.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInviteTest {
    @Test
    fun theLinkBehindTheCodeGivesEveryAddressInOrder() {
        // what `weave pair` prints, as pairing.py builds it
        val invite = parseInvite("https://t3lluz.com/weave/pair#n=my+box&a=192.168.1.23%2C100.74.84.100%2Cbox&p=47600&c=kq3T-xYz_9aBcDeF")
        assertEquals(LinkInvite("my box", listOf("192.168.1.23", "100.74.84.100", "box"), 47600, "kq3T-xYz_9aBcDeF"), invite)
    }

    @Test
    fun theAppsOwnLinkReadsTheSame() {
        val invite = parseInvite("weave://pair?n=box&a=192.168.1.23&p=47600&c=abc")
        assertEquals(LinkInvite("box", listOf("192.168.1.23"), 47600, "abc"), invite)
    }

    @Test
    fun whatIsNotAnInvitationIsNothing() {
        assertNull(parseInvite(null))
        assertNull(parseInvite("https://example.com/#a=1.2.3.4&c=x"))
        assertNull(parseInvite("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull("no code", parseInvite("weave://pair?n=box&a=192.168.1.23"))
        assertNull("no address", parseInvite("weave://pair?n=box&c=abc"))
    }

    @Test
    fun anAddressThatIsNotOneIsLeftOut() {
        val invite = parseInvite("weave://pair?a=192.168.1.5,http%3A%2F%2Fevil%2Fx,has%20space&c=abc")
        assertEquals(listOf("192.168.1.5"), invite?.addresses)
        // with no name the last address stands in, and a bad port is the usual one
        assertEquals("192.168.1.5", invite?.name)
        assertEquals(LinkProtocol.PORT, parseInvite("weave://pair?a=box&c=abc&p=99999")?.port)
    }

    @Test
    fun homeNetworksAreToldFromTailnets() {
        assertTrue(isLocalNetwork("192.168.1.23"))
        assertTrue(isLocalNetwork("10.0.0.2"))
        assertTrue(isLocalNetwork("172.20.3.4"))
        assertFalse(isLocalNetwork("100.74.84.100"))
        assertFalse(isLocalNetwork("172.32.0.1"))
        assertFalse(isLocalNetwork("t3lluz"))
    }

    @Test
    fun aComputerFoundOnTheNetworkJoinsItsDashboardEntry() {
        val listed = listOf(LinkHost("id1", "t3lluz", listOf("100.74.84.100", "t3lluz"), 47600, "ticket", online = true, busy = false))
        val found = listOf(
            LinkHost("lan:192.168.1.23", "T3lluz", listOf("192.168.1.23"), 47600, "", online = true, busy = false),
            LinkHost("lan:192.168.1.40", "desk", listOf("192.168.1.40"), 47600, "", online = true, busy = true),
        )
        val merged = mergeFound(listed, found)
        assertEquals(listOf("t3lluz", "desk"), merged.map { it.name })
        assertEquals(listOf("192.168.1.23", "100.74.84.100", "t3lluz"), merged[0].addresses)
        assertEquals("ticket", merged[0].ticket)
        assertTrue(merged[1].busy)
    }
}
