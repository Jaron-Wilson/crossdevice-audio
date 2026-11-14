package dev.jaronwilson.headphones

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The AirPods' AAP microphone stream (0x58): packets, framing, and the analysis that reads it. */
class AapMicTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun startAndStopMatchLibrePods() {
        // librepods PR #655 aacp.rs AACP_START_AUDIO / AACP_STOP_AUDIO, and the Android rewrite's payloads
        assertArrayEquals(hex("04000400580000000900000182000000049600"), AapMic.START)
        assertArrayEquals(hex("040004005800000002000301"), AapMic.STOP)
        assertEquals(19, AapMic.START.size)
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30, 0x00), AapMic.ASC)
    }

    private fun sdu(vararg aus: ByteArray): ByteArray {
        val header = ByteArray(22).also { h -> listOf(4, 0, 4, 0, 0x58, 0, 1, 0).forEachIndexed { i, v -> h[i] = v.toByte() } }
        var body = byteArrayOf()
        for ((i, au) in aus.withIndex()) body += byteArrayOf(i.toByte(), 0, 0, 0, au.size.toByte()) + au
        return header + body
    }

    @Test fun demuxSplitsAccessUnits() {
        val a = byteArrayOf(1, 2, 3); val b = ByteArray(200) { 7 }
        val got = AapMic.demux(sdu(a, b))
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0]); assertArrayEquals(b, got[1])
    }

    @Test fun demuxStopsAtATruncatedUnit() {
        val p = sdu(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))
        assertEquals(1, AapMic.demux(p, p.size - 2).size)
    }

    @Test fun onlyAudioSubtypeIsAudio() {
        assertTrue(AapMic.isAudio(sdu(byteArrayOf(1))))
        assertFalse(AapMic.isAudio(AapMic.STOP))                         // a control message, not audio
        assertFalse(AapMic.isAudio(hex("0400040009000D02000000")))       // noise-mode report
        assertTrue(AapMic.demux(hex("0400040009000D02000000")).isEmpty())
    }

    private fun tone(f: Double, secs: Double, rate: Int = AapMic.RATE, amp: Double = 0.3) =
        DoubleArray((secs * rate).toInt()) { amp * sin(2 * PI * f * it / rate) }

    @Test fun analyzerFindsMainsHum() {
        val a = SoundAnalyzer()
        a.feed(tone(60.0, 4.0))
        val l = a.inspect()
        assertNotNull(l)
        assertEquals(60.0, l!!.peakHz, 3.0)
        assertTrue(l.tonal); assertTrue(l.steady)
        assertTrue(l.label, l.label.startsWith("mains hum (60 Hz"))
        assertTrue(l.ancHelps)
    }

    @Test fun analyzerBandAndLevel() {
        val a = SoundAnalyzer()
        val x = tone(2000.0, 4.0)
        a.feed(x)
        val l = a.inspect(1500.0, 2600.0)!!
        assertEquals(2000.0, l.peakHz, 10.0)
        assertFalse(l.ancHelps)
        // a 0.3-amplitude sine is about -13.5 dBFS RMS
        assertEquals(-13.5, a.levelDb, 1.0)
        assertTrue(a.bins.maxOrNull()!! > -40)
    }

    @Test fun earTextReadsTheAirPodsReport() {
        val p = hex("04000400060000 02".replace(" ", ""))
        val e = PhoneDirect.earFrom(p, p.size)
        assertEquals(0 to 2, e)
        assertEquals("One bud in ear, the other in case (which is left isn't known yet)", PhoneDirect.earText(e!!))
        assertEquals("Left: in case, Right: in ear", PhoneDirect.earText(e, primaryIsLeft = false))
    }
}
