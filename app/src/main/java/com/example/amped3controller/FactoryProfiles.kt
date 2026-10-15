package com.example.amped3controller

import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import com.example.amped3controller.dsp.Loudness
import org.json.JSONArray
import org.json.JSONObject

/** The 288 cabinet/mic/axis payloads captured from Architect, as shipped in cab_profiles.json. */
class FactoryProfiles(json: String) {
    private val profiles = JSONArray(json)
    private val byKey: Map<String, JSONObject> = (0 until profiles.length()).map { profiles.getJSONObject(it) }
        .associateBy { "${it.getInt("cab")}:${it.getInt("mic")}:${it.getInt("axis")}" }

    /** The three profiles that quantise onto the unit circle are shipped data, not a licence to
     *  generate more of the same, so they are never used as a template. */
    val marginal = setOf("7:5:0", "22:5:0", "22:5:1")

    val keys: List<String> get() = byKey.keys.toList()

    fun find(cab: Int, mic: Int, axis: Int): JSONObject? = byKey["$cab:$mic:$axis"]
    fun find(key: String): JSONObject? = byKey[key]

    fun header(key: String): String = byKey.getValue(key).getString("header")
    fun chunks(key: String): List<String> = byKey.getValue(key).getJSONArray("chunks").let { a -> (0 until a.length()).map { a.getString(it) } }

    private val decoded = HashMap<String, FloatArray>()
    /** The 65 coefficients, decoded once and kept. */
    @Synchronized fun values(key: String): FloatArray = decoded.getOrPut(key) {
        CabProfile.decode(header(key), chunks(key)) ?: error("Factory profile $key is unreadable")
    }

    private val curves = HashMap<String, DoubleArray>()
    /** Modelled response on [CabLab.GRID_HZ], computed once per key. */
    @Synchronized fun curve(key: String): DoubleArray = curves.getOrPut(key) { CabLab.curveDb(values(key)) }

    private val loudness = HashMap<String, Double>()
    /** Estimated loudness of a factory profile, see [Loudness]; computed once per key. */
    @Synchronized fun loudnessDb(key: String): Double = loudness.getOrPut(key) { Loudness.db(values(key)) }

    /** The quietest non-marginal factory cabinet. Levelling turns every other cabinet down to it,
     *  so it never asks the pedal for more than the user's own level. */
    val loudnessReference: Double by lazy { keys.filter { it !in marginal }.minOf { loudnessDb(it) } }

    /** The level change that brings any profile down to [loudnessReference]; never positive. */
    fun correctionDb(values: FloatArray): Double = Loudness.correctionDb(values, loudnessReference)
    fun correctionDb(key: String): Double = minOf(0.0, loudnessReference - loudnessDb(key))

    /** Every profile's coefficients, for searches; 288 decodes the first time, cached after. */
    fun all(): Map<String, FloatArray> = keys.associateWith { values(it) }

    /** Factory profiles closest in response to [target], never a marginal one. */
    fun nearest(target: DoubleArray, count: Int): List<Pair<String, Double>> =
        keys.filter { it !in marginal }.map { it to CabLab.distanceDb(curve(it), target) }
            .filter { it.second.isFinite() }.sortedBy { it.second }.take(count)

    companion object {
        fun name(key: String): String {
            val (cab, mic, axis) = key.split(":").map { it.toInt() }
            return "${AmpedProtocol.cabinetNames.getOrNull(cab) ?: "?"} · ${AmpedProtocol.micShortNames.getOrNull(mic) ?: "?"} · ${if (axis == 0) "On" else "Off"}"
        }
        fun key(cab: Int, mic: Int, axis: Int) = "$cab:$mic:$axis"
    }
}
