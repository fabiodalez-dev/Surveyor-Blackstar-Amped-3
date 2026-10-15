package com.example.amped3controller.dsp

import com.example.amped3controller.FactoryProfiles
import com.example.amped3controller.IrConversion
import com.example.amped3controller.Recipe
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class CabLabTest {
    private val profiles = FactoryProfiles(File("src/main/assets/cab_profiles.json").readText())
    private val a = profiles.values("21:0:0")
    private val b = profiles.values("12:4:1")

    @Test fun aCabinetBlendedWithItselfIsItself() {
        for (mode in CabLab.BlendMode.values()) {
            val ir = CabLab.prepare(CabLab.blend(a, a, 0.5, mode))
            val d = CabLab.distanceDb(CabLab.curveDb(ir, CabProfile.SAMPLE_RATE), CabLab.curveDb(a))
            assertTrue("$mode drifted by $d dB", d < 0.5)
        }
    }

    @Test fun blendEndsAreTheSourcesAndTheMiddleIsBetween() {
        val ca = CabLab.curveDb(a); val cb = CabLab.curveDb(b)
        val atA = CabLab.curveDb(CabLab.prepare(CabLab.blend(a, b, 1.0, CabLab.BlendMode.ENERGY)), CabProfile.SAMPLE_RATE)
        val atB = CabLab.curveDb(CabLab.prepare(CabLab.blend(a, b, 0.0, CabLab.BlendMode.ENERGY)), CabProfile.SAMPLE_RATE)
        assertTrue(CabLab.distanceDb(atA, ca) < 0.5); assertTrue(CabLab.distanceDb(atB, cb) < 0.5)
        val mid = CabLab.curveDb(CabLab.prepare(CabLab.blend(a, b, 0.5, CabLab.BlendMode.ENERGY)), CabProfile.SAMPLE_RATE)
        assertTrue(CabLab.distanceDb(mid, ca) < CabLab.distanceDb(cb, ca))
        assertTrue(CabLab.distanceDb(mid, cb) < CabLab.distanceDb(ca, cb))
    }

    @Test fun magnitudeRoundTripKeepsTheResponse() {
        val ir = CabLab.prepare(CabLab.impulseFromMagnitude(CabLab.magnitudeBins(a)))
        assertTrue(CabLab.distanceDb(CabLab.curveDb(ir, CabProfile.SAMPLE_RATE), CabLab.curveDb(a)) < 0.5)
    }

    @Test fun tiltRaisesAnOctaveByItsSlope() {
        val shaped = CabLab.shape(CabLab.magnitudeBins(a), CabLab.Shape(tiltDbPerOctave = 1.0))
        val flat = CabLab.magnitudeBins(a)
        fun at(hz: Double) = (hz * CabLab.N / CabProfile.SAMPLE_RATE).toInt()
        val d1k = CabProfile.db(shaped[at(1000.0)]) - CabProfile.db(flat[at(1000.0)])
        val d2k = CabProfile.db(shaped[at(2000.0)]) - CabProfile.db(flat[at(2000.0)])
        assertEquals(0.0, d1k, 0.05); assertEquals(1.0, d2k, 0.05)
    }

    @Test fun scaleMovesThePeak() {
        val base = CabLab.character(CabLab.curveDb(profiles.values("4:0:0")))
        val mag = CabLab.shape(CabLab.magnitudeBins(profiles.values("4:0:0")), CabLab.Shape(scale = 1.2))
        val curve = DoubleArray(CabLab.GRID_HZ.size) { i ->
            val k = (CabLab.GRID_HZ[i] * CabLab.N / CabProfile.SAMPLE_RATE).toInt().coerceIn(0, CabLab.BINS - 1)
            CabProfile.db(mag[k])
        }
        val moved = CabLab.character(curve)
        assertEquals(base.peakHz * 1.2, moved.peakHz, base.peakHz * 0.08)
    }

    @Test fun characterReadsLikeACabinet() {
        val c = CabLab.character(CabLab.curveDb(a))
        assertTrue(c.peakHz in 60.0..6000.0)
        assertTrue(c.lowCornerHz < c.peakHz && c.highCornerHz > c.peakHz)
        assertTrue(c.label.contains(","))
        assertTrue(abs(c.tiltDbPerOctave) < 12)
    }

    @Test fun nearestFindsTheCabinetItself() {
        val n = profiles.nearest(profiles.curve("18:2:0"), 3)
        assertEquals("18:2:0", n.first().first); assertEquals(0.0, n.first().second, 1e-9)
        assertFalse(n.any { it.first in profiles.marginal })
    }

    @Test fun bandErrorsAreZeroForTheSameCurve() {
        val e = CabLab.bandErrors(CabLab.curveDb(a), CabLab.curveDb(a))
        assertEquals(0.0, e.lowDb, 1e-9); assertEquals(0.0, e.midDb, 1e-9); assertEquals(0.0, e.highDb, 1e-9)
    }

    @Test fun aDesignedBlendFitsAndMatchesItsPreview() {
        val recipe = Recipe("21:0:0", "21:4:0", 0.6, CabLab.BlendMode.COMPLEX)
        val c = IrConversion.design(recipe, "test", profiles)
        assertTrue(c.verdict.summary, c.verdict.passed)
        assertTrue("error ${c.errorDb}", c.errorDb < 1.5)
        assertEquals("21:0:0", c.templateKey)
        val fitted = CabLab.curveDb(CabProfile.decode(c.header, c.chunks)!!)
        assertTrue(CabLab.distanceDb(fitted, IrConversion.preview(recipe, profiles)) < 1.5)
        assertNotNull(c.recipe)
    }

    @Test fun aShapedCabinetUsesItsOwnPoleBank() {
        val recipe = Recipe("4:0:0", shape = CabLab.Shape(tiltDbPerOctave = -1.0, scale = 0.9))
        val c = IrConversion.design(recipe, "test", profiles)
        assertTrue(c.verdict.summary, c.verdict.passed)
        assertEquals("4:0:0", c.templateKey)
        assertTrue(CabProfile.samePoleBank(CabProfile.decode(c.header, c.chunks)!!, profiles.values("4:0:0")))
    }

    @Test fun autoTemplatePicksOneOfTheNearest() {
        val ir = CabProfile.impulse(profiles.values("18:0:0"), 8192)
        val wav: ByteArray = com.example.amped3controller.WavBuilder.mono16(ir, 48000)
        val c = IrConversion.convert(wav, "x.wav", listOf(-1, -1, -1), profiles, com.example.amped3controller.IrOptions(template = com.example.amped3controller.IrOptions.AUTO))
        assertTrue(c.nearest.map { it.first }.contains(c.templateKey))
        assertEquals("18:0:0", c.nearest.first().first)
        assertTrue(c.errorDb < 0.5)
    }
}
