package com.example.amped3controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

/**
 * Cabinet and Room levels were calibrated on the hardware on 2026-09-21 by stepping the Architect
 * sliders one notch at a time and pairing each readout with the byte that went out on the wire.
 * Those pairs are the oracle here, so a change to the conversion has to answer to them.
 */
class CabRigLevelsTest {
    private fun shown(raw: Int) = round(AmpedProtocol.levelDb(raw) * 10) / 10

    @Test
    fun conversionReproducesEveryMeasuredReadout() {
        val measured = mapOf(
            77 to 2.6, 78 to 2.7, 79 to 2.9, 80 to 3.1, 81 to 3.3,
            82 to 3.5, 83 to 3.7, 84 to 3.9, 85 to 4.1,          // cabinet level sweep
            71 to 1.4, 72 to 1.6, 73 to 1.8, 74 to 2.0, 75 to 2.2, 76 to 2.4)  // room level sweep
        measured.forEach { (raw, db) -> assertEquals("raw $raw", db, shown(raw), 0.0001) }
    }

    @Test
    fun scaleSpansTwelveDecibelsEachWay() {
        assertEquals(-12.0, AmpedProtocol.levelDb(0), 0.0001)
        assertEquals(12.0, AmpedProtocol.levelDb(127), 0.0001)
        assertEquals(0.0, AmpedProtocol.levelDb(64), 0.1)
    }

    @Test
    fun presetRestoreCoversTheExposedControlsButNotMasterVolume() {
        val offsets = AmpedProtocol.cabPresetOffsets
        assertTrue(offsets.containsAll(AmpedProtocol.cabEqBands))
        assertTrue(offsets.contains(AmpedProtocol.CAB_CABINET_LEVEL))
        assertTrue(offsets.contains(AmpedProtocol.CAB_ROOM_LEVEL))
        assertTrue(offsets.contains(AmpedProtocol.CAB_LOW_CUT_FREQ))
        assertTrue(offsets.contains(AmpedProtocol.CAB_HIGH_CUT_FREQ))
        assertFalse("loading a preset must not change how loud the rig is",
            offsets.contains(AmpedProtocol.CAB_MASTER_LEVEL))
        assertEquals("no duplicates", offsets.size, offsets.toSet().size)
        offsets.forEach { assertTrue("offset $it outside the 84-byte block", it in 0 until 84) }
    }
}
