package com.t3lluz.weave.roam

import com.t3lluz.weave.data.link.SoundLeveller
import com.t3lluz.weave.ui.screens.link.strength
import com.t3lluz.weave.ui.screens.link.whereTheyAre
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** The earbuds lab's pure parts: reading the computer's word, knowing the earbuds, the player's level. */
class RoamLogicTest {

    private fun tone(peak: Int, size: Int = 2048) = ShortArray(size) { (peak * sin(it * 0.21)).toInt().toShort() }
    private fun peakOf(block: ShortArray) = block.maxOf { abs(it.toInt()) }

    @Test
    fun `the computer's word on the earbuds is read as it is said`() {
        val status = RoamStatus.from(
            JSONObject(
                """{"t":"roam","on":true,"mode":"manual","sens":"early","state":"armed",
                   "device":{"addr":"aa:bb:cc:dd:ee:01","name":"AirPods Pro"},
                   "devices":[{"addr":"AA:BB:CC:DD:EE:01","name":"AirPods Pro"},{"addr":"","name":"nobody"}],
                   "signal":-3.4,"limit":-4,"dbm":false,"auto":true,"autoWhy":"","near":null,"beacon":null,"why":"asked for"}""",
            ),
        )
        assertTrue(status.on)
        assertEquals("manual", status.mode)
        assertEquals("early", status.sens)
        assertEquals(RoamEarbuds("AA:BB:CC:DD:EE:01", "AirPods Pro"), status.device)
        assertEquals("an entry with no address is none", 1, status.devices.size)
        assertEquals(-3.4, status.signal!!, 0.001)
        assertTrue(status.auto)
        assertNull("a beacon said to be nothing is none", status.beacon)
    }

    @Test
    fun `a computer that says little is off, automatic and balanced`() {
        val status = RoamStatus.from(JSONObject("""{"t":"roam"}"""))
        assertFalse(status.on)
        assertEquals("auto", status.mode)
        assertEquals("balanced", status.sens)
        assertEquals("off", status.state)
        assertNull(status.device)
        assertNull(status.signal)
    }

    @Test
    fun `the earbuds the computer named are kept and read back`() {
        val pairs = listOf(RoamEarbuds("AA:BB:CC:DD:EE:01", "AirPods Pro"), RoamEarbuds("11:22:33:44:55:66", "Buds"))
        assertEquals(pairs, RoamStatus.earbudsFrom(RoamStatus.toJson(pairs)))
        assertEquals(emptyList<RoamEarbuds>(), RoamStatus.earbudsFrom("not json"))
        assertEquals(emptyList<RoamEarbuds>(), RoamStatus.earbudsFrom(null))
    }

    @Test
    fun `an output is the earbuds by its whole address, or by its end where Android hides the rest`() {
        assertTrue(sameEarbuds("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:01"))
        assertTrue(sameEarbuds("aa:bb:cc:dd:ee:01", "AA:BB:CC:DD:EE:01"))
        assertTrue(sameEarbuds("XX:XX:XX:XX:EE:01", "AA:BB:CC:DD:EE:01"))
        assertFalse(sameEarbuds("XX:XX:XX:XX:EE:02", "AA:BB:CC:DD:EE:01"))
        assertFalse(sameEarbuds("11:22:33:44:55:66", "AA:BB:CC:DD:EE:01"))
        assertFalse(sameEarbuds("", "AA:BB:CC:DD:EE:01"))
        assertFalse(sameEarbuds(null, "AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun `the beacon's number is its bytes, and nonsense is no beacon`() {
        assertArrayEquals(byteArrayOf(0x01, 0x7f, 0xff.toByte(), 0x09), beaconBytes("017fff09"))
        assertNull(beaconBytes(null))
        assertNull(beaconBytes("abc"))
        assertNull(beaconBytes("zz"))
        assertNull(beaconBytes("00".repeat(17)))
    }

    @Test
    fun `the player is at a phone's level from its first block`() {
        // a computer mixed for earbuds it drives at full: peaks 42 dB under full scale
        val leveller = SoundLeveller(most = 100f, fromMost = true)
        val first = tone(260).also { leveller.lift(it, it.size) }
        assertTrue("peak ${peakOf(first)}", peakOf(first) in 20_000..23_500)
    }

    @Test
    fun `and something loud right at the start is not clipped`() {
        val leveller = SoundLeveller(most = 100f, fromMost = true)
        val loud = tone(30_000)
        leveller.lift(loud, loud.size)
        assertTrue("gain ${leveller.gain}", leveller.gain <= 1f)
        assertTrue("peak ${peakOf(loud)}", abs(peakOf(loud) - 30_000) < 400)
    }

    @Test
    fun `it never brings sound up by more than it was told`() {
        val leveller = SoundLeveller(most = 100f, fromMost = true)
        var last = ShortArray(0)
        repeat(2_000) { last = tone(20).also { leveller.lift(it, it.size) } }
        assertTrue("gain ${leveller.gain}", leveller.gain <= 100f)
        assertTrue("peak ${peakOf(last)}", peakOf(last) <= 2_050)
    }

    @Test
    fun `where the earbuds are is said in a sentence`() {
        val armed = RoamStatus(on = true, state = "armed")
        assertEquals("On t3lluz.", whereTheyAre(armed, RoamPlayer(), "t3lluz", online = true))
        assertEquals("On this phone, playing t3lluz's sound.", whereTheyAre(armed, RoamPlayer(running = true, playing = true), "t3lluz", online = true))
        assertEquals("Asking t3lluz…", whereTheyAre(null, RoamPlayer(), "t3lluz", online = false))
        assertEquals("On their way back to t3lluz.", whereTheyAre(armed.copy(state = "returning"), RoamPlayer(), "t3lluz", online = true))
    }

    @Test
    fun `the link's strength is said only while the earbuds are on the computer`() {
        val armed = RoamStatus(on = true, state = "armed", signal = 0.0, limit = -8.0)
        assertEquals("Link strong (0). The phone takes over at -8.", strength(armed))
        assertEquals("Link getting weak (-6). The phone takes over at -8.", strength(armed.copy(signal = -6.0)))
        assertEquals("Link weak (-9). The phone takes over at -8.", strength(armed.copy(signal = -9.0)))
        assertEquals("by hand, no point is named", "Link strong (0).", strength(armed.copy(mode = "manual")))
        assertEquals("Link strong (-50 dBm). The phone takes over at -72 dBm.", strength(armed.copy(signal = -50.0, limit = -72.0, dbm = true)))
        assertNull(strength(armed.copy(state = "bridged")))
        assertNull(strength(armed.copy(signal = null)))
    }
}
