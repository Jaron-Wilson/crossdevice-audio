package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarLogicTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ears(a: Bud, b: Bud) = EarReport(a, b)
    private val IN = Bud.IN_EAR
    private val OUT = Bud.OUT
    private val CASE = Bud.IN_CASE

    // ---- decoding
    @Test fun decodesEarReport() {
        assertEquals(ears(IN, CASE), EarReport.decode(hex("04 00 04 00 06 00 00 02")))
        assertEquals(ears(OUT, IN), EarReport.decode(hex("04 00 04 00 06 00 01 00")))
        assertEquals(ears(Bud.UNKNOWN, IN), EarReport.decode(hex("04 00 04 00 06 00 07 00")))
        assertNull(EarReport.decode(hex("04 00 04 00 06 00 00")))            // short
        assertNull(EarReport.decode(hex("04 00 04 00 09 00 0D 02")))         // a control report
        // only the first n bytes count
        assertNull(EarReport.decode(hex("04 00 04 00 06 00 00 02"), 7))
    }

    @Test fun reportCounts() {
        assertEquals(2, ears(IN, IN).inEar)
        assertEquals(1, ears(CASE, IN).inEar)
        assertTrue(ears(OUT, CASE).noneInEar)
        assertTrue(ears(CASE, CASE).bothInCase)
        assertFalse(ears(Bud.UNKNOWN, OUT).noneInEar)
    }

    @Test fun legacyPairHelpersAgree() {
        assertEquals(0 to 2, PhoneDirect.earFrom(hex("04 00 04 00 06 00 00 02"), 8))
    }

    // ---- left / right
    /** A real report from AirPods Pro 2, both buds in the ears, the case away: type 02 is the RIGHT bud (91%),
     *  04 the LEFT (93%), both state 02 = discharging; the case 04 = disconnected. */
    private val realBothInEar = hex("04 00 04 00 04 00 03 02 01 5B 02 01 04 01 5D 02 01 08 01 00 04 01")

    @Test fun budPowerByTypeNotOrder() {
        assertEquals(BudPower(left = 2, right = 2, firstIsLeft = false), BudPower.from(realBothInEar))
        assertEquals("L 93% · R 91%", AapTrack.batteryFrom(realBothInEar))
        // left listed first, charging (in the case); right discharging
        assertEquals(BudPower(1, 2, true), BudPower.from(hex("04 00 04 00 04 00 02 04 01 50 01 01 02 01 46 02 01")))
        assertEquals("L 80%+ · R 70%", AapTrack.batteryFrom(hex("04 00 04 00 04 00 02 04 01 50 01 01 02 01 46 02 01")))
        assertNull(BudPower.from(hex("04 00 04 00 06 00 00 02")))
    }

    /** Seen live: right bud in the ear, left in the case. The ear report is (in ear, in case); the battery
     *  report lists 02 (right) first. With the left bud charging in the case, it must read right in ear. */
    @Test fun realCaseRightInEarLeftInCase() {
        val ear = EarReport.decode(hex("04 00 04 00 06 00 00 02"))!!
        val bat = BudPower.from(hex("04 00 04 00 04 00 03 02 01 5B 02 01 04 01 5D 01 01 08 01 3C 02 01"))!!
        assertEquals(BudPower(left = 1, right = 2, firstIsLeft = false), bat)
        // battery first, then the ear report (the order the phone got them in when the link opened)
        val s = SideTracker()
        s.onBattery(bat, 1000); s.onEar(ear, 1012)
        assertEquals(false, s.primaryIsLeft)
        assertEquals("battery charging", s.source)
        assertEquals("Left: in case, Right: in ear", EarText.describe(ear, s.primaryIsLeft))
        // ear first, then battery
        val t = SideTracker()
        t.onEar(ear, 1000); t.onBattery(bat, 1100)
        assertEquals(false, t.primaryIsLeft)
        // a left bud in a closed case can be off the link (4) instead of charging: same answer
        val u = SideTracker()
        u.onEar(ear, 0); u.onBattery(BudPower(4, 2, false), 10)
        assertEquals(false, u.primaryIsLeft)
        // the mirror: left in the ear, right charging in the case
        val v = SideTracker()
        v.onEar(ear, 0); v.onBattery(BudPower(2, 1, true), 10)
        assertEquals(true, v.primaryIsLeft)
        assertEquals("Left: in ear, Right: in case", EarText.describe(ear, v.primaryIsLeft))
    }

    @Test fun batteryOrderOnlyWhenNothingElseSaid() {
        // both in ear (the real packet): charging can't tell, the order (right first) is the fallback
        val s = SideTracker()
        s.onEar(EarReport(Bud.IN_EAR, Bud.IN_EAR), 0); s.onBattery(BudPower.from(realBothInEar)!!, 10)
        assertEquals(false, s.primaryIsLeft); assertEquals("battery order", s.source)
        // an advert said otherwise: the order doesn't overrule it
        val t = SideTracker()
        t.onAdvert(true); t.onEar(EarReport(Bud.IN_EAR, Bud.OUT), 0); t.onBattery(BudPower.from(realBothInEar)!!, 10)
        assertEquals(true, t.primaryIsLeft)
        // but the charging states do (one bud in the case)
        t.onEar(EarReport(Bud.IN_EAR, Bud.IN_CASE), 20); t.onBattery(BudPower(1, 2, false), 30)
        assertEquals(false, t.primaryIsLeft)
    }

    @Test fun chargingCantTellWithoutExactlyOneInCase() {
        val s = SideTracker()
        s.onEar(EarReport(Bud.IN_CASE, Bud.IN_CASE), 0); s.onBattery(BudPower(1, 1, null), 10)
        assertNull(s.primaryIsLeft)
        // a stale battery report (older than FRESH_MS before the ear report) is not used for charging
        val t = SideTracker()
        t.onBattery(BudPower(1, 2, null), 0)
        t.onEar(EarReport(Bud.IN_EAR, Bud.IN_CASE), SideTracker.FRESH_MS + 1)
        assertNull(t.primaryIsLeft)
    }

    @Test fun mapsPrimaryToSides() {
        val r = ears(IN, CASE)
        assertEquals(IN to CASE, EarText.leftRight(r, true))
        assertEquals(CASE to IN, EarText.leftRight(r, false))
        assertNull(EarText.leftRight(r, null))
        assertEquals("Left: in ear, Right: in case", EarText.describe(r, true))
        assertEquals("Left: in case, Right: in ear", EarText.describe(r, false))
        assertEquals("Both in ear", EarText.describe(ears(IN, IN), null))
        assertEquals("One bud in ear, the other in case (which is left isn't known yet)", EarText.describe(r, null))
        assertEquals("One bud out of ear, the other in case (which is left isn't known yet)", EarText.describe(ears(CASE, OUT), null))
    }

    @Test fun sideSourcesAndRoleSwap() {
        val s = SideTracker()
        assertNull(s.primaryIsLeft)
        s.onAdvert(false)
        assertEquals(false, s.primaryIsLeft); assertEquals("Bluetooth advert", s.source)
        s.onAdvert(true)                                   // a newer observation replaces it
        assertEquals(true, s.primaryIsLeft)
        // left (primary) taken out: (out, in), then the roles swap and the report comes again as (in, out)
        s.onEar(ears(IN, IN), 0)
        s.onEar(ears(OUT, IN), 10_000)
        s.onEar(ears(IN, OUT), 10_300)
        assertEquals(false, s.primaryIsLeft)               // the right bud is primary now
        assertEquals("role swap", s.source)
        assertEquals("Left: out of ear, Right: in ear", EarText.describe(ears(IN, OUT), s.primaryIsLeft))
        // an advert between the two already said who is primary: no extra flip
        s.onEar(ears(OUT, IN), 20_000)
        s.onAdvert(true)
        s.onEar(ears(IN, OUT), 20_200)
        assertEquals(true, s.primaryIsLeft)
        // an exchange long apart is two real changes, not a swap
        s.onEar(ears(OUT, IN), 30_000)
        s.onEar(ears(IN, OUT), 40_000)
        assertEquals(true, s.primaryIsLeft)
        s.reset()
        assertNull(s.primaryIsLeft)
    }

    @Test fun freshnessText() {
        val clock = { _: Long -> "14:02" }
        assertEquals("(live, since 14:02)", EarText.freshness(0, 5_000, true, clock))
        assertEquals("(last seen just now)", EarText.freshness(0, 30_000, false, clock))
        assertEquals("(last seen 5 min ago)", EarText.freshness(0, 5 * 60_000, false, clock))
        assertEquals("(last seen 2 h ago)", EarText.freshness(0, 2 * 3_600_000 + 5, false, clock))
    }

    // ---- debounce
    @Test fun debounceHoldsAndDropsFlicker() {
        val d = Debouncer<EarReport>(400)
        assertEquals(400L, d.offer(ears(IN, IN), 0))
        assertNull(d.settle(399))
        assertEquals(ears(IN, IN), d.settle(400))
        // out and back in within 300 ms: nothing
        assertEquals(1400L, d.offer(ears(OUT, IN), 1000))
        assertNull(d.offer(ears(IN, IN), 1300))
        assertNull(d.settle(1400))
        assertEquals(ears(IN, IN), d.stable)
        // a change that holds
        assertEquals(2400L, d.offer(ears(OUT, IN), 2000))
        assertEquals(2400L, d.offer(ears(OUT, IN), 2200))   // a repeat doesn't restart the clock
        assertEquals(ears(OUT, IN), d.settle(2400))
        // a second change while one is pending restarts the clock
        d.offer(ears(OUT, OUT), 3000)
        assertEquals(3500L, d.offer(ears(CASE, OUT), 3100))
        assertNull(d.settle(3450))
        assertEquals(ears(CASE, OUT), d.settle(3500))
    }

    // ---- auto-pause / resume
    @Test fun firstReportIsOnlyTheBaseline() {
        val p = AutoPause()
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(OUT, IN), playing = true, now = 0, pauseOn = true, resumeOn = true))
    }

    @Test fun pausesAndResumesOneBud() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        assertEquals(AutoPause.Act.PAUSE, p.onEars(ears(OUT, IN), true, 1000, true, true))
        assertTrue(p.pausedByUs)
        assertEquals(AutoPause.Act.RESUME, p.onEars(ears(IN, IN), false, 30_000, true, true))
        assertFalse(p.pausedByUs)
    }

    @Test fun doesNothingWhenNotPlaying() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), false, 0, true, true)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(OUT, IN), false, 1000, true, true))
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, IN), false, 2000, true, true))   // we didn't pause it
    }

    @Test fun noResumeAfterTheWindow() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        p.onEars(ears(IN, OUT), true, 1000, true, true)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, IN), false, 1000 + AutoPause.RESUME_WINDOW_MS + 1, true, true))
    }

    @Test fun bothIntoTheCaseEndsResume() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        assertEquals(AutoPause.Act.PAUSE, p.onEars(ears(OUT, OUT), true, 1000, true, true))
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(CASE, CASE), false, 5000, true, true))
        assertFalse(p.pausedByUs)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, IN), false, 20_000, true, true))
    }

    @Test fun bothOutThenBothBackResumes() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        assertEquals(AutoPause.Act.PAUSE, p.onEars(ears(OUT, IN), true, 1000, true, true))
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(OUT, OUT), false, 2000, true, true))   // already paused
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, OUT), false, 3000, true, true))    // only one back of two
        assertTrue(p.pausedByUs)
        assertEquals(AutoPause.Act.RESUME, p.onEars(ears(IN, IN), false, 4000, true, true))
    }

    @Test fun youResumedYourselfSoWeDont() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        p.onEars(ears(OUT, IN), true, 1000, true, true)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, IN), true, 5000, true, true))
        assertFalse(p.pausedByUs)
    }

    @Test fun switchesAreRespected() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, false, true)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(OUT, IN), true, 1000, false, true))
        val q = AutoPause()
        q.onEars(ears(IN, IN), true, 0, true, false)
        assertEquals(AutoPause.Act.PAUSE, q.onEars(ears(OUT, IN), true, 1000, true, false))
        assertEquals(AutoPause.Act.NONE, q.onEars(ears(IN, IN), false, 2000, true, false))
        assertFalse(q.pausedByUs)
    }

    @Test fun unknownStatesNeverAct() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(Bud.UNKNOWN, IN), true, 1000, true, true))
    }

    @Test fun roleSwapIsNotAChange() {
        val p = AutoPause()
        p.onEars(ears(OUT, IN), false, 0, true, true)
        // the same buds, seen from the other one after the swap: one in ear before and after
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(IN, OUT), true, 500, true, true))
    }

    // ---- listening
    @Test fun listenGuardStopsAndRestarts() {
        val g = ListenGuard()
        assertEquals(ListenGuard.Act.NONE, g.onEars(ears(IN, OUT), true, true, 0, true, true))
        assertEquals(ListenGuard.Act.STOP, g.onEars(ears(OUT, CASE), true, true, 1000, true, true))
        assertTrue(g.stoppedFiltered)
        assertEquals(ListenGuard.Act.RESTART, g.onEars(ears(IN, CASE), false, false, 30_000, true, true))
        // restart off: stop only
        val h = ListenGuard()
        assertEquals(ListenGuard.Act.STOP, h.onEars(ears(OUT, OUT), true, false, 0, true, false))
        assertEquals(ListenGuard.Act.NONE, h.onEars(ears(IN, OUT), false, false, 1000, true, false))
        // not listening: nothing to stop or restart
        val k = ListenGuard()
        assertEquals(ListenGuard.Act.NONE, k.onEars(ears(OUT, OUT), false, false, 0, true, true))
        assertEquals(ListenGuard.Act.NONE, k.onEars(ears(IN, OUT), false, false, 100, true, true))
        // too late
        val m = ListenGuard()
        m.onEars(ears(OUT, OUT), true, true, 0, true, true)
        assertEquals(ListenGuard.Act.NONE, m.onEars(ears(IN, IN), false, false, AutoPause.RESUME_WINDOW_MS + 1, true, true))
    }

    // ---- the lid closes on a bud (seen live): ear report "unknown" for it, battery "disconnected"
    private val beforeLid = hex("04 00 04 00 04 00 03 02 01 58 02 01 04 01 5b 01 01 08 01 ff 04 01")
    private val afterLid = hex("04 00 04 00 04 00 03 02 01 58 02 01 04 01 00 04 01 08 01 00 04 01")

    @Test fun lidClosedOnTheLeftBud() {
        assertEquals(BudPower(left = 1, right = 2, firstIsLeft = false), BudPower.from(beforeLid))   // left charging
        assertEquals(BudPower(left = 4, right = 2, firstIsLeft = false), BudPower.from(afterLid))    // left off the link
        val s = SideTracker()
        s.onEar(EarReport.decode(hex("04 00 04 00 06 00 00 02"))!!, 0)        // right in ear, left in case
        s.onBattery(BudPower.from(beforeLid)!!, 10)
        assertEquals(false, s.primaryIsLeft)
        val closed = EarReport(Bud.IN_EAR, Bud.UNKNOWN)                        // the report after the lid closed
        s.onEar(closed, 5000)
        s.onBattery(BudPower.from(afterLid)!!, 5010)
        val shown = s.resolve(closed)
        assertEquals(EarReport(Bud.IN_EAR, Bud.CASE_CLOSED), shown)
        assertEquals("Left: in case (lid closed), Right: in ear", EarText.describe(shown, s.primaryIsLeft))
        // and with the battery report first
        val t = SideTracker()
        t.onEar(EarReport(Bud.IN_EAR, Bud.IN_CASE), 0); t.onBattery(BudPower.from(beforeLid)!!, 10)
        t.onBattery(BudPower.from(afterLid)!!, 5000); t.onEar(closed, 5010)
        assertEquals(EarReport(Bud.IN_EAR, Bud.CASE_CLOSED), t.resolve(closed))
    }

    @Test fun budThatWasOutAndDropsOffIsDisconnected() {
        val s = SideTracker()
        s.onEar(EarReport(Bud.IN_EAR, Bud.OUT), 0)
        s.onBattery(BudPower(2, 2, false), 10)                    // order fallback: right primary
        val r = EarReport(Bud.IN_EAR, Bud.UNKNOWN)
        s.onEar(r, 1000); s.onBattery(BudPower(4, 2, false), 1010)
        assertEquals(EarReport(Bud.IN_EAR, Bud.GONE), s.resolve(r))
        assertEquals("Left: disconnected, Right: in ear", EarText.describe(s.resolve(r), s.primaryIsLeft))
        // without battery news it stays unknown
        assertEquals(r, SideTracker().resolve(r))
    }

    @Test fun lidClosedCountsAsCaseForPause() {
        val p = AutoPause()
        p.onEars(ears(IN, IN), true, 0, true, true)
        assertEquals(AutoPause.Act.PAUSE, p.onEars(ears(OUT, OUT), true, 1000, true, true))
        assertEquals(AutoPause.Act.NONE, p.onEars(ears(Bud.CASE_CLOSED, CASE), false, 2000, true, true))
        assertFalse(p.pausedByUs)                                   // both in the case: no resume
        assertTrue(ears(Bud.CASE_CLOSED, Bud.CASE_CLOSED).bothInCase)
        assertTrue(ears(IN, Bud.CASE_CLOSED).known)
    }

    @Test fun lidClosedBeforeTheLinkOpened() {
        // the left bud went into the case and charged on an earlier link; then the link closed and reopened
        val s = SideTracker()
        s.onEar(EarReport(Bud.IN_EAR, Bud.IN_CASE), 0); s.onBattery(BudPower.from(beforeLid)!!, 10)
        s.reset()
        // the new link: the battery report first (left off the link), then "in ear / unknown"
        val closed = EarReport(Bud.IN_EAR, Bud.UNKNOWN)
        s.onBattery(BudPower.from(afterLid)!!, 1000); s.onEar(closed, 1005)
        assertEquals(false, s.primaryIsLeft)                       // right listed first
        assertEquals("Left: in case (lid closed), Right: in ear", EarText.describe(s.resolve(closed), s.primaryIsLeft))
    }

    @Test fun offTheLinkWithNoHistoryIsTheClosedCase() {
        // seen live after an app update: the lid was already closed, nothing remembered about the left bud
        val s = SideTracker()
        val closed = EarReport(Bud.IN_EAR, Bud.UNKNOWN)
        s.onBattery(BudPower.from(afterLid)!!, 0); s.onEar(closed, 8)
        assertEquals("Left: in case (lid closed), Right: in ear", EarText.describe(s.resolve(closed), s.primaryIsLeft))
    }
}
