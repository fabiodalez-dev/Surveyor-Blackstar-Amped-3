package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * A cabinet profile fitted from an impulse response rather than shipped by Blackstar.
 *
 * It carries the transfer payload plus where it came from and what the checks said, because a
 * profile that cannot say how it was made is not one to send to an amplifier a year from now.
 * `cab`, `mic` and `axis` are the template's, so the pedal keeps reporting a coherent selection.
 */
data class CustomProfile(
    val id: String,
    val name: String,
    val created: Long,
    val source: String,
    val templateKey: String,
    val cab: Int,
    val mic: Int,
    val axis: Int,
    val header: String,
    val chunks: List<String>,
    val errorDb: Double,
    val attenuatedDb: Double
) {
    /** The shape the DSP transfer expects, identical to a factory profile's. */
    fun transferJson(): JSONObject = JSONObject()
        .put("cab", cab).put("mic", mic).put("axis", axis)
        .put("header", header).put("chunks", JSONArray(chunks))

    fun json(): JSONObject = transferJson()
        .put("id", id).put("name", name).put("created", created).put("source", source)
        .put("templateKey", templateKey).put("errorDb", errorDb).put("attenuatedDb", attenuatedDb)

    companion object {
        fun parseList(text: String): List<CustomProfile> {
            val a = JSONArray(text)
            require(a.length() <= 200)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val chunks = o.getJSONArray("chunks")
                require(chunks.length() == 5)
                CustomProfile(
                    o.getString("id").take(100), o.getString("name").take(60),
                    o.optLong("created"), o.optString("source").take(120),
                    o.optString("templateKey").take(20),
                    o.getInt("cab"), o.getInt("mic"), o.getInt("axis"),
                    o.getString("header"), (0 until 5).map { chunks.getString(it) },
                    o.optDouble("errorDb", Double.NaN), o.optDouble("attenuatedDb", 0.0)
                )
            }
        }
    }
}
