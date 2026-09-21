package com.example.amped3controller.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The CabRig coefficient payload and the maths that describes what it does.
 *
 * A profile is 260 bytes: 65 little-endian float32 values, one direct term plus sixteen sections
 * of (b0, b1, a1, a2). It travels as one 0xAA header report plus five 0xAC data reports, each 64
 * bytes with a big-endian CRC-16/XMODEM of its own payload; the header repeats the payload CRC in
 * little-endian order with the length and the chunk count.
 *
 * The response model, H = v0 + sum (b0 + b1 z) / (1 + a1 z + a2 z^2) with z = e^-jw, is inferred
 * from the payload layout and from Architect's coefficient routine. It has never been checked
 * against a measured sweep, so everything derived from it is modelled, not measured.
 */
object CabProfile {
    const val SAMPLE_RATE = 48000.0   // assumed, not read off the hardware
    const val SECTIONS = 16
    const val VALUES = 65
    const val PAYLOAD_BYTES = 260

    fun crc16(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var r = 0
        for (i in from until until) {
            r = r xor ((data[i].toInt() and 255) shl 8)
            repeat(8) { r = if (r and 0x8000 != 0) ((r shl 1) xor 0x1021) and 0xffff else (r shl 1) and 0xffff }
        }
        return r
    }

    /** One 64-byte report: opcode, big-endian payload CRC, payload length, payload, zero padding. */
    fun report(opcode: Int, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= 60)
        val out = ByteArray(64)
        val c = crc16(payload)
        out[0] = opcode.toByte(); out[1] = (c shr 8).toByte(); out[2] = c.toByte(); out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** Payload of a report, verifying its own CRC. Null when the report is malformed. */
    fun unwrap(hex: String, opcode: Int): ByteArray? {
        val b = runCatching { hexToBytes(hex) }.getOrNull() ?: return null
        if (b.size != 64 || (b[0].toInt() and 255) != opcode) return null
        val length = b[3].toInt() and 255
        if (length !in 1..60) return null
        val payload = b.copyOfRange(4, 4 + length)
        val stated = ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)
        return if (stated == crc16(payload)) payload else null
    }

    /** The 65 coefficients of a profile, or null if header, chunks or either CRC layer disagree. */
    fun decode(header: String, chunks: List<String>): FloatArray? {
        val head = unwrap(header, 0xaa) ?: return null
        if (head.size != 12) return null
        val blocks = chunks.map { unwrap(it, 0xac) ?: return null }
        if (blocks.map { it.size } != listOf(60, 60, 60, 60, 20)) return null
        val data = ByteArray(PAYLOAD_BYTES)
        var at = 0
        for (b in blocks) { b.copyInto(data, at); at += b.size }
        if (le16(head, 2) != PAYLOAD_BYTES || le16(head, 0) != crc16(data) || le16(head, 4) != blocks.size) return null
        val values = FloatArray(VALUES) { i ->
            var bits = 0
            for (k in 3 downTo 0) bits = (bits shl 8) or (data[i * 4 + k].toInt() and 255)
            Float.fromBits(bits)
        }
        return if (values.all { it.isFinite() }) values else null
    }

    /** Rebuilds header and chunks around new coefficients, keeping the template's header tail. */
    fun encode(templateHeader: String, values: FloatArray): Pair<String, List<String>>? {
        require(values.size == VALUES)
        if (!values.all { it.isFinite() }) return null
        val head = unwrap(templateHeader, 0xaa)?.copyOf() ?: return null
        if (head.size != 12) return null
        val data = ByteArray(PAYLOAD_BYTES)
        for (i in 0 until VALUES) {
            val bits = values[i].toRawBits()
            for (k in 0 until 4) data[i * 4 + k] = (bits shr (8 * k)).toByte()
        }
        val chunks = (0 until PAYLOAD_BYTES step 60).map {
            bytesToHex(report(0xac, data.copyOfRange(it, minOf(it + 60, PAYLOAD_BYTES))))
        }
        putLe16(head, 0, crc16(data)); putLe16(head, 2, PAYLOAD_BYTES); putLe16(head, 4, chunks.size)
        return bytesToHex(report(0xaa, head)) to chunks
    }

    fun quantise(values: DoubleArray): FloatArray = FloatArray(VALUES) { values[it].toFloat() }

    // --- response ---------------------------------------------------------------------------

    /** |H| at a normalised angular frequency, linear. */
    fun magnitude(v: FloatArray, omega: Double): Double {
        val zr = cos(-omega); val zi = sin(-omega)
        val z2r = zr * zr - zi * zi; val z2i = 2 * zr * zi
        var re = v[0].toDouble(); var im = 0.0
        for (s in 0 until SECTIONS) {
            val b0 = v[1 + 4 * s].toDouble(); val b1 = v[2 + 4 * s].toDouble()
            val a1 = v[3 + 4 * s].toDouble(); val a2 = v[4 + 4 * s].toDouble()
            val nr = b0 + b1 * zr; val ni = b1 * zi
            val dr = 1 + a1 * zr + a2 * z2r; val di = a1 * zi + a2 * z2i
            val den = dr * dr + di * di
            if (den == 0.0) return Double.POSITIVE_INFINITY
            re += (nr * dr + ni * di) / den
            im += (ni * dr - nr * di) / den
        }
        return sqrt(re * re + im * im)
    }

    fun magnitudeAtHz(v: FloatArray, hz: Double) = magnitude(v, 2 * Math.PI * hz / SAMPLE_RATE)

    /**
     * Gain at exactly DC, computed in closed form. Evaluating the general expression at z=1 hides
     * a section whose denominator is zero, which is exactly the case worth catching: such a
     * section is an integrator and the profile has infinite DC gain.
     */
    fun dcGain(v: FloatArray): Double {
        var sum = v[0].toDouble()
        for (s in 0 until SECTIONS) {
            val d = 1 + v[3 + 4 * s].toDouble() + v[4 + 4 * s].toDouble()
            if (d == 0.0) return Double.POSITIVE_INFINITY
            sum += (v[1 + 4 * s].toDouble() + v[2 + 4 * s].toDouble()) / d
        }
        return abs(sum)
    }

    fun nyquistGain(v: FloatArray): Double {
        var sum = v[0].toDouble()
        for (s in 0 until SECTIONS) {
            val d = 1 - v[3 + 4 * s].toDouble() + v[4 + 4 * s].toDouble()
            if (d == 0.0) return Double.POSITIVE_INFINITY
            sum += (v[1 + 4 * s].toDouble() - v[2 + 4 * s].toDouble()) / d
        }
        return abs(sum)
    }

    /** Impulse response of the parallel bank, in double precision. */
    fun impulse(v: FloatArray, n: Int): DoubleArray {
        val h = DoubleArray(n)
        if (n > 0) h[0] = v[0].toDouble()
        for (s in 0 until SECTIONS) {
            val b0 = v[1 + 4 * s].toDouble(); val b1 = v[2 + 4 * s].toDouble()
            val a1 = v[3 + 4 * s].toDouble(); val a2 = v[4 + 4 * s].toDouble()
            var y1 = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val x0 = if (i == 0) b0 else if (i == 1) b1 else 0.0
                val y = x0 - a1 * y1 - a2 * y2
                h[i] += y; y2 = y1; y1 = y
            }
        }
        return h
    }

    /** The same recursion in float32, to expose cancellations that only the state precision sees. */
    fun impulseFloat(v: FloatArray, n: Int): FloatArray {
        val h = FloatArray(n)
        if (n > 0) h[0] = v[0]
        for (s in 0 until SECTIONS) {
            val b0 = v[1 + 4 * s]; val b1 = v[2 + 4 * s]; val a1 = v[3 + 4 * s]; val a2 = v[4 + 4 * s]
            var y1 = 0f; var y2 = 0f
            for (i in 0 until n) {
                val x0 = if (i == 0) b0 else if (i == 1) b1 else 0f
                val y = x0 - a1 * y1 - a2 * y2
                h[i] += y; y2 = y1; y1 = y
            }
        }
        return h
    }

    /** Smallest 1+a1+a2 and 1-a1+a2 over the sections: distance of the poles from z=1 and z=-1. */
    fun poleMargins(v: FloatArray): Pair<Double, Double> {
        var dc = Double.MAX_VALUE; var ny = Double.MAX_VALUE
        for (s in 0 until SECTIONS) {
            val a1 = v[3 + 4 * s].toDouble(); val a2 = v[4 + 4 * s].toDouble()
            dc = minOf(dc, 1 + a1 + a2); ny = minOf(ny, 1 - a1 + a2)
        }
        return dc to ny
    }

    fun samePoleBank(a: FloatArray, b: FloatArray): Boolean =
        (0 until SECTIONS).all {
            a[3 + 4 * it].toRawBits() == b[3 + 4 * it].toRawBits() &&
            a[4 + 4 * it].toRawBits() == b[4 + 4 * it].toRawBits()
        }

    fun db(linear: Double) = 20 * log10(max(linear, 1e-12))

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    private fun putLe16(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte(); b[at + 1] = (value shr 8).toByte()
    }
    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { ((digit(s[it * 2]) shl 4) or digit(s[it * 2 + 1])).toByte() }
    }
    private fun digit(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not hex: $c")
    }
    fun bytesToHex(b: ByteArray) = StringBuilder(b.size * 2).apply {
        for (x in b) { val i = x.toInt() and 255; append("0123456789abcdef"[i shr 4]); append("0123456789abcdef"[i and 15]) }
    }.toString()
}
