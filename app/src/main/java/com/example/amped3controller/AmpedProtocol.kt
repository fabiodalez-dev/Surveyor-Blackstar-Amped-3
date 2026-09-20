package com.example.amped3controller

/** AMPED 3 firmware 1.03, captured from Architect 2.1.3. All offsets are unsigned. */
object AmpedProtocol {
    const val VID = 0x27d4
    const val PID = 0x0072

    /* CabRig offsets verified on the hardware on 2026-09-20: one Architect control was moved
       at a time while capturing the a9 reports it sent. The two cut frequencies used to be
       swapped in the UI; the factory eqPresets below corroborate the corrected pairing, since
       "Cocked Wah" cuts lows hard (67 high) and highs hard (83 low). */
    const val CAB_CABINET_LEVEL = 3
    const val CAB_ROOM_LEVEL = 57
    const val CAB_MASTER_LEVEL = 65
    const val CAB_LOW_CUT_ON = 66
    const val CAB_LOW_CUT_FREQ = 67
    const val CAB_EQ_BANDS = 70
    const val CAB_HIGH_CUT_ON = 82
    const val CAB_HIGH_CUT_FREQ = 83
    val cabEqBands = listOf(70, 73, 77, 81)
    /** CabRig offsets a local preset restores: the four EQ bands, both cut filters and the two
     *  levels whose meaning is verified. The Master level is deliberately excluded, like the
     *  amplifier's Master: loading a preset should not change how loud the rig is. */
    val cabPresetOffsets = cabEqBands + listOf(
        CAB_CABINET_LEVEL, CAB_ROOM_LEVEL, CAB_LOW_CUT_ON, CAB_LOW_CUT_FREQ, CAB_HIGH_CUT_ON, CAB_HIGH_CUT_FREQ)
    /* AMP offsets 0..9 verified the same day by correlating the live block with the values
       Architect displayed for each knob; every knob matched exactly one offset. */
    /** Cabinet and Room level bytes as decibels. Calibrated on 2026-09-21 by stepping the
     *  Architect sliders one notch at a time and pairing each readout with the byte sent:
     *  77 -> 2.6 dB, 85 -> 4.1 dB, 71 -> 1.4 dB, 76 -> 2.4 dB and every step between.
     *  The Master level (offset 65) uses a different, non-linear fader taper and is left raw. */
    fun levelDb(raw: Int) = raw * 24.0 / 127.0 - 12.0

    const val AMP_STATUS = 32
    const val AMP_BOOST_BIT = 64
    const val AMP_REVERB_ON = 33
    fun packet(vararg values: Int): ByteArray {
        require(values.size <= 64 && values.all { it in 0..255 })
        return ByteArray(64).also { b -> values.forEachIndexed { i, v -> b[i] = v.toByte() } }
    }
    fun parameter(cab: Boolean, offset: Int, value: Int): ByteArray {
        require(offset in 0 until if (cab) 84 else 52)
        return packet(if (cab) 0xa9 else 0x16, offset, 0, 1, value)
    }
    fun saveAmpPacket(slot: Int, values: List<Int>): ByteArray {
        require(slot in 1..3 && values.size == 52 && values.all { it in 0..255 })
        return packet(2, 0x13, slot, 52, *values.toIntArray())
    }
    fun saveNamePacket(cab: Boolean, slot: Int, name: String): ByteArray {
        require(slot in 1..3)
        val limit = if (cab) 21 else 23
        require(name.isNotBlank() && name.length <= limit && name.all { it.code in 32..126 }) {
            T("Nome: da 1 a $limit caratteri ASCII stampabili", "Name: 1 to $limit printable ASCII characters")
        }
        return packet(2, if (cab) 2 else 0x12, slot, name.length, *name.map { it.code }.toIntArray())
    }
    val eqPresets = mapOf(
        "Flattened" to mapOf(70 to 159, 73 to 145, 77 to 127, 81 to 109, 66 to 0, 82 to 1, 67 to 0, 83 to 127),
        "Cab In The Room" to mapOf(70 to 145, 73 to 99, 77 to 127, 81 to 154, 66 to 1, 82 to 1, 67 to 130, 83 to 106),
        "Focussed" to mapOf(70 to 164, 73 to 100, 77 to 127, 81 to 127, 66 to 1, 82 to 0, 67 to 64, 83 to 255),
        "Bass Boost" to mapOf(70 to 127, 73 to 127, 77 to 159, 81 to 179, 66 to 0, 82 to 1, 67 to 0, 83 to 132),
        "Treble Boost" to mapOf(70 to 127, 73 to 99, 77 to 73, 81 to 127, 66 to 0, 82 to 0, 67 to 0, 83 to 255),
        "Mid Scoop" to mapOf(70 to 102, 73 to 168, 77 to 255, 81 to 71, 66 to 1, 82 to 1, 67 to 64, 83 to 75),
        "Cocked Wah" to mapOf(70 to 0, 73 to 214, 77 to 170, 81 to 127, 66 to 1, 82 to 1, 67 to 255, 83 to 17),
        "Lo-Fi" to mapOf(70 to 127, 73 to 127, 77 to 127, 81 to 70, 66 to 0, 82 to 1, 67 to 0, 83 to 93),
        "Dynamic 57" to mapOf(70 to 102, 73 to 127, 77 to 127, 81 to 89, 66 to 0, 82 to 1, 67 to 0, 83 to 107),
        "Dynamic 421" to mapOf(70 to 127, 73 to 127, 77 to 127, 81 to 95, 66 to 0, 82 to 0, 67 to 0, 83 to 255),
        "Condenser 67" to mapOf(70 to 63, 73 to 127, 77 to 127, 81 to 127, 66 to 0, 82 to 1, 67 to 0, 83 to 120),
        "Condenser 414" to mapOf(70 to 51, 73 to 127, 77 to 127, 81 to 127, 66 to 0, 82 to 1, 67 to 0, 83 to 134),
        "Ribbon 121" to mapOf(70 to 89, 73 to 127, 77 to 127, 81 to 127, 66 to 0, 82 to 1, 67 to 0, 83 to 134),
        "Ribbon 160" to mapOf(70 to 89, 73 to 127, 77 to 127, 81 to 127, 66 to 0, 82 to 1, 67 to 0, 83 to 134)
    )
    fun decodeHex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun chunk(report: ByteArray): Chunk? {
        if (report.size != 64) return null
        val command = report[0].toInt() and 255
        if (command != 0x16 && command != 0xa9) return null
        val offset = (report[1].toInt() and 255) or ((report[2].toInt() and 255) shl 8)
        val count = report[3].toInt() and 255
        val size = if (command == 0x16) 52 else 84
        if (count !in 1..60 || offset + count > size) return null
        return Chunk(command == 0xa9, offset, report.copyOfRange(4, 4 + count).map { it.toInt() and 255 })
    }
    data class Chunk(val cab: Boolean, val offset: Int, val values: List<Int>)
    val cabinetNames = listOf("DI", "1x10 Classic USA Combo", "1x12 Vintage USA Alnico Combo", "1x12 Vintage USA Ceramic Combo", "1x12 Classic USA Alnico Combo", "1x12 Classic USA Ceramic Combo", "1x12 Modern USA Combo", "1x12 Classic USA Open Back", "2x10 Classic USA Combo", "2x12 Vintage USA Combo", "2x12 Modern USA Combo", "2x12 Vintage Closed Back", "2x12 Classic UK Combo", "2x12 Classic USA Open Back", "2x12 Classic USA Closed Back", "2x12 Vintage Stack", "4x10 Vintage USA Open Back", "4x12 Vintage Oversized UK", "4x12 Vintage UK", "4x12 Modern UK", "4x12 Classic UK", "4x12 Modern USA", "4x12 Modern UK Ceramic", "4x12 Modern UK Neo")
}

data class AmpState(
    val connected: Boolean = false,
    val synced: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val amp: List<Int> = List(52) { -1 },
    val cab: List<Int> = List(84) { -1 },
    val ampSlot: Int = 0,
    val cabSlot: Int = 0,
    val ampNames: Map<Int, String> = emptyMap(),
    val cabNames: Map<Int, String> = emptyMap(),
    val logs: String = ""
)
