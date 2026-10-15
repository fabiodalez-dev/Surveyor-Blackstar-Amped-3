package com.example.amped3controller

import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import com.example.amped3controller.dsp.Fit
import com.example.amped3controller.dsp.SafetyCheck
import com.example.amped3controller.dsp.Wav

/** A fitted candidate waiting for the user to decide: audition it, save it, or drop it. */
class Conversion(
    val header: String, val chunks: List<String>, val templateKey: String,
    val templateHeader: String, val templateChunks: List<String>,
    val cab: Int, val mic: Int, val axis: Int, val source: String,
    val rate: Double, val channels: Int, val usedMs: Int, val truncated: Boolean,
    val errorDb: Double, val attenuatedDb: Double,
    val verdict: SafetyCheck.Verdict,
    /** The target the fit was aimed at, on [CabLab.GRID_HZ], so the result can be drawn against it. */
    val targetCurve: DoubleArray,
    /** Where the fit lost: lows, mids, highs. */
    val bands: CabLab.BandErrors,
    /** Factory profiles closest to the target, nearest first. */
    val nearest: List<Pair<String, Double>>,
    /** Present when the profile was designed rather than imported. */
    val recipe: Recipe? = null,
    val options: IrOptions? = null
)

object IrConversion {
    /**
     * Fits a WAV impulse response onto a factory pole bank, so the denominators, and with them
     * the stability, are the pedal's own. Pure arithmetic, no USB: it works with no pedal
     * attached so the result can be inspected before anything is sent.
     */
    fun convert(bytes: ByteArray, source: String, liveCab: List<Int>, profiles: FactoryProfiles,
                options: IrOptions = IrOptions()): Conversion {
        val audio = Wav.decode(bytes, options.channel) ?: error(
            if (options.channel > 0) T("Il file non ha quel canale", "The file has no such channel")
            else T("File WAV non leggibile", "Cannot read that WAV file"))
        val prepared = Fit.prepare(audio, options.lengthMs)
        if (prepared.size < 64) error(T("La risposta e' troppo corta o silenziosa", "That impulse response is too short or silent"))
        val ir = Fit.minimumPhase(prepared)
        val target = CabLab.curveDb(ir, audio.rate)
        val nearest = profiles.nearest(target, 3)
        val candidates = templateCandidates(options.template, liveCab, nearest, profiles)
        val (key, fitted) = bestFit(candidates, ir, audio.rate, profiles)
        return finish(key, fitted, source, audio.rate, audio.channels,
            (prepared.size * 1000.0 / audio.rate).toInt(), prepared.size < audio.samples.size,
            target, nearest, profiles, options = options)
    }

    /** A cabinet built from the factory library: a blend, a tone shape, or both, then fitted. */
    fun design(recipe: Recipe, source: String, profiles: FactoryProfiles): Conversion {
        val a = profiles.values(recipe.a)
        val mixed: DoubleArray = if (recipe.isBlend) CabLab.blend(a, profiles.values(recipe.b!!), recipe.ratioA, recipe.mode)
            else CabProfile.impulse(a, CabLab.N)
        val shaped = if (recipe.shape.isIdentity) mixed else {
            // shaping works on the magnitude of whatever the blend produced
            val re = mixed.copyOf(CabLab.N); val im = DoubleArray(CabLab.N)
            com.example.amped3controller.dsp.Fft.transform(re, im, false)
            val mag = DoubleArray(CabLab.BINS) { k -> kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) }
            CabLab.impulseFromMagnitude(CabLab.shape(mag, recipe.shape))
        }
        val ir = CabLab.prepare(shaped)
        if (ir.size < 64) error(T("Risultato vuoto", "Empty result"))
        val rate = CabProfile.SAMPLE_RATE
        val target = CabLab.curveDb(ir, rate)
        val nearest = profiles.nearest(target, 3)
        val preferred = recipe.template ?: recipe.a
        val candidates = templateCandidates(preferred, listOf(-1, -1, -1), nearest, profiles)
        val (key, fitted) = bestFit(candidates, ir, rate, profiles)
        return finish(key, fitted, source, rate, 1, (ir.size * 1000.0 / rate).toInt(), false,
            target, nearest, profiles, recipe = recipe)
    }

    /** The target response a recipe produces, before any fit: what the lab draws while you move faders. */
    fun preview(recipe: Recipe, profiles: FactoryProfiles): DoubleArray {
        val a = profiles.values(recipe.a)
        val mag = if (recipe.isBlend) {
            val b = profiles.values(recipe.b!!)
            val ma = CabLab.magnitudeBins(a); val mb = CabLab.magnitudeBins(b)
            when (recipe.mode) {
                CabLab.BlendMode.ENERGY -> DoubleArray(CabLab.BINS) { kotlin.math.sqrt(recipe.ratioA * ma[it] * ma[it] + (1 - recipe.ratioA) * mb[it] * mb[it]) }
                CabLab.BlendMode.COMPLEX -> {
                    val h = CabLab.blend(a, b, recipe.ratioA, CabLab.BlendMode.COMPLEX)
                    val re = h.copyOf(CabLab.N); val im = DoubleArray(CabLab.N)
                    com.example.amped3controller.dsp.Fft.transform(re, im, false)
                    DoubleArray(CabLab.BINS) { k -> kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) }
                }
            }
        } else CabLab.magnitudeBins(a)
        val shaped = CabLab.shape(mag, recipe.shape)
        // read the bins back on the log grid
        return DoubleArray(CabLab.GRID_HZ.size) { i ->
            val pos = CabLab.GRID_HZ[i] * CabLab.N / CabProfile.SAMPLE_RATE
            val k = pos.toInt().coerceIn(0, CabLab.BINS - 2)
            CabProfile.db(shaped[k] + (shaped[k + 1] - shaped[k]) * (pos - k))
        }
    }

    private fun templateCandidates(choice: String?, liveCab: List<Int>, nearest: List<Pair<String, Double>>,
                                   profiles: FactoryProfiles): List<String> {
        if (choice == IrOptions.AUTO) return nearest.map { it.first }.ifEmpty { listOf("21:5:0") }
        val live = FactoryProfiles.key(liveCab[0], liveCab[1], liveCab[2])
        val key = choice?.takeIf { profiles.find(it) != null } ?: live.takeIf { profiles.find(it) != null } ?: "21:5:0"
        return listOf(if (key in profiles.marginal) "21:5:0" else key)
    }

    private fun bestFit(candidates: List<String>, ir: DoubleArray, rate: Double, profiles: FactoryProfiles): Pair<String, Fit.Result> {
        var best: Pair<String, Fit.Result>? = null
        for (key in candidates) {
            val r = Fit.fit(profiles.values(key), ir, rate)
            val b = best
            if (b == null || (r.verdict.passed && !b.second.verdict.passed) ||
                (r.verdict.passed == b.second.verdict.passed && r.errorDb < b.second.errorDb)) best = key to r
        }
        return best!!
    }

    private fun finish(key: String, fitted: Fit.Result, source: String, rate: Double, channels: Int,
                       usedMs: Int, truncated: Boolean, target: DoubleArray, nearest: List<Pair<String, Double>>,
                       profiles: FactoryProfiles, recipe: Recipe? = null, options: IrOptions? = null): Conversion {
        val json = profiles.find(key)!!
        val header = json.getString("header")
        val encoded = CabProfile.encode(header, fitted.values) ?: error(T("Codifica fallita", "Encoding failed"))
        return Conversion(encoded.first, encoded.second, key, header, profiles.chunks(key),
            json.getInt("cab"), json.getInt("mic"), json.getInt("axis"), source,
            rate, channels, usedMs, truncated, fitted.errorDb, fitted.attenuatedDb, fitted.verdict,
            target, CabLab.bandErrors(CabLab.curveDb(fitted.values), target), nearest, recipe, options)
    }
}
