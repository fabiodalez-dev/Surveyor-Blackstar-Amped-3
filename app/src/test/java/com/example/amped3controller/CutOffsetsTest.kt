package com.example.amped3controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two CabRig cut frequencies were swapped in the UI until 2026-09-20: the slider labelled
 * Low-Cut wrote the high-cut offset and vice versa. Moving one Architect control at a time while
 * capturing its reports showed 67 is the low cut and 83 the high cut. The factory EQ presets,
 * extracted from Architect, say the same thing independently, so they are used here as the oracle.
 */
class CutOffsetsTest {
    private fun preset(name: String) = AmpedProtocol.eqPresets.getValue(name)

    @Test
    fun cutOffsetsAreNotSwapped() {
        assertEquals(67, AmpedProtocol.CAB_LOW_CUT_FREQ)
        assertEquals(83, AmpedProtocol.CAB_HIGH_CUT_FREQ)
        assertEquals(66, AmpedProtocol.CAB_LOW_CUT_ON)
        assertEquals(82, AmpedProtocol.CAB_HIGH_CUT_ON)
    }

    @Test
    fun neutralPresetLeavesLowCutAtBottomAndHighCutAtTop() {
        // Treble Boost switches both filters off: an unused low cut sits at its minimum
        // frequency and an unused high cut at its maximum. Swap the offsets and this inverts.
        val p = preset("Treble Boost")
        assertEquals(0, p.getValue(AmpedProtocol.CAB_LOW_CUT_ON))
        assertEquals(0, p.getValue(AmpedProtocol.CAB_HIGH_CUT_ON))
        assertEquals(0, p.getValue(AmpedProtocol.CAB_LOW_CUT_FREQ))
        assertEquals(255, p.getValue(AmpedProtocol.CAB_HIGH_CUT_FREQ))
    }

    @Test
    fun cockedWahKeepsOnlyMidrange() {
        // A cocked wah is a narrow midrange band: both filters on, lows cut aggressively
        // (high low-cut frequency) and highs cut aggressively (low high-cut frequency).
        val p = preset("Cocked Wah")
        assertEquals(1, p.getValue(AmpedProtocol.CAB_LOW_CUT_ON))
        assertEquals(1, p.getValue(AmpedProtocol.CAB_HIGH_CUT_ON))
        assertTrue(p.getValue(AmpedProtocol.CAB_LOW_CUT_FREQ) > 200)
        assertTrue(p.getValue(AmpedProtocol.CAB_HIGH_CUT_FREQ) < 50)
    }

    @Test
    fun everyEqPresetAddressesKnownCabRigOffsets() {
        val known = (AmpedProtocol.cabEqBands + listOf(
            AmpedProtocol.CAB_LOW_CUT_ON, AmpedProtocol.CAB_LOW_CUT_FREQ,
            AmpedProtocol.CAB_HIGH_CUT_ON, AmpedProtocol.CAB_HIGH_CUT_FREQ)).toSet()
        AmpedProtocol.eqPresets.forEach { (name, values) ->
            assertEquals("$name touches unexpected offsets", known, values.keys.toSet())
            values.forEach { (offset, value) ->
                assertTrue("$name offset $offset out of range", value in 0..255)
                assertTrue("$name offset $offset outside the 84-byte block", offset in 0 until 84)
            }
        }
    }
}
