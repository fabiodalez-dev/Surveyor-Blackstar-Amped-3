package com.example.amped3controller.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cabinets made from other cabinets.
 *
 * The pedal runs one bank of sixteen sections, so there is no second cabinet to mix with on the
 * hardware. What the hardware does offer is an arbitrary response, and the 288 factory responses
 * are known to the digit, so a mix of two cabinets, a microphone blend, a tilt or a "bigger box"
 * can all be built as a target response on the phone and fitted onto a factory pole bank like an
 * imported impulse response. Everything here is linear algebra on the modelled response; the
 * result still has to pass SafetyCheck and still reaches the pedal only through the usual
 * audition path.
 */
object CabLab {
    /** FFT size used to turn a magnitude curve back into an impulse: 8192 bins at 48 kHz is
     *  5.9 Hz per bin and 170 ms of impulse, the same length the WAV path keeps. */
    const val N = 8192
    const val BINS = N / 2 + 1

    fun binHz(k: Int): Double = k * CabProfile.SAMPLE_RATE / N

    /** |H| of a profile at every FFT bin, linear. */
    fun magnitudeBins(v: FloatArray): DoubleArray =
        DoubleArray(BINS) { k -> CabProfile.magnitude(v, 2 * PI * k / N) }

    /**
     * A minimum-phase impulse with the given magnitude at the bins. The magnitude is placed on a
     * linear-phase FIR first (peak in the middle), because a zero-phase sequence that wraps around
     * the buffer loses its symmetry when zero-padded and the magnitude ripples; the linear phase
     * is only a delay, which minimumPhase then removes.
     */
    fun impulseFromMagnitude(mag: DoubleArray): DoubleArray {
        require(mag.size == BINS)
        val re = DoubleArray(N); val im = DoubleArray(N)
        for (k in 0 until BINS) {
            val m = if (mag[k].isFinite()) max(mag[k], 0.0) else 0.0
            re[k] = m
            if (k in 1 until N / 2) re[N - k] = m
        }
        Fft.transform(re, im, true)
        val linear = DoubleArray(N) { re[(it + N / 2) % N] }   // centre the zero-phase impulse
        return Fit.minimumPhase(linear)
    }

    /** The target an impulse would present to [Fit.fit], trimmed and faded like a WAV. */
    fun prepare(ir: DoubleArray): DoubleArray = Fit.prepare(Audio(CabProfile.SAMPLE_RATE, ir))

    // --- mixing --------------------------------------------------------------------------

    enum class BlendMode { COMPLEX, ENERGY }

    /**
     * Two cabinets on one fader. COMPLEX adds the impulses, as two microphones on one bus do,
     * with the comb filtering that implies when their phases differ; ENERGY averages the powers,
     * which keeps the magnitude of both and never cancels. Both end up minimum phase.
     */
    fun blend(a: FloatArray, b: FloatArray, ratioA: Double, mode: BlendMode): DoubleArray {
        val r = ratioA.coerceIn(0.0, 1.0)
        return when (mode) {
            BlendMode.COMPLEX -> {
                val ha = CabProfile.impulse(a, N); val hb = CabProfile.impulse(b, N)
                Fit.minimumPhase(DoubleArray(N) { r * ha[it] + (1 - r) * hb[it] })
            }
            BlendMode.ENERGY -> {
                val ma = magnitudeBins(a); val mb = magnitudeBins(b)
                impulseFromMagnitude(DoubleArray(BINS) { sqrt(r * ma[it] * ma[it] + (1 - r) * mb[it] * mb[it]) })
            }
        }
    }

    // --- shaping -------------------------------------------------------------------------

    /**
     * Tone controls applied to a response before it is fitted. All gains are in dB on the
     * magnitude; the result is minimum phase whatever the source was. [scale] stretches the
     * frequency axis: 1.1 moves every resonance up a tenth, which is what a smaller box does.
     */
    data class Shape(
        val tiltDbPerOctave: Double = 0.0,
        val lowShelfHz: Double = 120.0, val lowShelfDb: Double = 0.0,
        val highShelfHz: Double = 4000.0, val highShelfDb: Double = 0.0,
        val peakHz: Double = 1000.0, val peakDb: Double = 0.0, val peakOctaves: Double = 1.0,
        val scale: Double = 1.0
    ) {
        val isIdentity get() = tiltDbPerOctave == 0.0 && lowShelfDb == 0.0 && highShelfDb == 0.0 && peakDb == 0.0 && scale == 1.0

        fun gainDb(hz: Double): Double {
            if (hz <= 0.0) return 0.0
            var g = tiltDbPerOctave * (ln(hz / 1000.0) / ln(2.0))
            g += lowShelfDb / (1 + (hz / lowShelfHz) * (hz / lowShelfHz))
            g += highShelfDb / (1 + (highShelfHz / hz) * (highShelfHz / hz))
            if (peakDb != 0.0) {
                val x = ln(hz / peakHz) / ln(2.0)
                val sigma = max(peakOctaves, 0.1) / 2.355
                g += peakDb * exp(-x * x / (2 * sigma * sigma))
            }
            return g
        }
    }

    fun shape(mag: DoubleArray, shape: Shape): DoubleArray {
        require(mag.size == BINS)
        val scaled = if (shape.scale == 1.0) mag else DoubleArray(BINS) { k ->
            // value at hz/scale, interpolated between bins on the linear grid
            val pos = k / shape.scale
            val i = pos.toInt()
            if (i >= BINS - 1) mag[BINS - 1] else mag[i] + (mag[i + 1] - mag[i]) * (pos - i)
        }
        return DoubleArray(BINS) { k -> scaled[k] * Math.pow(10.0, shape.gainDb(binHz(k)) / 20) }
    }

    fun shaped(v: FloatArray, shape: Shape): DoubleArray = impulseFromMagnitude(shape(magnitudeBins(v), shape))

    // --- describing ----------------------------------------------------------------------

    /** Log grid, 20 Hz to 16 kHz, for curves and distances. */
    val GRID_HZ: DoubleArray = DoubleArray(240) { exp(ln(20.0) + (ln(16000.0) - ln(20.0)) * it / 239.0) }

    fun curveDb(v: FloatArray): DoubleArray = DoubleArray(GRID_HZ.size) { CabProfile.db(CabProfile.magnitudeAtHz(v, GRID_HZ[it])) }

    /** Magnitude in dB of an impulse at the grid, by direct DTFT, so a WAV can be compared too. */
    fun curveDb(ir: DoubleArray, rate: Double): DoubleArray = DoubleArray(GRID_HZ.size) { i ->
        val hz = GRID_HZ[i]
        if (hz > rate / 2) return@DoubleArray -120.0
        val w = -2 * PI * hz / rate
        val cr = Math.cos(w); val ci = Math.sin(w)
        var zr = 1.0; var zi = 0.0; var sr = 0.0; var si = 0.0
        for (x in ir) { sr += x * zr; si += x * zi; val nr = zr * cr - zi * ci; zi = zr * ci + zi * cr; zr = nr }
        CabProfile.db(sqrt(sr * sr + si * si))
    }

    /** Numbers a player can read: where the peak is, where the lows and highs give up, which way
     *  the whole thing leans, and how much upper midrange it has against the body. */
    data class Character(
        val peakHz: Double, val peakDb: Double,
        val lowCornerHz: Double, val highCornerHz: Double,
        val tiltDbPerOctave: Double, val presenceDb: Double
    ) {
        /** Two words, the way one would describe it in a rehearsal room. */
        val label: String get() {
            val warmth = when { tiltDbPerOctave < -2.5 -> "dark"; tiltDbPerOctave > 0.5 -> "bright"; else -> "balanced" }
            val body = when { lowCornerHz < 80 -> "full"; lowCornerHz > 140 -> "tight"; else -> "even" }
            return "$warmth, $body"
        }
    }

    fun character(curve: DoubleArray): Character {
        require(curve.size == GRID_HZ.size)
        var peak = -1e9; var peakAt = 0
        for (i in curve.indices) if (curve[i] > peak) { peak = curve[i]; peakAt = i }
        var low = peakAt; while (low > 0 && curve[low - 1] >= peak - 6) low--
        var high = peakAt; while (high < curve.size - 1 && curve[high + 1] >= peak - 6) high++
        // slope of a straight line through the curve between 100 Hz and 5 kHz, in dB per octave
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var n = 0
        for (i in curve.indices) {
            val hz = GRID_HZ[i]
            if (hz < 100 || hz > 5000) continue
            val x = ln(hz) / ln(2.0)
            sx += x; sy += curve[i]; sxx += x * x; sxy += x * curve[i]; n++
        }
        val tilt = if (n > 1 && sxx * n - sx * sx != 0.0) (n * sxy - sx * sy) / (n * sxx - sx * sx) else 0.0
        fun mean(from: Double, to: Double): Double {
            var s = 0.0; var c = 0
            for (i in curve.indices) if (GRID_HZ[i] in from..to) { s += curve[i]; c++ }
            return if (c > 0) s / c else 0.0
        }
        return Character(GRID_HZ[peakAt], peak, GRID_HZ[low], GRID_HZ[high], tilt, mean(2000.0, 5000.0) - mean(300.0, 1000.0))
    }

    /** RMS difference of two curves in dB between 60 Hz and 12 kHz, level ignored. */
    fun distanceDb(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.size == GRID_HZ.size)
        var bias = 0.0; var n = 0
        for (i in a.indices) if (GRID_HZ[i] in 60.0..12000.0) { bias += a[i] - b[i]; n++ }
        if (n == 0) return Double.NaN
        bias /= n
        var s = 0.0
        for (i in a.indices) if (GRID_HZ[i] in 60.0..12000.0) { val d = a[i] - b[i] - bias; s += d * d }
        return sqrt(s / n)
    }

    /** The same difference split into lows, mids and highs, so a conversion can say where it lost. */
    data class BandErrors(val lowDb: Double, val midDb: Double, val highDb: Double)

    fun bandErrors(candidate: DoubleArray, target: DoubleArray): BandErrors {
        var bias = 0.0; var n = 0
        for (i in candidate.indices) if (GRID_HZ[i] in 60.0..12000.0) { bias += candidate[i] - target[i]; n++ }
        bias = if (n > 0) bias / n else 0.0
        fun band(from: Double, to: Double): Double {
            var s = 0.0; var c = 0
            for (i in candidate.indices) if (GRID_HZ[i] in from..to) { val d = candidate[i] - target[i] - bias; s += d * d; c++ }
            return if (c > 0) sqrt(s / c) else Double.NaN
        }
        return BandErrors(band(60.0, 250.0), band(250.0, 2500.0), band(2500.0, 12000.0))
    }

    /** Factory profiles ordered by how close their response is to [target]. */
    fun nearest(target: DoubleArray, library: Map<String, FloatArray>, count: Int): List<Pair<String, Double>> =
        library.entries.map { (key, v) -> key to distanceDb(curveDb(v), target) }
            .filter { it.second.isFinite() }.sortedBy { it.second }.take(count)

    fun linear(db: Double) = Math.pow(10.0, db / 20)
}
