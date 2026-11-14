package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Packets screen: what each packet means, and that private bytes never show. All values are made up. */
class PacketDecodeTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val fakeMac = hex("112233445566")
    private val fakeIrk = hex("A1A2A3A4A5A6A7A8A9AAABACADAEAFB0")

    @Test fun noiseModeSentAndReported() {
        val s = PacketDecode.aap(PhoneDirect.modePacket("transparency"), sent = true)
        assertEquals("04 00 04 00 09 00 0D 03 00 00 00", s.hex)
        assertEquals("Set noise mode: Transparency", s.meaning)
        assertEquals("Noise mode is Noise Cancellation", PacketDecode.aap(hex("0400040009000D02000000"), sent = false).meaning)
        assertNull(s.text)
    }

    @Test fun settingsUseTheAppsOwnTable() {
        assertEquals("Set Conversational Awareness: on", PacketDecode.aap(PhoneDirect.controlPacket(0x28, 1), true).meaning)
        assertEquals("Personalized Volume: off", PacketDecode.aap(PhoneDirect.controlPacket(0x26, 2), false).meaning)
        assertTrue(PacketDecode.aap(PhoneDirect.controlPacket(0x2E, 30), false).meaning.startsWith("Adaptive noise level: 30"))
        assertEquals("Set Press-and-hold modes: Noise Cancellation, Transparency",
            PacketDecode.aap(PhoneDirect.controlPacket(0x1A, 6), true).meaning)
        assertEquals("Setting 0x37 is 2", PacketDecode.aap(PhoneDirect.controlPacket(0x37, 2), false).meaning)
    }

    @Test fun batteryAndEars() {
        val bat = hex("04000400040003" + "0201500101" + "02014B0101".replace("0201", "0401") + "0801280201")
        assertEquals("Battery: L 80% · R 75% · Case 40%+", PacketDecode.aap(bat, false).meaning)
        assertEquals("Ear detection: both in ear", PacketDecode.aap(hex("0400040006000000"), false).meaning)
        assertEquals("Ear detection: primary bud in ear, secondary in case", PacketDecode.aap(hex("0400040006000002"), false).meaning)
    }

    @Test fun openingSequenceAndMicControl() {
        assertEquals("Handshake: open the AAP session", PacketDecode.aap(hex("00000400010002000000000000000000"), true).meaning)
        assertEquals("Turn on notifications", PacketDecode.aap(hex("040004000F00FFFFFEFF"), true).meaning)
        assertEquals("Start the mic stream", PacketDecode.aap(AapMic.START, true).meaning)
        assertEquals("Stop the mic stream", PacketDecode.aap(AapMic.STOP, true).meaning)
        assertEquals("Ask for the IRK (proximity key)", PacketDecode.aap(AapTrack.REQUEST_IRK, true).meaning)
    }

    @Test fun unknownIsSaidHonestly() {
        val s = PacketDecode.aap(hex("0400040077000102"), false)
        assertEquals("unknown opcode 0x77", s.meaning)
        assertEquals("04 00 04 00 77 00 01 02", s.hex)
    }

    @Test fun audioSourceAddressIsHidden() {
        val s = PacketDecode.aap(hex("040004000E00") + fakeMac.reversedArray() + byteArrayOf(2), false)
        assertEquals("04 00 04 00 0E 00 xx xx xx xx xx xx 02", s.hex)
        assertEquals("Audio source: a device (address hidden), media", s.meaning)
    }

    @Test fun connectedDevicesAddressesAreHidden() {
        val p = hex("040004002E000100" + "02") + fakeMac + hex("0101") + hex("AABBCCDDEEFF") + hex("0102")
        val s = PacketDecode.aap(p, false)
        assertEquals("04 00 04 00 2E 00 01 00 02 xx xx xx xx xx xx 01 01 xx xx xx xx xx xx 01 02", s.hex)
        assertTrue(s.meaning.startsWith("Connected devices: 2"))
    }

    @Test fun keyBytesAreHidden() {
        val p = hex("04000400310001" + "01001000") + fakeIrk
        val s = PacketDecode.aap(p, false)
        assertEquals("Proximity keys: IRK (hidden)", s.meaning)
        assertEquals("04 00 04 00 31 00 01 01 00 10 00" + " xx".repeat(16), s.hex)
        assertFalse(s.hex.contains("A1"))
    }

    @Test fun deviceInfoShowsModelAndFirmwareOnly() {
        val body = listOf("Someone's AirPods Pro", "A3048", "Apple Inc.", "H1ABCDEFGHIJ", "81.2675000075000000.6421")
            .joinToString("\u0000").toByteArray(Charsets.ISO_8859_1)
        val p = hex("040004001D00") + body
        val s = PacketDecode.aap(p, false)
        assertTrue(s.meaning, s.meaning.startsWith("Device info: A3048, 81.2675000075000000.6421;"))
        assertTrue(s.meaning.contains("(hidden)"))
        val hexOfName = PacketDecode.hex("Someone".toByteArray())
        assertFalse(s.hex.contains(hexOfName))
        assertFalse(s.hex.contains(PacketDecode.hex("H1ABCDEFGHIJ".toByteArray())))
        assertTrue(s.hex.contains(PacketDecode.hex("A3048".toByteArray())))
    }

    @Test fun secretsAndTextAreHiddenEvenInUnknownPackets() {
        // an unknown packet carrying the AirPods' address (both byte orders), the IRK and a name
        val p = hex("040004007700") + fakeMac + fakeMac.reversedArray() + fakeIrk + "Bob's phone".toByteArray()
        val s = PacketDecode.aap(p, false, listOf(fakeMac, fakeIrk))
        assertEquals("04 00 04 00 77 00" + " xx".repeat(p.size - 6), s.hex)
        assertEquals("unknown opcode 0x77 (some bytes hidden)", s.meaning)
    }

    @Test fun micAudioShowsOnlyItsHeader() {
        val header = ByteArray(22).also { h -> listOf(4, 0, 4, 0, 0x58, 0, 1, 0).forEachIndexed { i, v -> h[i] = v.toByte() } }
        val p = header + byteArrayOf(0, 0, 0, 0, 3, 9, 9, 9) + byteArrayOf(1, 0, 0, 0, 2, 8, 8)
        val s = PacketDecode.aap(p, false)
        assertTrue(PacketDecode.isMicAudio(p))
        assertEquals("Mic audio: 2 AAC-ELD frames", s.meaning)
        assertTrue(s.hex.endsWith("... (+15 bytes of audio)"))
        assertFalse(s.hex.contains("09 09"))
    }

    @Test fun crusherCommandsAsTextAndHex() {
        val s = PacketDecode.crusher("AT.UIAUDIO=anc,on\r".toByteArray(), sent = true)
        assertEquals("AT.UIAUDIO=anc,on\\r", s.text)
        assertEquals("ANC on", s.meaning)
        assertEquals("41 54 2E 55 49 41 55 44 49 4F 3D 61 6E 63 2C 6F 6E 0D", s.hex)
        assertEquals("Set Sensory Bass level 7 of 10", PacketDecode.crusher("AT.CRUSHER=level,7\r".toByteArray(), true).meaning)
        assertEquals("Ask the noise mode", PacketDecode.crusher("AT.UIAUDIO?\r".toByteArray(), true).meaning)
        assertEquals("AT command (not decoded)", PacketDecode.crusher("AT.SOMETHING\r".toByteArray(), true).meaning)
    }

    @Test fun crusherRepliesUseTheAppsParser() {
        val r = "\r\n.UIAUDIO: anc: on, transparency: off\r\nOK\r\n"
        assertEquals("ANC on, transparency off; accepted (OK)", PacketDecode.crusher(r.toByteArray(), false).meaning)
        assertEquals("Sensory Bass level 5 of 10, Sensory Bass on; accepted (OK)",
            PacketDecode.crusher("\r\n.CRUSHER: level: 5 / 10\r\nstate: ON\r\nOK\r\n".toByteArray(), false).meaning)
        assertEquals("Reply: refused (ERROR)", PacketDecode.crusher("\r\nERROR\r\n".toByteArray(), false).meaning)
        val p = CrusherDirect.parseState("anc: off\ntransparency: on\nlevel: 3 / 10")
        assertEquals(false, p.anc); assertEquals(true, p.transparency); assertEquals(3, p.bass); assertNull(p.bassOn)
    }

    @Test fun crusherAddressesNamesAndSerialsAreHidden() {
        val r = "\r\n.INFO: addr: 11:22:33:44:55:66, serial: SN12345678, name: Jo's Crusher\r\nOK\r\n"
        val s = PacketDecode.crusher(r.toByteArray(), false, listOf("Jo's Crusher"))
        assertFalse(s.text!!, s.text!!.contains("11:22"))
        assertFalse(s.text!!.contains("SN1234"))
        assertFalse(s.text!!.contains("Jo's"))
        assertTrue(s.text!!.contains("(hidden)"))
        assertFalse(s.hex.contains(PacketDecode.hex("11:22".toByteArray())))
        assertTrue(s.meaning.endsWith("(some text hidden)"))
        // a bare 12-digit address too
        assertTrue(PacketDecode.crusher("AT.X=112233445566\r".toByteArray(), true).text!!.contains("(hidden)"))
    }
}
