package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartMachineTest {
    private var now = 1_000_000L
    private val map = mutableMapOf(
        Act.STILL to "anc", Act.WALKING to "transparency", Act.RUNNING to "transparency",
        Act.ON_BICYCLE to "transparency", Act.IN_VEHICLE to "anc", Act.ON_CALL to "transparency")
    private fun machine() = SmartMachine({ now }, { map.getValue(it) })

    @Test fun nothingKnownDoesNothing() {
        val m = machine()
        assertNull(m.poll()); assertNull(m.nextCheckInMs())
    }

    @Test fun actsOnlyAfterTheHold() {
        val m = machine()
        m.onActivity(Act.WALKING)
        assertNull(m.poll())
        now += 19_000; assertNull(m.poll())
        assertEquals(1_000L, m.nextCheckInMs())
        now += 1_000; assertEquals("transparency", m.poll())
        assertNull(m.poll())                     // already applied
    }

    @Test fun aBlipThatDoesNotHoldIsIgnored() {
        val m = machine()
        m.onActivity(Act.STILL); now += 21_000; assertEquals("anc", m.poll())
        now += 40_000
        m.onActivity(Act.WALKING); now += 10_000
        m.onActivity(Act.STILL)                  // back before 20 s
        now += 30_000
        assertNull(m.poll())                     // still maps to what is applied
        assertNull(m.nextCheckInMs())
    }

    @Test fun atMostOneSwitchPerThirtySeconds() {
        val m = machine()
        m.onActivity(Act.STILL); now += 20_000; assertEquals("anc", m.poll())
        m.onActivity(Act.WALKING); now += 20_000      // held 20 s but only 20 s since the last switch
        assertNull(m.poll())
        assertEquals(10_000L, m.nextCheckInMs())
        now += 10_000; assertEquals("transparency", m.poll())
    }

    @Test fun sameActionActivitiesDoNotSwitch() {
        val m = machine()
        m.onActivity(Act.WALKING); now += 20_000; assertEquals("transparency", m.poll())
        m.onActivity(Act.RUNNING); now += 60_000
        assertNull(m.poll())
    }

    @Test fun aCallWinsAndIsHeldToo() {
        val m = machine()
        map[Act.WALKING] = "anc"
        m.onActivity(Act.WALKING); now += 20_000; assertEquals("anc", m.poll())
        now += 40_000
        m.onCall(true); assertNull(m.poll())
        now += 20_000; assertEquals("transparency", m.poll())
        m.onCall(false); now += 30_000
        assertEquals("anc", m.poll())            // back to the activity's choice
    }

    @Test fun mappingEditsApplyAtOnce() {
        val m = machine()
        m.onActivity(Act.IN_VEHICLE); now += 20_000; assertEquals("anc", m.poll())
        map[Act.IN_VEHICLE] = "adaptive:30"
        now += 30_000; assertEquals("adaptive:30", m.poll())
    }

    @Test fun statusLine() {
        val m = machine()
        assertEquals("waiting for the first activity reading", m.status())
        m.onActivity(Act.WALKING)
        assertEquals("now: walking -> transparency", m.status())
    }

    @Test fun stateSurvivesARestart() {
        val a = machine(); a.onActivity(Act.STILL); now += 5_000
        val b = SmartMachine({ now }, { map.getValue(it) }, state = a.state)
        now += 15_000; assertEquals("anc", b.poll())
    }
}

class EqHeadroomTest {
    private val bands = listOf(
        EqModel.Band("bass", "Bass", "lowshelf", 100.0, 0.7),
        EqModel.Band("mid", "Mid", "peaking", 1000.0, 1.0),
        EqModel.Band("treble", "Treble", "highshelf", 8000.0, 0.7))

    @Test fun flatNeedsNoHeadroom() = assertEquals(0.0, EqModel.preamp(bands, listOf(0.0, 0.0, 0.0)), 0.0)

    @Test fun halfThePeakInHalfDbSteps() {
        val g = listOf(6.0, 0.0, 0.0)
        val peak = EqModel.curvePeak(bands, g)
        val p = EqModel.preamp(bands, g)
        assertEquals(-Math.round(0.5 * peak * 2) / 2.0, p, 0.0)
        assertEquals(true, p < 0.0 && p > -6.0)       // less than the old "-max(gain)"
        assertEquals(0.0, (p * 2) - Math.rint(p * 2), 0.0)
    }

    @Test fun cutsNeedNoHeadroom() = assertEquals(0.0, EqModel.preamp(bands, listOf(-4.0, -2.0, -3.0)), 0.0)
}
