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
    fun anAnswerFromTheNetworkIsTiedToAKnownComputerOnlyByItsAddress() {
        val mine = listOf(LinkHost("known", "t3lluz", listOf("192.168.1.23", "100.74.84.100", "t3lluz"), 47600, "ticket", online = false, busy = false))
        val found = listOf(
            // the computer itself, answering from its own address
            LinkHost("lan:192.168.1.23", "t3lluz", listOf("192.168.1.23"), 47600, "", online = true, busy = true),
            // a stranger on the Wi-Fi, answering to its name
            LinkHost("lan:192.168.1.66", "t3lluz", listOf("192.168.1.66"), 47600, "", online = true, busy = false),
        )
        val merged = mergeFound(mine, found)
        assertEquals(2, merged.size)
        assertEquals("its addresses are untouched", listOf("192.168.1.23", "100.74.84.100", "t3lluz"), merged[0].addresses)
        assertEquals("ticket", merged[0].ticket)
        assertTrue(merged[0].online && merged[0].busy)
        assertEquals("the stranger is an entry of its own", listOf("192.168.1.66"), merged[1].addresses)
        assertEquals("", merged[1].ticket)
    }

    @Test
    fun theSameAddressOnAnotherPortIsAnotherComputer() {
        val mine = listOf(LinkHost("known", "box", listOf("192.168.1.23"), 47600, "", online = false, busy = false))
        val found = listOf(LinkHost("lan:192.168.1.23", "box", listOf("192.168.1.23"), 5000, "", online = true, busy = false))
        assertEquals(2, mergeFound(mine, found).size)
    }

    @Test
    fun eachComputerGetsATokenOfItsOwn() {
        val home = deriveToken("secret", "key-of-home")
        assertEquals("the same computer, the same token, whatever its addresses have become", home, deriveToken("secret", "key-of-home"))
        assertTrue(home.length >= 32)
        // what a link pointing somewhere else is sent (a new key) opens nothing at home
        assertFalse(home == deriveToken("secret", "key-of-a-stranger"))
        assertFalse("and another phone's is another", home == deriveToken("other", "key-of-home"))
        assertFalse("neither the secret nor the key is in it", home.contains("secret") || home.contains("key-of-home"))
    }

    @Test
    fun aComputerPickedFromTheDashboardIsOneEntryWithItsKey() {
        val known = listOf(
            LinkHost("known:k1", "t3lluz", listOf("100.74.84.100", "t3lluz"), 47600, "old-code", online = false, busy = false, key = "k1", board = "d1"),
            LinkHost("known:k2", "desk", listOf("192.168.1.40"), 47600, "", online = true, busy = false, key = "k2"),
        )
        // the dashboard's own entry for the first, which has moved since
        val board = listOf(LinkHost("d1", "t3lluz", listOf("100.74.84.200", "t3lluz"), 47600, "ticket", online = true, busy = false, fromDashboard = true))
        val (left, joined) = joinKnown(known, board)
        assertEquals(listOf("desk"), left.map { it.name })
        assertEquals("the dashboard's addresses and ticket, the phone's key", "k1", joined[0].key)
        assertEquals(listOf("100.74.84.200", "t3lluz"), joined[0].addresses)
        assertEquals("ticket", joined[0].ticket)
    }

    @Test
    fun anEntryThatOnlyLooksLikeAKnownComputerNeverGetsItsKey() {
        val known = listOf(
            LinkHost("known:k1", "t3lluz", listOf("192.168.1.23", "100.74.84.100"), 47600, "", online = true, busy = false, key = "k1", board = "d1"),
            LinkHost("known:k2", "desk", listOf("192.168.1.40"), 47600, "", online = true, busy = false, key = "k2"),
        )
        // the same name under another id, with somebody's address put in front of a real one
        val namesake = LinkHost("d9", "t3lluz", listOf("203.0.113.9", "100.74.84.100"), 47600, "", online = true, busy = false, fromDashboard = true)
        // one that shares an address with a computer paired by code
        val lookalike = LinkHost("d8", "desk", listOf("203.0.113.9", "192.168.1.40"), 47600, "", online = true, busy = false, fromDashboard = true)
        // and one this phone has nothing to do with
        val stranger = LinkHost("d7", "t3lluz", listOf("100.99.0.1"), 47600, "", online = true, busy = false, fromDashboard = true)
        val (left, shown) = joinKnown(known, listOf(namesake, lookalike, stranger))
        assertEquals("both known computers stay as the phone saved them", listOf("known:k1", "known:k2"), left.map { it.id })
        assertEquals("the two that share an address are left out, not shown as a second one", listOf("d7"), shown.map { it.id })
        assertEquals("", shown[0].key)
    }

    @Test
    fun aComputerSavedBeforeKeysStaysOnTheListWithANewOne() {
        val old = org.json.JSONObject().put("name", "t3lluz").put("host", "100.74.84.100").put("port", 47600).put("wakeMac", "aa:bb:cc:dd:ee:ff")
        val read = KnownComputer.fromJson(old) { "made-now" }!!
        assertEquals("made-now", read.key)
        assertEquals("aa:bb:cc:dd:ee:ff", read.wakeMac)
        val kept = KnownComputer.fromJson(read.copy(board = "d1").toJson()) { "another" }!!
        assertEquals("one that has a key keeps it", "made-now", kept.key)
        assertEquals("d1", kept.board)
        assertNull(KnownComputer.fromJson(org.json.JSONObject().put("name", "nowhere")) { "k" })
    }

    @Test
    fun theRowThatIsTheSavedComputerIsFoundByAddressNotByName() {
        val real = LinkHost("known:k1", "t3lluz", listOf("192.168.1.23", "t3lluz"), 47600, "", online = true, busy = false, key = "k1")
        val stranger = LinkHost("lan:192.168.1.66", "t3lluz", listOf("192.168.1.66"), 47600, "", online = true, busy = false)
        val saved = "192.168.1.23, 100.74.84.100,t3lluz"
        assertTrue(isSaved(real, saved, 47600))
        assertFalse("the same name from another address is somebody else", isSaved(stranger, saved, 47600))
        assertFalse(isSaved(real, saved, 5000))
        assertFalse(isSaved(real, "", 47600))
    }

    @Test
    fun anInvitationCarriesNothingThatCouldBecomePartOfAnotherLink() {
        // a second "#Intent;...;end" hidden in a field would hijack the page's hand-over to the app
        assertNull(parseInvite("https://t3lluz.com/weave/pair#a=1.2.3.4&c=x#Intent;scheme=other;end"))
        assertNull(parseInvite("weave://pair?a=1.2.3.4&c=ab;cd"))
    }
}
