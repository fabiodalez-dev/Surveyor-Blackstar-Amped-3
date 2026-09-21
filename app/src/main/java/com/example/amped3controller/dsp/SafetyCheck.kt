package com.example.amped3controller.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Whether a candidate profile is safe to send to the pedal.
 *
 * Loading coefficients only writes the live DSP: it is not a firmware path and it cannot reach the
 * stored slots, which take a separate 0xAF command this feature never sends. What remains is an
 * audio risk, because the live DSP drives the speaker, so every limit below is the largest value
 * observed across the 288 factory profiles, excluding the three that quantise onto the unit
 * circle. The rule is that nothing we generate may do something Blackstar's own data does not
 * already do. `tools/safety_envelope.py` recomputes these numbers from the shipped library.
 *
 * Checks run cheapest first and all of them operate on the float32 values, which is what the
 * hardware actually receives.
 */
object SafetyCheck {
    // Limits are the extreme each metric reaches across the 285 non-marginal factory profiles,
    // rounded outward by one digit so the profile that sets the extreme still passes. Recompute
    // with tools/safety_envelope.py; the factory value is in the comment beside each one.
    const val COEFFICIENT_ABS_MAX = 45.0            // factory 44.97
    const val POLE_DC_MARGIN_MIN = 4.7e-7           // factory 4.768e-7; zero is an integrator at DC
    const val POLE_NYQUIST_MARGIN_MIN = 0.5         // factory 0.5006
    const val PEAK_DB_MAX = 6.8                     // factory +6.67
    const val DC_DB_MAX = -20.5                     // factory -20.59
    const val HZ20_DB_MAX = -11.4                   // factory -11.48
    const val HZ20K_DB_MAX = -32.2                  // factory -32.25
    const val NYQUIST_DB_MAX = -41.8                // factory -41.85
    const val SECTION_PEAK_DB_MAX = 43.0            // factory 42.95
    const val SECTION_SUM_OVER_PEAK_DB_MAX = 60.3   // factory 60.18
    const val IR_PEAK_MAX = 0.142                   // factory 0.1405
    const val IR_L1_MAX = 3.55                      // factory 3.5397
    const val IR_ENERGY_MAX = 0.2495                // factory 0.24930
    const val T60_SECONDS_MAX = 0.062               // factory 61.4 ms
    const val TAIL_100MS_DB_MAX = -55.2             // factory -55.31
    const val FLOAT32_DRIFT_DB_MAX = -80.0          // factory worst -83.8
    const val ENERGY_TARGET = 0.246        // every factory profile is normalised to about this

    data class Metrics(
        val peakDb: Double, val peakHz: Double, val dcDb: Double, val hz20Db: Double,
        val hz20kDb: Double, val nyquistDb: Double, val sectionPeakDb: Double,
        val sectionSumOverPeakDb: Double, val irPeak: Double, val irL1: Double,
        val energy: Double, val t60: Double, val tailDb: Double, val float32DriftDb: Double,
        val poleDcMargin: Double, val poleNyquistMargin: Double, val coefficientAbsMax: Double
    )

    data class Verdict(val passed: Boolean, val failures: List<String>, val metrics: Metrics?) {
        val summary: String get() = if (passed) "safe to load" else failures.joinToString("; ")
    }

    /**
     * @param template the factory profile the candidate was fitted on. When given, the candidate
     *   must carry its pole bank bit for bit: the fit only moves numerators, so a difference means
     *   the denominators were touched and the stability argument no longer holds.
     */
    fun verify(values: FloatArray, template: FloatArray? = null): Verdict {
        val bad = ArrayList<String>()
        if (values.size != CabProfile.VALUES || !values.all { it.isFinite() })
            return Verdict(false, listOf("coefficients are not 65 finite values"), null)

        var coefficientAbsMax = 0.0
        for (i in 0 until CabProfile.VALUES) {
            val x = abs(values[i].toDouble())
            if (x > coefficientAbsMax) coefficientAbsMax = x
        }
        if (coefficientAbsMax > COEFFICIENT_ABS_MAX) bad += "coefficient magnitude %.1f over %.0f".format(coefficientAbsMax, COEFFICIENT_ABS_MAX)
        for (s in 0 until CabProfile.SECTIONS) {
            val a1 = values[3 + 4 * s].toDouble(); val a2 = values[4 + 4 * s].toDouble()
            if (abs(a1) >= 2.0 || a2 <= 0.0 || a2 >= 1.0) bad += "section $s has poles outside the unit circle"
        }
        if (template != null && !CabProfile.samePoleBank(values, template)) bad += "pole bank differs from the template"

        val (dcMargin, nyMargin) = CabProfile.poleMargins(values)
        if (dcMargin < POLE_DC_MARGIN_MIN) bad += "a pole sits on or beside z=1 (margin %.3g)".format(dcMargin)
        if (nyMargin < POLE_NYQUIST_MARGIN_MIN) bad += "a pole sits beside z=-1 (margin %.3g)".format(nyMargin)

        // frequency response on a log grid, plus the exact values at the ends
        var peak = 0.0; var peakHz = 0.0
        val lo = ln(0.01); val hi = ln(CabProfile.SAMPLE_RATE / 2)
        val points = 4000
        for (i in 0 until points) {
            val hz = exp(lo + (hi - lo) * i / (points - 1.0))
            val m = CabProfile.magnitudeAtHz(values, hz)
            if (m > peak) { peak = m; peakHz = hz }
        }
        val dc = CabProfile.dcGain(values)
        val nyquist = CabProfile.nyquistGain(values)
        peak = maxOf(peak, dc, nyquist)
        val peakDb = CabProfile.db(peak)
        val dcDb = CabProfile.db(dc)
        val hz20Db = CabProfile.db(CabProfile.magnitudeAtHz(values, 20.0))
        val hz20kDb = CabProfile.db(CabProfile.magnitudeAtHz(values, 20000.0))
        val nyquistDb = CabProfile.db(nyquist)
        if (peakDb > PEAK_DB_MAX) bad += "peak gain %.1f dB over %.1f".format(peakDb, PEAK_DB_MAX)
        if (dcDb > DC_DB_MAX) bad += "DC gain %.1f dB over %.1f".format(dcDb, DC_DB_MAX)
        if (hz20Db > HZ20_DB_MAX) bad += "20 Hz gain %.1f dB over %.1f".format(hz20Db, HZ20_DB_MAX)
        if (hz20kDb > HZ20K_DB_MAX) bad += "20 kHz gain %.1f dB over %.1f".format(hz20kDb, HZ20K_DB_MAX)
        if (nyquistDb > NYQUIST_DB_MAX) bad += "Nyquist gain %.1f dB over %.1f".format(nyquistDb, NYQUIST_DB_MAX)

        // a section that peaks far above the total means the sections are cancelling each other,
        // which is fragile: it survives coefficient quantisation but not necessarily the
        // hardware's own arithmetic, which we cannot inspect
        var sectionPeakDb = Double.NEGATIVE_INFINITY; var sectionSum = 0.0
        for (s in 0 until CabProfile.SECTIONS) {
            val one = FloatArray(CabProfile.VALUES)
            for (k in 0 until 4) one[1 + 4 * s + k] = values[1 + 4 * s + k]
            var p = 0.0
            for (i in 0 until 600) {
                val hz = exp(lo + (hi - lo) * i / 599.0)
                p = maxOf(p, CabProfile.magnitudeAtHz(one, hz))
            }
            sectionSum += p
            sectionPeakDb = maxOf(sectionPeakDb, CabProfile.db(p))
        }
        val sumOverPeakDb = CabProfile.db(sectionSum) - peakDb
        if (sectionPeakDb > SECTION_PEAK_DB_MAX) bad += "a section peaks at %.1f dB over %.0f".format(sectionPeakDb, SECTION_PEAK_DB_MAX)
        if (sumOverPeakDb > SECTION_SUM_OVER_PEAK_DB_MAX) bad += "sections cancel by %.1f dB over %.0f".format(sumOverPeakDb, SECTION_SUM_OVER_PEAK_DB_MAX)

        // impulse response: worst-case gain, loudness, ringing, and non-decay
        val seconds = 2.0
        val n = (CabProfile.SAMPLE_RATE * seconds).toInt()
        val h = CabProfile.impulse(values, n)
        var irPeak = 0.0; var l1 = 0.0; var energy = 0.0
        for (x in h) { val a = abs(x); if (a > irPeak) irPeak = a; l1 += a; energy += x * x }
        energy = sqrt(energy)
        var t60 = 0.0
        val floor60 = irPeak * 0.001
        for (i in h.indices.reversed()) if (abs(h[i]) > floor60) { t60 = (i + 1) / CabProfile.SAMPLE_RATE; break }
        var tail = 0.0
        for (i in (CabProfile.SAMPLE_RATE * 0.1).toInt() until n) tail += h[i] * h[i]
        val tailDb = CabProfile.db(sqrt(tail))
        if (irPeak > IR_PEAK_MAX) bad += "impulse peak %.3f over %.3f".format(irPeak, IR_PEAK_MAX)
        if (l1 > IR_L1_MAX) bad += "worst-case gain %.2f over %.2f".format(l1, IR_L1_MAX)
        if (energy > IR_ENERGY_MAX) bad += "energy %.4f over %.4f".format(energy, IR_ENERGY_MAX)
        if (t60 > T60_SECONDS_MAX) bad += "rings for %.0f ms over %.0f".format(t60 * 1000, T60_SECONDS_MAX * 1000)
        if (tailDb > TAIL_100MS_DB_MAX) bad += "tail after 100 ms at %.0f dB over %.0f".format(tailDb, TAIL_100MS_DB_MAX)

        // the same recursion in float32: catches cancellations the state precision cannot hold
        val h32 = CabProfile.impulseFloat(values, n)
        var drift = 0.0
        for (i in 0 until n) drift = maxOf(drift, abs(h32[i] - h[i]))
        val driftDb = CabProfile.db(drift / maxOf(irPeak, 1e-12))
        if (driftDb > FLOAT32_DRIFT_DB_MAX) bad += "float32 drift %.0f dB over %.0f".format(driftDb, FLOAT32_DRIFT_DB_MAX)

        return Verdict(bad.isEmpty(), bad, Metrics(peakDb, peakHz, dcDb, hz20Db, hz20kDb, nyquistDb,
            sectionPeakDb, sumOverPeakDb, irPeak, l1, energy, t60, tailDb, driftDb,
            dcMargin, nyMargin, coefficientAbsMax))
    }
}
