package com.t3lluz.weave.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun theMagicPacketIsSixFfsThenTheAddressSixteenTimes() {
        val p = WakeOnLan.packet("bc:f4:d4:73:13:83")!!
        assertEquals(102, p.size)
        assertEquals((0 until 6).map { 0xFF.toByte() }, p.take(6))
        assertEquals(listOf(0xbc, 0xf4, 0xd4, 0x73, 0x13, 0x83).map { it.toByte() }, p.slice(96 until 102))
        assertNull(WakeOnLan.packet("not a mac"))
    }
}
