package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateSessionTest {
    private val airpods = AudioRoute.Headset(7, "AirPods Pro", isConfiguredAirPods = true)

    private class FakeAudio(var headset: AudioRoute.Headset?, var routeOk: Boolean = true) : AudioRoute {
        var m = AudioRoute.MODE_NORMAL
        var routed: Int? = null
        var focus = false
        val log = mutableListOf<String>()
        override fun findHeadset() = headset
        override fun currentMode() = m
        override fun setMode(mode: Int) { m = mode; log += "mode $mode" }
        override fun routeTo(h: AudioRoute.Headset): Boolean { log += "route ${h.id}"; if (routeOk) routed = h.id; return routeOk }
        override fun clearRoute() { routed = null; log += "clear" }
        override fun requestFocus(): Boolean { focus = true; return true }
        override fun abandonFocus() { focus = false }
    }

    private class FakeAnc(var state: AncState, var reachable: Boolean = true, var failApply: Boolean = false) : NoiseControl {
        val log = mutableListOf<String>()
        override fun available() = reachable
        override fun read() = state
        override fun apply(choice: AncChoice) {
            if (failApply) error("link refused")
            log += "apply $choice"
            state = when (choice) { AncChoice.ANC -> AncState("anc", state.level); AncChoice.BLOCK_MAX -> AncState("adaptive", 100); else -> state }
        }
        override fun restore(prev: AncState, applied: AncChoice) { log += "restore ${prev.mode} ${prev.level} after $applied"; state = prev }
    }

    @Test fun startRoutesTheAirPodsAndStopPutsEverythingBack() {
        val a = FakeAudio(airpods); val n = FakeAnc(AncState("transparency", 40))
        val s = TranslateSession(a, n) { AncChoice.ANC }
        val r = s.start()
        assertTrue(r is TranslateSession.Start.Ok)
        assertEquals(TranslateSession.Phase.ACTIVE, s.phase)
        assertEquals(AudioRoute.MODE_IN_COMMUNICATION, a.m)
        assertEquals(7, a.routed)
        assertTrue(a.focus)
        assertEquals("anc", n.state.mode)
        // mode is set before the device is chosen
        assertEquals(listOf("mode 3", "route 7"), a.log)

        assertEquals("", s.stop())
        assertEquals(TranslateSession.Phase.IDLE, s.phase)
        assertEquals(AudioRoute.MODE_NORMAL, a.m)
        assertNull(a.routed)
        assertTrue(!a.focus)
        assertEquals(AncState("transparency", 40), n.state)
        assertEquals(listOf("apply ANC", "restore transparency 40 after ANC"), n.log)
    }

    @Test fun noAirPodsChangesNothing() {
        val a = FakeAudio(null); val n = FakeAnc(AncState("off", null))
        val s = TranslateSession(a, n) { AncChoice.ANC }
        assertEquals(TranslateSession.Start.NoHeadset, s.start())
        assertEquals(TranslateSession.Phase.IDLE, s.phase)
        assertTrue(a.log.isEmpty()); assertTrue(n.log.isEmpty())
        assertEquals("", s.stop())                         // stop when idle is a no-op
        assertTrue(a.log.isEmpty())
    }

    @Test fun aRouteFailureRollsBack() {
        val a = FakeAudio(airpods, routeOk = false); val n = FakeAnc(AncState("off", null))
        val s = TranslateSession(a, n) { AncChoice.ANC }
        assertEquals(TranslateSession.Start.RouteFailed, s.start())
        assertEquals(AudioRoute.MODE_NORMAL, a.m)
        assertNull(a.routed)
        assertTrue(!a.focus)
        assertTrue(n.log.isEmpty())                        // ANC untouched
        assertEquals(TranslateSession.Phase.IDLE, s.phase)
    }

    @Test fun blockMaxRestoresTheAdaptiveLevel() {
        val a = FakeAudio(airpods); val n = FakeAnc(AncState("adaptive", 30))
        val s = TranslateSession(a, n) { AncChoice.BLOCK_MAX }
        s.start()
        assertEquals(AncState("adaptive", 100), n.state)
        s.stop()
        assertEquals(AncState("adaptive", 30), n.state)
        assertEquals("restore adaptive 30 after BLOCK_MAX", n.log.last())
    }

    @Test fun alreadyInTheWantedModeIsLeftAlone() {
        val n = FakeAnc(AncState("anc", 50))
        val s = TranslateSession(FakeAudio(airpods), n) { AncChoice.ANC }
        s.start(); s.stop()
        assertTrue(n.log.isEmpty())
    }

    @Test fun keepNeverTouchesNoiseControl() {
        val n = FakeAnc(AncState("transparency", 50))
        val s = TranslateSession(FakeAudio(airpods), n) { AncChoice.KEEP }
        s.start(); s.stop()
        assertTrue(n.log.isEmpty())
    }

    @Test fun anotherHeadsetGetsNoAncChanges() {
        val n = FakeAnc(AncState("transparency", 50))
        val other = AudioRoute.Headset(3, "Other headset", isConfiguredAirPods = false)
        val s = TranslateSession(FakeAudio(other), n) { AncChoice.ANC }
        val r = s.start() as TranslateSession.Start.Ok
        assertTrue(r.ancNote.isNotEmpty())
        s.stop()
        assertTrue(n.log.isEmpty())
    }

    @Test fun unknownPreviousModeIsNotChanged() {
        val n = FakeAnc(AncState(null, null))
        val s = TranslateSession(FakeAudio(airpods), n) { AncChoice.ANC }
        s.start(); s.stop()
        assertTrue(n.log.isEmpty())
    }

    @Test fun anAncFailureStillTranslatesAndRestoresAudio() {
        val a = FakeAudio(airpods); val n = FakeAnc(AncState("transparency", 50), failApply = true)
        val s = TranslateSession(a, n) { AncChoice.ANC }
        val r = s.start() as TranslateSession.Start.Ok
        assertTrue(r.ancNote.contains("failed"))
        s.stop()
        assertEquals(AudioRoute.MODE_NORMAL, a.m)
        assertTrue(n.log.isEmpty())                        // nothing applied, nothing to restore
    }

    @Test fun startAndStopAreIdempotent() {
        val a = FakeAudio(airpods); val n = FakeAnc(AncState("off", null))
        val s = TranslateSession(a, n) { AncChoice.ANC }
        s.start(); s.start()
        assertEquals(1, a.log.count { it.startsWith("route") })
        assertEquals(1, n.log.size)
        s.stop(); s.stop()
        assertEquals(1, a.log.count { it == "clear" })
        assertEquals(2, n.log.size)
        // and it can run again
        assertTrue(s.start() is TranslateSession.Start.Ok)
        assertEquals(AudioRoute.MODE_IN_COMMUNICATION, a.m)
    }
}
