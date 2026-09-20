package com.example.amped3controller

import org.junit.Assert.*
import org.junit.Test

class AmpedProtocolTest {
    @Test fun decodeActualAmpStartup() {
        val b=AmpedProtocol.decodeHex("16000034683c3a2b51334c37417f6500000000000000000000010203000100000100000001000000000000000d00000000000000000000000000000000000000")
        val c=AmpedProtocol.chunk(b)!!
        assertFalse(c.cab);assertEquals(52,c.values.size)
        assertEquals(listOf(104,60,58,43,81,51,76,55,65,127),c.values.take(10))
        assertEquals(2,c.values[22])
    }
    @Test fun decodeActualCabTailBeforeHead() {
        val b=AmpedProtocol.decodeHex("a93c00180100000000670151000080000072000000900000007c016f000000000000000000000000000000000000000000000000000000000000000000000000")
        val c=AmpedProtocol.chunk(b)!!
        assertTrue(c.cab);assertEquals(60,c.offset);assertEquals(24,c.values.size)
        assertEquals(128,c.values[10]);assertEquals(114,c.values[13])
    }
    @Test fun ignoresMalformedOrUnrelatedReports() {
        assertNull(AmpedProtocol.chunk(ByteArray(3)))
        assertNull(AmpedProtocol.chunk(AmpedProtocol.packet(0x16,51,0,2,1,2)))
        assertNull(AmpedProtocol.chunk(AmpedProtocol.packet(0xa9,0,0,61)))
        assertNull(AmpedProtocol.chunk(AmpedProtocol.packet(2,0x14,1,23)))
        assertNull(AmpedProtocol.chunk(AmpedProtocol.packet(0xa9,255,255,1)))
    }
    @Test fun writesOnlyDocumentedBytesAndZeroPadding() {
        val p=AmpedProtocol.parameter(true,73,114)
        assertArrayEquals(byteArrayOf(0xa9.toByte(),73,0,1,114),p.take(5).toByteArray())
        assertTrue(p.drop(5).all { it==0.toByte() })
        assertEquals(64,p.size)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfBoundsParameter(){AmpedProtocol.parameter(false,52,0)}
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfBoundsValue(){AmpedProtocol.parameter(true,70,256)}
}
