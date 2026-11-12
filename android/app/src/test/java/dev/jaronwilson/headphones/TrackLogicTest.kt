package dev.jaronwilson.headphones

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLogicTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- RSSI smoothing ----
    @Test fun kalmanStartsAtTheFirstReading() {
        assertEquals(-70.0, RssiKalman().update(-70.0), 0.0)
    }

    @Test fun kalmanDampsASpike() {
        val k = RssiKalman()
        repeat(20) { k.update(-80.0) }
        val after = k.update(-50.0)                  // one 30 dB outlier
        assertTrue("moved only part way: $after", after < -65 && after > -80)
    }

    @Test fun kalmanFollowsARealChange() {
        val k = RssiKalman()
        repeat(10) { k.update(-85.0) }
        repeat(15) { k.update(-55.0) }               // walked right up to them
        assertEquals(-55.0, k.value!!, 2.0)
    }

    @Test fun kalmanResetForgets() {
        val k = RssiKalman(); k.update(-90.0); k.reset()
        assertNull(k.value)
        assertEquals(-60.0, k.update(-60.0), 0.0)
    }

    @Test fun emaMovesByAlpha() {
        val e = Ema(0.25)
        e.update(-80.0)
        assertEquals(-75.0, e.update(-60.0), 1e-9)
    }

    // ---- distance / heat ----
    @Test fun distanceIsOneMetreAtTheReferencePower() {
        assertEquals(1.0, Proximity.distanceMeters(-59.0), 1e-9)
    }

    @Test fun distanceGrowsTenfoldPer25dB() {
        assertEquals(10.0, Proximity.distanceMeters(-84.0), 1e-9)
        assertTrue(Proximity.distanceMeters(-70.0) < Proximity.distanceMeters(-80.0))
    }

    @Test fun heatBands() {
        assertEquals(Heat.HOT, Proximity.heat(-55.0))
        assertEquals(Heat.WARM, Proximity.heat(-70.0))
        assertEquals(Heat.COLD, Proximity.heat(-90.0))
    }

    @Test fun buzzSpeedsUpWhenCloser() {
        val far = Proximity.buzzIntervalMs(-95.0)
        val near = Proximity.buzzIntervalMs(-50.0)
        assertTrue(near < far)
        assertTrue(near >= 150 && far <= 1600)
    }

    @Test fun distanceText() {
        assertEquals("within half a metre", Proximity.distanceText(-40.0))
        assertEquals("far (20 m or more)", Proximity.distanceText(-100.0))
    }

    // ---- event storage and rotation ----
    private fun ev(device: String, kind: String, t: Long) = SeenEvent(device, kind, t)

    @Test fun roundTripKeepsEveryField() {
        val e = SeenEvent("airpods", "disconnected", 1_700_000_000_000, 40.1, -111.5, 12.5f,
            "L 80% · R 70%", "both buds in ears", -63, "tab\there")
        val back = EventLog.decode(EventLog.encode(e))!!
        assertEquals(e.copy(note = "tab here"), back)
        val bare = SeenEvent("crusher", "lost", 5)
        assertEquals(bare, EventLog.decode(EventLog.encode(bare)))
        assertNull(EventLog.decode("garbage"))
    }

    @Test fun keepsAtMost50PerDeviceDroppingTheOldest() {
        var list = emptyList<SeenEvent>()
        for (i in 1..60) list = EventLog.append(list, ev("airpods", "disconnected", i.toLong()))
        list = EventLog.append(list, ev("crusher", "disconnected", 100))
        val ap = list.filter { it.device == "airpods" }
        assertEquals(50, ap.size)
        assertEquals(11L, ap.minOf { it.t })
        assertEquals(60L, ap.maxOf { it.t })
        assertEquals(1, list.count { it.device == "crusher" })
    }

    @Test fun rotationIsPerDevice() {
        var list = emptyList<SeenEvent>()
        for (i in 1..5) list = EventLog.append(list, ev("crusher", "disconnected", i.toLong()))
        for (i in 1..10) list = EventLog.append(list, ev("airpods", "disconnected", 100L + i), max = 3)
        assertEquals(5, list.count { it.device == "crusher" })
        assertEquals(3, list.count { it.device == "airpods" })
    }

    @Test fun nearbyRunCollapsesButKeepsItsLocation() {
        var list = EventLog.append(emptyList(), SeenEvent("airpods", "nearby", 1, 1.0, 2.0, 5f))
        list = EventLog.append(list, ev("airpods", "nearby", 2))
        assertEquals(1, list.size)
        assertEquals(2L, list[0].t)
        assertEquals(1.0, list[0].lat!!, 0.0)
        list = EventLog.append(list, ev("airpods", "disconnected", 3))
        list = EventLog.append(list, ev("airpods", "nearby", 4))
        assertEquals(3, list.size)
    }

    @Test fun nearbyThrottle() {
        assertFalse(EventLog.nearbyDue(lastWrite = 0, now = 60_000, gapMs = 300_000))
        assertTrue(EventLog.nearbyDue(lastWrite = 0, now = 300_000, gapMs = 300_000))
    }

    @Test fun storageRoundTripOfAList() {
        val list = listOf(ev("airpods", "nearby", 1), ev("crusher", "disconnected", 2))
        assertEquals(list, EventLog.decodeAll(EventLog.encodeAll(list)))
        assertEquals(listOf(list[1]), EventLog.forDevice(list, "crusher"))
    }

    // ---- the advert ----
    // AirPods Pro 2 (USB-C), left primary, L 80 R 70, case 50 charging, colour white
    private val advert = hex("07 19 01 24 20 2B 78 45 31 00 04" + "00".repeat(16))

    @Test fun parsesModelBatteryAndFlags() {
        val a = ProxAdvert.parse(advert)!!
        assertEquals(0x2420, a.model)
        assertEquals(80, a.left); assertEquals(70, a.right); assertEquals(50, a.case)
        assertTrue(a.caseCharging)
        assertEquals("L 80% · R 70% · Case 50%+", a.batteryText())
        assertEquals("AirPods Pro 2 (USB-C)", ProxAdvert.modelName(a.model))
    }

    @Test fun rightPrimaryFlipsTheNibbles() {
        val a = ProxAdvert.parse(hex("07 19 01 24 20 0B 78 4F 31 00 04" + "00".repeat(16)))!!
        assertEquals(70, a.left); assertEquals(80, a.right); assertNull(a.case)
    }

    @Test fun inCaseFlags() {
        val both = ProxAdvert.parse(hex("07 19 01 24 20 74 78 45 31 00 04" + "00".repeat(16)))!!   // 0x74: this in case, both in case
        assertTrue(both.thisInCase); assertTrue(both.bothInCase)
        assertEquals("both buds in the case", both.budsText())
        val one = ProxAdvert.parse(hex("07 19 01 24 20 30 78 45 31 00 04" + "00".repeat(16)))!!    // 0x30: one in case
        assertTrue(one.oneInCase); assertFalse(one.thisInCase)
    }

    @Test fun rejectsOtherAdverts() {
        assertNull(ProxAdvert.parse(hex("10 05 01 18 00 00 00 00 00 00 00")))   // "nearby" type, not proximity pairing
        assertNull(ProxAdvert.parse(hex("07 19 01")))
        assertNull(ProxAdvert.parse(null))
    }

    // ---- matching ----
    private val ad = ProxAdvert.parse(advert)!!

    @Test fun heuristicMatch() {
        assertEquals(MatchLevel.LIKELY, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity(0x2420, 0, left = 90, right = 70)))
        assertEquals(MatchLevel.NO, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity(0x1420, 0)))           // other model
        assertEquals(MatchLevel.NO, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity(0x2420, 1)))           // other colour
        assertEquals(MatchLevel.NO, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity(0x2420, 0, left = 20))) // battery far off
        assertEquals(MatchLevel.MODEL_ONLY, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity(0x2420, 0)))   // nothing to compare
        assertEquals(MatchLevel.MODEL_ONLY, AdvertMatch.match("11:22:33:44:55:66", ad, ApIdentity()))            // nothing known
    }

    // Bluetooth Core spec sample (Vol 3 Part H, ah): IRK ec0234a3..0a397d9b, prand 708194 -> hash 0dfbaa.
    // The AirPods send the IRK least-significant byte first, hence the reversal here.
    private val irk = hex("ec0234a357c8ad05341010a60a397d9b").reversedArray()

    @Test fun rpaResolvesTheSpecSample() {
        assertTrue(Rpa.resolves("70:81:94:0D:FB:AA", irk))
        assertArrayEquals(hex("aafb0d"), Rpa.ah(irk, hex("948170")))
    }

    @Test fun rpaRejectsOthers() {
        assertFalse(Rpa.resolves("70:81:94:0D:FB:AB", irk))       // hash off by one
        assertFalse(Rpa.resolves("30:81:94:0D:FB:AA", irk))       // not a resolvable address
        assertFalse(Rpa.resolves("nonsense", irk))
    }

    @Test fun keyMakesTheMatchExactOrNothing() {
        val id = ApIdentity(0x1420, 5, irk)       // model and colour don't matter once the key is known
        assertEquals(MatchLevel.EXACT, AdvertMatch.match("70:81:94:0D:FB:AA", ad, id))
        assertEquals(MatchLevel.NO, AdvertMatch.match("70:81:94:0D:FB:AB", ad, id))
    }

    // ---- AAP packets ----
    @Test fun irkFromTheKeysReply() {
        val key = ByteArray(16) { it.toByte() }
        val enc = ByteArray(16) { 0x55 }
        val pkt = hex("04 00 04 00 31 00 02") + hex("04 00 10 00") + enc + hex("01 00 10 00") + key
        assertArrayEquals(key, AapTrack.irkFrom(pkt))
        assertNull(AapTrack.irkFrom(hex("04 00 04 00 09 00 0D 02 00 00 00")))
        assertNull(AapTrack.irkFrom(hex("04 00 04 00 31 00 01 01 00 10 00 01 02")))   // truncated
    }

    @Test fun batteryFromAap() {
        val pkt = hex("04 00 04 00 04 00 03 02 01 50 01 01 04 01 46 02 01 08 01 1E 04 01")
        assertEquals("L 80% · R 70%+", AapTrack.batteryFrom(pkt))
        assertNotNull(AapTrack.batteryFrom(pkt, pkt.size))
        assertNull(AapTrack.batteryFrom(hex("04 00 04 00 09 00 0D 02")))
    }

    @Test fun matchTolerance() {
        val near = ApIdentity(0x2420, 0, left = 80 + 20)
        assertEquals(MatchLevel.LIKELY, AdvertMatch.match("x", ad, near))
    }
}
