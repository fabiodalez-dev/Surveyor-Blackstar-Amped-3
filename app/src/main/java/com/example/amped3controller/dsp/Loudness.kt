package com.example.amped3controller.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * How loud a cabinet sounds, as opposed to how much energy it carries.
 *
 * Blackstar normalises every factory profile to the same energy, and the fit does the same, but
 * the ear does not weigh all frequencies alike: across the 285 non-marginal factory profiles the
 * energy varies by 0.6 dB while the loudness below varies by about 6 dB, the bright cabinets
 * being the loud ones. The estimate is pink noise between 80 Hz and 10 kHz, roughly the band of
 * a guitar amplifier, through the modelled cabinet and the ITU-R BS.1770 K-weighting that LUFS
 * meters use. On a log grid pink noise has the same power in every step, so it is a plain mean.
 *
 * Like every curve in the app this is computed from the response model, not measured on the
 * pedal; tools/loudness.py repeats it offline.
 */
object Loudness {
    private const val POINTS = 400
    private val OMEGA = DoubleArray(POINTS) {
        2 * PI * exp(ln(80.0) + (ln(10000.0) - ln(80.0)) * it / (POINTS - 1.0)) / CabProfile.SAMPLE_RATE
    }

    /** |K|² at each grid point: the BS.1770 pre-filter shelf and its high-pass, at 48 kHz. */
    private val K2 = DoubleArray(POINTS) {
        biquad2(OMEGA[it], 1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585) *
            biquad2(OMEGA[it], 1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
    }
    private val K2_SUM = K2.sum()

    /** Loudness in dB relative to a cabinet that passes everything at unity gain. */
    fun db(values: FloatArray): Double {
        var sum = 0.0
        for (i in 0 until POINTS) {
            val m = CabProfile.magnitude(values, OMEGA[i])
            sum += m * m * K2[i]
        }
        return 10 * log10(sum / K2_SUM)
    }

    /**
     * The cabinet level change that brings [values] down to [reference]. Never positive: a cabinet
     * quieter than the reference is left as it is, so levelling can only ever turn a cabinet down.
     */
    fun correctionDb(values: FloatArray, reference: Double): Double = min(0.0, reference - db(values))

    private fun biquad2(w: Double, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Double {
        val c1 = cos(w); val s1 = -sin(w); val c2 = cos(2 * w); val s2 = -sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = b1 * s1 + b2 * s2
        val dr = 1 + a1 * c1 + a2 * c2; val di = a1 * s1 + a2 * s2
        return (nr * nr + ni * ni) / (dr * dr + di * di)
    }
}
