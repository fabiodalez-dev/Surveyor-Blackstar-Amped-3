package com.example.amped3controller

import com.example.amped3controller.dsp.CabLab
import org.json.JSONObject

/**
 * How a designed cabinet was made, kept with the profile so it can be shown, exported and edited
 * again. A profile that only carries 65 numbers cannot say "60% of a 4x12 and 40% of a 2x12";
 * this can, and from it the same profile can be rebuilt.
 *
 * [a] and [b] are factory keys, "cab:mic:axis". [template] is the pole bank the fit uses; null
 * means the one the app picks.
 */
data class Recipe(
    val a: String,
    val b: String? = null,
    val ratioA: Double = 1.0,
    val mode: CabLab.BlendMode = CabLab.BlendMode.COMPLEX,
    val shape: CabLab.Shape = CabLab.Shape(),
    val template: String? = null
) {
    val isBlend get() = b != null && b != a && ratioA < 1.0

    fun json(): JSONObject = JSONObject().put("a", a).put("b", b).put("ratioA", ratioA).put("mode", mode.name)
        .put("template", template)
        .put("shape", JSONObject().put("tilt", shape.tiltDbPerOctave)
            .put("lowHz", shape.lowShelfHz).put("lowDb", shape.lowShelfDb)
            .put("highHz", shape.highShelfHz).put("highDb", shape.highShelfDb)
            .put("peakHz", shape.peakHz).put("peakDb", shape.peakDb).put("peakOct", shape.peakOctaves)
            .put("scale", shape.scale))

    /** One line for a list: what it is made of. */
    fun describe(name: (String) -> String): String {
        val parts = ArrayList<String>()
        parts += if (isBlend) "${(ratioA * 100).toInt()}% ${name(a)} + ${(100 - (ratioA * 100).toInt())}% ${name(b!!)}" +
            (if (mode == CabLab.BlendMode.ENERGY) " (energy)" else "") else name(a)
        val s = shape
        if (s.tiltDbPerOctave != 0.0) parts += "tilt %+.1f dB/oct".format(java.util.Locale.US, s.tiltDbPerOctave)
        if (s.lowShelfDb != 0.0) parts += "low %+.1f dB @ %.0f Hz".format(java.util.Locale.US, s.lowShelfDb, s.lowShelfHz)
        if (s.highShelfDb != 0.0) parts += "high %+.1f dB @ %.0f Hz".format(java.util.Locale.US, s.highShelfDb, s.highShelfHz)
        if (s.peakDb != 0.0) parts += "peak %+.1f dB @ %.0f Hz".format(java.util.Locale.US, s.peakDb, s.peakHz)
        if (s.scale != 1.0) parts += "size ×%.2f".format(java.util.Locale.US, 1 / s.scale)
        return parts.joinToString(" · ")
    }

    companion object {
        fun parse(o: JSONObject): Recipe {
            val sh = o.optJSONObject("shape") ?: JSONObject()
            val mode = runCatching { CabLab.BlendMode.valueOf(o.optString("mode", "COMPLEX")) }.getOrDefault(CabLab.BlendMode.COMPLEX)
            fun key(s: String?): String? = s?.takeIf { Regex("""(1?\d|2[0-3]):[0-5]:[01]""").matches(it) }
            val a = key(o.optString("a")) ?: throw IllegalArgumentException("recipe without a source")
            return Recipe(a, key(o.optString("b", "")), o.optDouble("ratioA", 1.0).coerceIn(0.0, 1.0), mode,
                CabLab.Shape(
                    sh.optDouble("tilt", 0.0).coerceIn(-6.0, 6.0),
                    sh.optDouble("lowHz", 120.0).coerceIn(40.0, 500.0), sh.optDouble("lowDb", 0.0).coerceIn(-12.0, 12.0),
                    sh.optDouble("highHz", 4000.0).coerceIn(1000.0, 12000.0), sh.optDouble("highDb", 0.0).coerceIn(-12.0, 12.0),
                    sh.optDouble("peakHz", 1000.0).coerceIn(60.0, 10000.0), sh.optDouble("peakDb", 0.0).coerceIn(-12.0, 12.0),
                    sh.optDouble("peakOct", 1.0).coerceIn(0.2, 3.0),
                    sh.optDouble("scale", 1.0).coerceIn(0.7, 1.4)),
                key(o.optString("template", "")))
        }
    }
}

/** What the user chose for a WAV before it was fitted. */
data class IrOptions(
    /** [com.example.amped3controller.dsp.Wav.MIX], or a channel index. */
    val channel: Int = 0,
    val lengthMs: Int = com.example.amped3controller.dsp.Fit.MAX_MS,
    /** A factory key to fit on; null takes the loaded cabinet, [AUTO] tries the nearest three. */
    val template: String? = null
) {
    companion object { const val AUTO = "auto" }
}
