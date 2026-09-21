package com.example.amped3controller.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class FitTest {
    private fun template(key: String): FloatArray {
        val asset = File("src/main/assets/cab_profiles.json")
        val objectPattern = Regex(
            """\{\s*"cab":\s*(\d+),\s*"mic":\s*(\d+),\s*"axis":\s*(\d+),\s*"header":\s*"([0-9a-f]+)",\s*"chunks":\s*\[(.*?)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val chunkPattern = Regex(""""([0-9a-f]+)"""")
        val m = objectPattern.findAll(asset.readText()).first {
            "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}" == key
        }
        return CabProfile.decode(m.groupValues[4],
            chunkPattern.findAll(m.groupValues[5]).map { it.groupValues[1] }.toList())!!
    }

    private fun run(ir: DoubleArray, rate: Double, key: String = "21:5:0"): Fit.Result {
        val t = template(key)
        val prepared = Fit.prepare(Audio(rate, ir))
        assertTrue("nothing left after trimming", prepared.size > 64)
        return Fit.fit(t, Fit.minimumPhase(prepared), rate)
    }

    /** The sharpest test available without hardware: a factory cabinet has to fit itself. */
    @Test
    fun aFactoryCabinetFitsItself() {
        val t = template("21:5:0")
        val result = run(CabProfile.impulse(t, 8192), 48000.0)
        assertTrue("refused: ${result.verdict.summary}", result.verdict.passed)
        assertTrue("shape error ${result.errorDb} dB is too large", result.errorDb < 0.25)
        assertTrue("the pole bank must be inherited untouched", CabProfile.samePoleBank(result.values, t))
    }

    /** Another cabinet, so the first result is not a coincidence of one pole bank. */
    @Test
    fun aDifferentCabinetAlsoFitsItself() {
        val t = template("9:0:0")
        val prepared = Fit.prepare(Audio(48000.0, CabProfile.impulse(t, 8192)))
        val result = Fit.fit(t, Fit.minimumPhase(prepared), 48000.0)
        assertTrue("refused: ${result.verdict.summary}", result.verdict.passed)
        assertTrue("shape error ${result.errorDb} dB is too large", result.errorDb < 0.25)
    }

    /**
     * The same cabinet presented at 44.1 kHz must come out at the same frequencies. The target is
     * a real factory impulse response resampled by interpolation, because a bare damped sine is
     * not a cabinet: it has no low end roll-off, the fit parks energy at DC and SafetyCheck
     * rightly refuses it, which says more about the target than about the rate handling.
     */
    @Test
    fun aCabinetKeepsItsShapeWhateverTheSourceRate() {
        val t = template("21:5:0")
        val at48 = CabProfile.impulse(t, 8192)
        val ratio = 44100.0 / 48000.0
        val at441 = DoubleArray((at48.size * ratio).toInt()) { i ->
            val x = i / ratio
            val k = x.toInt()
            val f = x - k
            if (k + 1 < at48.size) at48[k] * (1 - f) + at48[k + 1] * f else 0.0
        }
        val a = Fit.fit(t, Fit.minimumPhase(Fit.prepare(Audio(48000.0, at48))), 48000.0)
        val b = Fit.fit(t, Fit.minimumPhase(Fit.prepare(Audio(44100.0, at441))), 44100.0)
        assertTrue("48 kHz refused: ${a.verdict.summary}", a.verdict.passed)
        assertTrue("44.1 kHz refused: ${b.verdict.summary}", b.verdict.passed)
        assertTrue("44.1 kHz shape error ${b.errorDb} dB is too large", b.errorDb < 2.0)
        val ra = a.verdict.metrics!!.peakHz; val rb = b.verdict.metrics!!.peakHz
        assertEquals("the resonance moved between rates: $ra vs $rb Hz", ra, rb, 0.1 * ra)
    }

    /** A target the hardware should never be asked to play must not come back as "safe". */
    @Test
    fun anImpossibleTargetIsNeverDeclaredSafe() {
        // a 20 Hz tone that barely decays: seconds of ringing and infrasonic energy
        val rate = 48000.0
        val n = (rate * 0.17).toInt()
        val ir = DoubleArray(n) { i -> exp(-i / (rate * 4.0)) * sin(2 * PI * 20.0 * i / rate) }
        val result = run(ir, rate)
        if (result.verdict.passed) {
            val m = result.verdict.metrics!!
            assertTrue("accepted with ${m.t60 * 1000} ms of ringing", m.t60 <= SafetyCheck.T60_SECONDS_MAX)
            assertTrue("accepted with ${m.hz20Db} dB at 20 Hz", m.hz20Db <= SafetyCheck.HZ20_DB_MAX)
            assertTrue("accepted with L1 ${m.irL1}", m.irL1 <= SafetyCheck.IR_L1_MAX)
        }
    }

    @Test
    fun wavFilesAreReadBackExactly() {
        val rate = 48000
        val samples = DoubleArray(1000) { sin(2 * PI * 100 * it / rate) * 0.5 }
        val body = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, x ->
            val v = (x * 32767).toInt()
            body[i * 2] = v.toByte(); body[i * 2 + 1] = (v shr 8).toByte()
        }
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray()); out.write(le32(36 + body.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
        out.write(le32(rate)); out.write(le32(rate * 2)); out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(body.size)); out.write(body)

        val audio = Wav.decode(out.toByteArray())!!
        assertEquals(48000.0, audio.rate, 0.0)
        assertEquals(1000, audio.samples.size)
        audio.samples.forEachIndexed { i, x -> assertTrue(abs(x - samples[i]) < 1e-4) }
        assertTrue("a truncated file must be refused", Wav.decode(out.toByteArray().copyOf(20)) == null)
    }
}
