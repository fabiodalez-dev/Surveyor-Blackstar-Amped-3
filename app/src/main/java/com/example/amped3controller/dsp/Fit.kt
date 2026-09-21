package com.example.amped3controller.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A mono impulse response and the rate it was recorded at. */
class Audio(val rate: Double, val samples: DoubleArray)

/** Minimal WAV reader: PCM 8/16/24/32 bit integer and 32 bit float, first channel only. */
object Wav {
    fun decode(bytes: ByteArray): Audio? {
        if (bytes.size < 44 || str(bytes, 0, 4) != "RIFF" || str(bytes, 8, 4) != "WAVE") return null
        var at = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataAt = -1; var dataLen = 0
        while (at + 8 <= bytes.size) {
            val id = str(bytes, at, 4)
            val size = le32(bytes, at + 4)
            if (size < 0 || at + 8 + size > bytes.size && id != "data") return null
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    format = le16(bytes, at + 8); channels = le16(bytes, at + 10)
                    rate = le32(bytes, at + 12); bits = le16(bytes, at + 22)
                }
                "data" -> { dataAt = at + 8; dataLen = min(size, bytes.size - dataAt) }
            }
            at += 8 + size + (size and 1)
            if (dataAt >= 0) break
        }
        if (dataAt < 0 || channels < 1 || rate < 8000 || rate > 384000) return null
        val step = bits / 8
        if (step < 1 || (format != 1 && format != 3) || (format == 3 && bits != 32)) return null
        val frames = dataLen / (step * channels)
        if (frames < 8) return null
        val out = DoubleArray(frames)
        for (i in 0 until frames) {
            val p = dataAt + i * step * channels
            out[i] = when {
                format == 3 -> Float.fromBits(le32(bytes, p)).toDouble()
                bits == 8 -> ((bytes[p].toInt() and 255) - 128) / 128.0
                bits == 16 -> le16signed(bytes, p) / 32768.0
                bits == 24 -> (((bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8) or
                    (bytes[p + 2].toInt() shl 16)) shl 8 shr 8) / 8388608.0
                bits == 32 -> le32(bytes, p) / 2147483648.0
                else -> return null
            }
            if (!out[i].isFinite()) return null
        }
        return Audio(rate.toDouble(), out)
    }
    private fun str(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.US_ASCII)
    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    private fun le16signed(b: ByteArray, at: Int) = le16(b, at).toShort().toInt()
    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or
        ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)
}

/** In-place iterative radix-2 FFT. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n and (n - 1) == 0) { "length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2 * PI else -2 * PI) / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

/**
 * Fitting an impulse response onto a factory pole bank.
 *
 * Only the numerators move: the sixteen denominators are copied bit for bit from the template, so
 * a fitted profile has exactly the poles of a profile Blackstar ships and its stability is not a
 * property we have to argue about. That leaves 33 real unknowns, one direct term and two per
 * section, and the response is linear in them, which is why a drawn curve and an imported WAV are
 * the same problem.
 *
 * The response model is unverified against a measured sweep, so a fit is an approximation under a
 * hypothesis. Everything it produces still has to pass SafetyCheck before it can reach the pedal.
 */
object Fit {
    const val UNKNOWNS = 33
    private const val GRID = 1024
    private const val PRIOR_ROWS = 64
    private const val LOW_HZ = 5.0
    private const val HIGH_HZ = 20000.0

    class Result(
        val values: FloatArray,
        val verdict: SafetyCheck.Verdict,
        val errorDb: Double,
        val ridge: Double,
        val attenuatedDb: Double
    )

    /** Trims silence, limits the length to what sixteen sections can represent, and fades the end. */
    fun prepare(audio: Audio): DoubleArray {
        val h = audio.samples
        var peak = 0.0
        for (x in h) peak = max(peak, abs(x))
        if (peak <= 0.0) return DoubleArray(0)
        val threshold = peak * 1e-3
        var start = 0
        while (start < h.size && abs(h[start]) < threshold) start++
        val maxLength = (0.17 * audio.rate).toInt()
        val n = min(h.size - start, maxLength)
        if (n < 8) return DoubleArray(0)
        val out = DoubleArray(n) { h[start + it] }
        val fade = n / 10
        for (i in 0 until fade) {
            val w = 0.5 * (1 + cos(PI * i / fade))
            out[n - fade + i] *= w
        }
        return out
    }

    /**
     * Same magnitude, no bulk delay and no non-minimum-phase reflections. A sixteen-section IIR
     * cannot represent those, and left in they drag the complex fit away from the magnitude.
     */
    fun minimumPhase(h: DoubleArray): DoubleArray {
        var n = 32768
        while (n < 4 * h.size) n = n shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        h.copyInto(re)
        Fft.transform(re, im, false)
        var peak = 0.0
        for (i in 0 until n) peak = max(peak, sqrt(re[i] * re[i] + im[i] * im[i]))
        val floor = max(peak * 1e-5, 1e-12)
        for (i in 0 until n) { re[i] = ln(max(sqrt(re[i] * re[i] + im[i] * im[i]), floor)); im[i] = 0.0 }
        Fft.transform(re, im, true)                       // real cepstrum
        for (i in 1 until n / 2) { re[i] *= 2; im[i] *= 2 }
        for (i in n / 2 + 1 until n) { re[i] = 0.0; im[i] = 0.0 }
        Fft.transform(re, im, false)
        for (i in 0 until n) {                             // exp of the folded cepstrum
            val m = exp(re[i]); val p = im[i]
            re[i] = m * cos(p); im[i] = m * sin(p)
        }
        Fft.transform(re, im, true)
        return DoubleArray(h.size) { re[it] }
    }

    /**
     * Solves the weighted least squares with a ridge term, escalating the ridge while the result
     * is rejected. Returns the best candidate it managed, passed or not, so the caller can show why.
     */
    fun fit(template: FloatArray, ir: DoubleArray, rateIn: Double): Result {
        require(template.size == CabProfile.VALUES)
        val frequencies = DoubleArray(GRID) {
            exp(ln(LOW_HZ) + (ln(HIGH_HZ) - ln(LOW_HZ)) * it / (GRID - 1.0))
        }
        // Target spectrum taken straight at the file's own rate: the sample rate only enters this
        // exponent, so a 44.1 kHz impulse response needs no resampling.
        val targetRe = DoubleArray(GRID); val targetIm = DoubleArray(GRID)
        for (k in 0 until GRID) {
            if (frequencies[k] > rateIn / 2) continue
            val w = -2 * PI * frequencies[k] / rateIn
            val cr = cos(w); val ci = sin(w)
            var zr = 1.0; var zi = 0.0; var sr = 0.0; var si = 0.0
            for (x in ir) {
                sr += x * zr; si += x * zi
                val nr = zr * cr - zi * ci; zi = zr * ci + zi * cr; zr = nr
            }
            targetRe[k] = sr; targetIm[k] = si
        }
        var targetPeak = 0.0
        for (k in 0 until GRID) targetPeak = max(targetPeak, sqrt(targetRe[k] * targetRe[k] + targetIm[k] * targetIm[k]))
        if (targetPeak <= 0.0) return Result(template.copyOf(), SafetyCheck.verify(template), Double.NaN, 0.0, 0.0)

        val floor = targetPeak * 0.0631            // 24 dB below the peak
        val weights = DoubleArray(GRID) { k ->
            1.0 / max(sqrt(targetRe[k] * targetRe[k] + targetIm[k] * targetIm[k]), floor)
        }
        var maxWeight = 0.0
        for (w in weights) maxWeight = max(maxWeight, w)
        for (k in 0 until GRID) weights[k] /= maxWeight

        var ridge = 1e-8
        var best: Result? = null
        repeat(5) {
            val values = solve(template, frequencies, targetRe, targetIm, weights, rateIn, ridge)
            val normalised = normalise(values)
            var candidate = CabProfile.quantise(normalised)
            var attenuated = 0.0
            var verdict = SafetyCheck.verify(candidate, template)
            val metrics = verdict.metrics
            if (metrics != null && metrics.peakDb > SafetyCheck.PEAK_DB_MAX) {
                // pull the whole thing down rather than reshape it: quieter is the safe direction
                val scale = Math.pow(10.0, -(metrics.peakDb - SafetyCheck.PEAK_DB_MAX + 0.3) / 20)
                attenuated = 20 * log10(scale)
                val quieter = DoubleArray(CabProfile.VALUES) { i ->
                    if (i % 4 == 3 || i % 4 == 0 && i != 0) normalised[i] else normalised[i] * scale
                }
                for (s in 0 until CabProfile.SECTIONS) {
                    quieter[3 + 4 * s] = normalised[3 + 4 * s]; quieter[4 + 4 * s] = normalised[4 + 4 * s]
                }
                candidate = CabProfile.quantise(quieter)
                verdict = SafetyCheck.verify(candidate, template)
            }
            val error = shapeErrorDb(candidate, frequencies, targetRe, targetIm, rateIn)
            val result = Result(candidate, verdict, error, ridge, attenuated)
            if (best == null || (!best!!.verdict.passed && verdict.passed)) best = result
            if (verdict.passed) return result
            ridge *= 100
        }
        return best!!
    }

    private fun solve(
        template: FloatArray, frequencies: DoubleArray,
        targetRe: DoubleArray, targetIm: DoubleArray, weights: DoubleArray,
        rateIn: Double, ridge: Double
    ): DoubleArray {
        val priorFrom = CabProfile.SAMPLE_RATE / 2 - 4000
        val rows = ArrayList<DoubleArray>(2 * GRID + PRIOR_ROWS + UNKNOWNS + 1)
        val rhs = ArrayList<Double>(rows.size)

        fun addRow(hz: Double, tr: Double, ti: Double, w: Double, realOnly: Boolean) {
            val omega = 2 * PI * hz / CabProfile.SAMPLE_RATE
            val zr = cos(-omega); val zi = sin(-omega)
            val z2r = zr * zr - zi * zi; val z2i = 2 * zr * zi
            val re = DoubleArray(UNKNOWNS); val im = DoubleArray(UNKNOWNS)
            re[0] = 1.0
            for (s in 0 until CabProfile.SECTIONS) {
                val a1 = template[3 + 4 * s].toDouble(); val a2 = template[4 + 4 * s].toDouble()
                val dr = 1 + a1 * zr + a2 * z2r; val di = a1 * zi + a2 * z2i
                val den = dr * dr + di * di
                val invR = dr / den; val invI = -di / den          // 1/D
                re[1 + 2 * s] = invR; im[1 + 2 * s] = invI          // b0 column
                re[2 + 2 * s] = zr * invR - zi * invI               // b1 column: z/D
                im[2 + 2 * s] = zr * invI + zi * invR
            }
            rows.add(DoubleArray(UNKNOWNS) { re[it] * w }); rhs.add(tr * w)
            if (!realOnly) { rows.add(DoubleArray(UNKNOWNS) { im[it] * w }); rhs.add(ti * w) }
        }

        for (k in 0 until GRID) addRow(frequencies[k], targetRe[k], targetIm[k], weights[k], false)
        // roll-off prior: the model must not park energy above the audio band where the target says nothing
        for (i in 0 until PRIOR_ROWS) {
            val hz = priorFrom + (CabProfile.SAMPLE_RATE / 2 - priorFrom) * i / (PRIOR_ROWS - 1.0)
            addRow(hz, 0.0, 0.0, 4.0, false)
        }

        val m = rows.size
        val a = Array(m + UNKNOWNS) { r -> if (r < m) rows[r] else DoubleArray(UNKNOWNS) }
        val b = DoubleArray(m + UNKNOWNS) { r -> if (r < m) rhs[r] else 0.0 }
        // equilibrate the columns before regularising, otherwise the ridge means something
        // different for each unknown
        val scale = DoubleArray(UNKNOWNS)
        for (c in 0 until UNKNOWNS) {
            var s = 0.0
            for (r in 0 until m) s += a[r][c] * a[r][c]
            scale[c] = if (s > 0) sqrt(s) else 1.0
            for (r in 0 until m) a[r][c] /= scale[c]
        }
        for (c in 0 until UNKNOWNS) a[m + c][c] = sqrt(ridge)

        val x = householder(a, b)
        for (c in 0 until UNKNOWNS) x[c] /= scale[c]

        val values = DoubleArray(CabProfile.VALUES)
        values[0] = x[0]
        for (s in 0 until CabProfile.SECTIONS) {
            values[1 + 4 * s] = x[1 + 2 * s]
            values[2 + 4 * s] = x[2 + 2 * s]
            values[3 + 4 * s] = template[3 + 4 * s].toDouble()
            values[4 + 4 * s] = template[4 + 4 * s].toDouble()
        }
        return values
    }

    /** Householder QR with back substitution. Never forms the normal equations. */
    private fun householder(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = a.size; val n = UNKNOWNS
        val rhs = b.copyOf()
        for (c in 0 until n) {
            var norm = 0.0
            for (r in c until m) norm += a[r][c] * a[r][c]
            norm = sqrt(norm)
            if (norm < 1e-300) continue
            if (a[c][c] > 0) norm = -norm
            val v = DoubleArray(m)
            for (r in c until m) v[r] = a[r][c]
            v[c] -= norm
            var vv = 0.0
            for (r in c until m) vv += v[r] * v[r]
            if (vv < 1e-300) continue
            for (col in c until n) {
                var dot = 0.0
                for (r in c until m) dot += v[r] * a[r][col]
                val f = 2 * dot / vv
                for (r in c until m) a[r][col] -= f * v[r]
            }
            var dot = 0.0
            for (r in c until m) dot += v[r] * rhs[r]
            val f = 2 * dot / vv
            for (r in c until m) rhs[r] -= f * v[r]
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = rhs[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = if (abs(a[r][r]) > 1e-300) s / a[r][r] else 0.0
        }
        return x
    }

    /** Every factory profile carries almost the same energy; a fit must land on that reference. */
    private fun normalise(values: DoubleArray): DoubleArray {
        val quantised = CabProfile.quantise(values)
        val h = CabProfile.impulse(quantised, CabProfile.SAMPLE_RATE.toInt())
        var energy = 0.0
        for (x in h) energy += x * x
        energy = sqrt(energy)
        if (energy <= 0 || !energy.isFinite()) return values
        val scale = SafetyCheck.ENERGY_TARGET / energy
        val out = values.copyOf()
        out[0] *= scale
        for (s in 0 until CabProfile.SECTIONS) { out[1 + 4 * s] *= scale; out[2 + 4 * s] *= scale }
        return out
    }

    /** RMS difference in dB between candidate and target over the band a guitar cabinet lives in. */
    private fun shapeErrorDb(
        values: FloatArray, frequencies: DoubleArray,
        targetRe: DoubleArray, targetIm: DoubleArray, rateIn: Double
    ): Double {
        var sum = 0.0; var count = 0; var bias = 0.0
        val diffs = ArrayList<Double>(GRID)
        for (k in 0 until GRID) {
            val hz = frequencies[k]
            if (hz < 60 || hz > 12000 || hz > rateIn / 2) continue
            val t = sqrt(targetRe[k] * targetRe[k] + targetIm[k] * targetIm[k])
            if (t <= 0) continue
            val c = CabProfile.magnitudeAtHz(values, hz)
            val d = 20 * log10(max(c, 1e-12)) - 20 * log10(t)
            diffs.add(d); bias += d; count++
        }
        if (count == 0) return Double.NaN
        bias /= count
        for (d in diffs) sum += (d - bias) * (d - bias)
        return sqrt(sum / count)
    }
}
