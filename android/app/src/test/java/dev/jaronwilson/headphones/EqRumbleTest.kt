package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Rumble preset on the shared six-band model (same numbers as the laptop's hp_eq.py). */
class EqRumbleTest {
    private val bands = listOf(
        EqModel.Band("bass", "Bass", "lowshelf", 80.0, 0.707),
        EqModel.Band("lowmid", "Low-mid", "peaking", 250.0, 1.0),
        EqModel.Band("mid", "Mid", "peaking", 1000.0, 1.0),
        EqModel.Band("highmid", "High-mid", "peaking", 3500.0, 1.0),
        EqModel.Band("treble", "Treble", "highshelf", 10000.0, 0.707),
        EqModel.Band("sub", "Sub", "peaking", 45.0, 0.8))
    private val rumble = listOf(4.0, 0.0, 0.0, 0.0, 0.0, 10.0)

    @Test fun rumbleHeadroomMatchesLaptop() = assertEquals(-6.5, EqModel.preamp(bands, rumble), 0.0)

    @Test fun subBandPeaksNear45Hz() {
        val at45 = EqModel.responseDb(bands, rumble, 45.0)
        assertTrue(at45 > 12.0)
        assertTrue(at45 > EqModel.responseDb(bands, rumble, 100.0))
    }

    @Test fun subsonicCutsBelow25HzOnlyWhenSubBoosted() {
        val withSub = EqModel.responseDb(bands, rumble, 15.0)
        val noSub = EqModel.responseDb(bands, listOf(4.0, 0.0, 0.0, 0.0, 0.0, 0.0), 15.0)
        assertTrue("high-pass should cut 15 Hz", withSub < EqModel.responseDb(bands, rumble, 45.0) - 10)
        assertTrue("no high-pass without the sub boost", noSub > 3.5)
    }

    @Test fun oldFiveBandGainsStillWork() =
        assertEquals(EqModel.preamp(bands, listOf(6.0, 2.0, 0.0, 0.0, 0.0)),
            EqModel.preamp(bands, listOf(6.0, 2.0, 0.0, 0.0, 0.0, 0.0)), 0.0)
}
