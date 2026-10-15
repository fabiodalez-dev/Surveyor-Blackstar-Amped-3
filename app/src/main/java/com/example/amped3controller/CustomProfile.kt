package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * A cabinet profile fitted from an impulse response, or designed from factory cabinets, rather
 * than shipped by Blackstar.
 *
 * It carries the transfer payload plus where it came from and what the checks said, because a
 * profile that cannot say how it was made is not one to send to an amplifier a year from now.
 * `cab`, `mic` and `axis` are the template's, so the pedal keeps reporting a coherent selection.
 * A designed profile also keeps its [recipe], so it can be shown, shared and edited again.
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
    val attenuatedDb: Double,
    /** Whether it passed the checks when it was made. A profile that did not is never sent. */
    val passed: Boolean = true,
    val recipe: Recipe? = null,
    val notes: String = "",
    val favorite: Boolean = false
) {
    /** The shape the DSP transfer expects, identical to a factory profile's. */
    fun transferJson(): JSONObject = JSONObject()
        .put("cab", cab).put("mic", mic).put("axis", axis)
        .put("header", header).put("chunks", JSONArray(chunks))

    fun json(): JSONObject = transferJson()
        .put("id", id).put("name", name).put("created", created).put("source", source)
        .put("templateKey", templateKey).put("errorDb", errorDb).put("attenuatedDb", attenuatedDb)
        .put("passed", passed).put("recipe", recipe?.json()).put("notes", notes).put("favorite", favorite)

    /** A single profile as a file of its own, for sharing. */
    fun exportJson(): String = JSONObject().put("format", FORMAT).put("profile", json()).toString(2)

    companion object {
        const val FORMAT = "amped-custom-profile-v1"

        fun parse(o: JSONObject): CustomProfile {
            val chunks = o.getJSONArray("chunks")
            require(chunks.length() == 5)
            return CustomProfile(
                o.getString("id").take(100), o.getString("name").take(60),
                o.optLong("created"), o.optString("source").take(120),
                o.optString("templateKey").take(20),
                o.getInt("cab"), o.getInt("mic"), o.getInt("axis"),
                o.getString("header"), (0 until 5).map { chunks.getString(it) },
                o.optDouble("errorDb", Double.NaN), o.optDouble("attenuatedDb", 0.0),
                o.optBoolean("passed", false),
                o.optJSONObject("recipe")?.let { runCatching { Recipe.parse(it) }.getOrNull() },
                o.optString("notes").take(500), o.optBoolean("favorite", false)
            )
        }

        fun parseList(text: String): List<CustomProfile> {
            val a = JSONArray(text)
            require(a.length() <= 200)
            return (0 until a.length()).map { i -> parse(a.getJSONObject(i)) }
        }

        /** The inverse of [exportJson]; null when the text is not a profile file. */
        fun parseExport(text: String): CustomProfile? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (root.optString("format") != FORMAT) return null
            return parse(root.getJSONObject("profile"))
        }
    }
}
