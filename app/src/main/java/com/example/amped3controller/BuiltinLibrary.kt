package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cabinets that ship with the app on top of the 288 factory ones: profiles fitted, with the app's
 * own code, from impulse responses whose licence allows it (CC0), plus a few designed from the
 * factory library. `assets/cab_library.json` is produced by `LibraryBuildHarness`; every entry
 * records its origin, licence and the sha256 of the WAV it came from. See docs/CAB_LIBRARY.md.
 */
data class LibraryCab(
    val id: String, val name: String, val kind: String,
    val cabinet: String, val speaker: String, val mic: String, val style: String,
    val pack: String, val author: String, val license: String, val url: String,
    val templateKey: String, val cab: Int, val micIndex: Int, val axis: Int,
    val header: String, val chunks: List<String>,
    val errorDb: Double, val attenuatedDb: Double,
    val bandLow: Double, val bandMid: Double, val bandHigh: Double,
    val recipe: Recipe?
) {
    val captured get() = kind == "captured"

    /** The make the cabinet is sold or described under, for the brand filter; "other" when none is named. */
    val brand: String get() = brandOf("$cabinet $name $pack")

    /** Everything a text search can match. */
    val searchText: String get() = "$name $cabinet $speaker $mic $pack $author $style".lowercase()

    /** As a custom profile, so audition, slot write and the lab treat it like any other. */
    fun toCustomProfile(): CustomProfile = CustomProfile("lib:$id", name, 0L,
        if (captured) pack else T("progettata", "designed"), templateKey, cab, micIndex, axis, header, chunks,
        errorDb, attenuatedDb, true, recipe, "$cabinet · $speaker · $mic", false)

    companion object {
        /** Brand keywords in the order they are tested; the first hit wins. */
        val brands = listOf("Mesa", "Fender", "Marshall", "Orange", "Vox", "Bogner", "ENGL", "EVH", "Friedman", "Matchless", "Two-Rock", "Hiwatt", "Ampeg", "Roland", "Peavey", "Soldano", "Blackstar", "Laney")
        private val brandPatterns = mapOf("Mesa" to Regex("mesa|boogie|recto|rectifier|lone ?star|cali"), "Fender" to Regex("fender|deluxe|twin|bassman|tweed|blackface|princeton|frontman"),
            "Marshall" to Regex("marshall|1960|jcm|plexi|1959|brit"), "Orange" to Regex("orange|ppc|matamp"), "Vox" to Regex("\\bvox\\b|ac30|ac-30|a30"), "Bogner" to Regex("bogner|uber"),
            "ENGL" to Regex("engl|ngl pro"), "EVH" to Regex("\\bevh\\b|5150"), "Friedman" to Regex("friedman"), "Matchless" to Regex("matchless"), "Two-Rock" to Regex("two.?rock|2rock"),
            "Hiwatt" to Regex("hiwatt|whowatt"), "Ampeg" to Regex("ampeg|svt"), "Roland" to Regex("roland|jc-?120|jazz chorus"), "Peavey" to Regex("peavey|6505"), "Soldano" to Regex("soldano"),
            "Blackstar" to Regex("blackstar"), "Laney" to Regex("laney"))
        fun brandOf(text: String): String { val t = text.lowercase(); return brands.firstOrNull { brandPatterns[it]!!.containsMatchIn(t) } ?: "other" }

        fun parseList(text: String): List<LibraryCab> {
            val a = JSONArray(text)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val chunks = o.getJSONArray("chunks")
                require(chunks.length() == 5)
                val bands = o.optJSONObject("bands") ?: JSONObject()
                LibraryCab(o.getString("id"), o.getString("name"), o.optString("kind", "captured"),
                    o.optString("cabinet"), o.optString("speaker"), o.optString("mic"), o.optString("style"),
                    o.optString("pack"), o.optString("author"), o.optString("license"), o.optString("url"),
                    o.getString("templateKey"), o.getInt("cab"), o.getInt("mic_index"), o.getInt("axis"),
                    o.getString("header"), (0 until 5).map { chunks.getString(it) },
                    o.optDouble("errorDb", Double.NaN), o.optDouble("attenuatedDb", 0.0),
                    bands.optDouble("low", Double.NaN), bands.optDouble("mid", Double.NaN), bands.optDouble("high", Double.NaN),
                    o.optJSONObject("recipe")?.let { runCatching { Recipe.parse(it) }.getOrNull() })
            }
        }
    }
}
