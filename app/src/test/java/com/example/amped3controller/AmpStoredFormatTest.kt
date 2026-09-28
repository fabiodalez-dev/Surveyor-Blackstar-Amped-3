package com.example.amped3controller

import org.junit.Assert.*
import org.junit.Test

/**
 * The three AMP slots as the pedal stored and recalled them during the storage verification of
 * 2026-09-20 (backups/storage-verification-20260920.json): the 15-byte stored record next to the
 * live block the same slot produced when recalled. Real bytes, not invented ones.
 */
class AmpStoredFormatTest {
    private fun live(knobs: List<Int>, power: Int, response: Int, od: Int, status: Int, x41: Int) =
        MutableList(52) { 0 }.also { a ->
            knobs.forEachIndexed { i, v -> a[i] = v }
            a[9] = 110; a[10] = 102; a[21] = power; a[22] = response; a[23] = 2; a[25] = 1
            a[28] = od; a[32] = status; a[40] = 13; a[41] = x41
        }
    private val slots = listOf(
        "683c3a2b51334c3741000102000001" to live(listOf(104,60,58,43,81,51,76,55,65), 1, 2, 1, 1, 0),
        "4f7f1f4a5e31433948000103000001" to live(listOf(79,127,31,74,94,49,67,57,72), 1, 3, 1, 8, 1),
        "5a7f7d4a3c31433451000101000000" to live(listOf(90,127,125,74,60,49,67,52,81), 1, 1, 0, 32, 2))

    private fun stored(hex: String) = AmpedProtocol.decodeHex(hex).map { it.toInt() and 255 }

    @Test fun everyRecordedSlotMatchesItsRecalledLiveState() {
        slots.forEach { (hex, amp) -> assertTrue(hex, AmpedProtocol.ampStoredMatches(stored(hex), amp)) }
    }

    @Test fun verificationCoversEveryStoredByte() {
        assertEquals((0..14).toSet(), AmpedProtocol.ampStoredOffsets.keys)
        val (hex, amp) = slots[1]
        // one changed live offset per stored byte, as found by tools/verify_amp_stored_bytes.py
        for ((live, value) in listOf(22 to 2, 24 to 1, 25 to 0, 26 to 1, 27 to 1, 28 to 0, 4 to 95))
            assertFalse("offset $live", AmpedProtocol.ampStoredMatches(stored(hex), amp.toMutableList().also { it[live] = value }))
    }

    @Test fun powerMasterReverbOnAndBoostAreNotStored() {
        val (hex, amp) = slots[0]
        for ((live, value) in listOf(9 to 20, 21 to 3, 33 to 1, 32 to 65))
            assertTrue("offset $live", AmpedProtocol.ampStoredMatches(stored(hex), amp.toMutableList().also { it[live] = value }))
    }

    @Test fun verifyAmpChecksNameAndData() {
        val (hex, amp) = slots[0]
        val records = mapOf("20:23" to "436c65616e000000000000000000000000000000000000", "21:15" to hex)
        assertTrue(Recovery.verifyAmp(records, "Clean", amp))
        assertFalse(Recovery.verifyAmp(records, "Crunch", amp))
        assertFalse(Recovery.verifyAmp(records, "Clean", amp.toMutableList().also { it[0] = 105 }))
    }

    @Test fun boostIsTheOnlyPartOfTheStatusByteThatCounts() {
        assertTrue(AmpedProtocol.accepted(false, AmpedProtocol.AMP_STATUS, 64 or 1, 64 or 8))
        assertFalse(AmpedProtocol.accepted(false, AmpedProtocol.AMP_STATUS, 64, 8))
        assertTrue(AmpedProtocol.accepted(true, 5, 1, 1))
        assertFalse(AmpedProtocol.accepted(true, 5, 1, 0))
        assertFalse(AmpedProtocol.accepted(true, AmpedProtocol.AMP_STATUS, 64 or 1, 64 or 8))
    }
}
