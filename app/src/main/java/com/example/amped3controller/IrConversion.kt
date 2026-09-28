package com.example.amped3controller

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
    val verdict: SafetyCheck.Verdict
)

object IrConversion {
    /** The three profiles that quantise onto the unit circle are shipped data, not a licence to
     *  generate more of the same, so they are never used as a template. */
    private val marginal = setOf("7:5:0", "22:5:0", "22:5:1")

    /**
     * Fits a WAV impulse response onto the pole bank of the cabinet currently loaded, so the
     * denominators, and with them the stability, are the pedal's own. Pure arithmetic, no USB:
     * it works with no pedal attached so the result can be inspected before anything is sent.
     */
    fun convert(bytes: ByteArray, source: String, liveCab: List<Int>, profiles: FactoryProfiles): Conversion {
        val audio = Wav.decode(bytes) ?: error(T("File WAV non leggibile", "Cannot read that WAV file"))
        val prepared = Fit.prepare(audio)
        if (prepared.size < 64) error(T("La risposta e' troppo corta o silenziosa", "That impulse response is too short or silent"))
        var key = "${liveCab[0]}:${liveCab[1]}:${liveCab[2]}"
        var json = profiles.find(liveCab[0], liveCab[1], liveCab[2])
        if (json == null || key in marginal) { json = profiles.find(21, 5, 0); key = "21:5:0" }
        val header = json!!.getString("header")
        val chunks = json.getJSONArray("chunks").let { a -> (0 until a.length()).map { a.getString(it) } }
        val template = CabProfile.decode(header, chunks) ?: error(T("Profilo di riferimento non leggibile", "Cannot read the reference profile"))
        val fitted = Fit.fit(template, Fit.minimumPhase(prepared), audio.rate)
        val encoded = CabProfile.encode(header, fitted.values) ?: error(T("Codifica fallita", "Encoding failed"))
        return Conversion(encoded.first, encoded.second, key, header, chunks,
            json.getInt("cab"), json.getInt("mic"), json.getInt("axis"), source,
            audio.rate, audio.channels,
            (prepared.size * 1000.0 / audio.rate).toInt(), prepared.size < audio.samples.size,
            fitted.errorDb, fitted.attenuatedDb, fitted.verdict)
    }
}
