package com.example.amped3controller

import org.junit.Assert.*
import org.junit.Test

class HardwareSavePacketTest {
    @Test fun encodesListAsUnsignedBytesWithoutArrayCopy() {
        val values = List(52) { (it * 7) % 256 }
        val b = AmpedProtocol.saveAmpPacket(3, values)
        assertArrayEquals(byteArrayOf(2, 0x13, 3, 52), b.copyOfRange(0, 4))
        assertEquals(values, b.copyOfRange(4, 56).map { it.toInt() and 255 })
        assertTrue(b.drop(56).all { it == 0.toByte() })
    }
    @Test fun namesRespectStorageLimitsAndPadding() {
        val b = AmpedProtocol.saveNamePacket(true, 2, "Modern USA")
        assertArrayEquals(byteArrayOf(2, 2, 2, 10), b.copyOfRange(0, 4))
        assertEquals("Modern USA", b.copyOfRange(4, 14).toString(Charsets.US_ASCII))
        assertTrue(b.drop(14).all { it == 0.toByte() })
    }
    @Test(expected=IllegalArgumentException::class) fun refusesLongCabName() {
        AmpedProtocol.saveNamePacket(true, 1, "x".repeat(22))
    }
    @Test(expected=IllegalArgumentException::class) fun refusesUnknownAmpValues() {
        AmpedProtocol.saveAmpPacket(1, List(52) { -1 })
    }
}
