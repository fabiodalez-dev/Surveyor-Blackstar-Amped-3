package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Builds the shipped cabinet library, `assets/cab_library.json`, with the app's own fit, so what
 * ships is exactly what the app would produce from the same WAV.
 *
 * Put the impulse responses and a `manifest.json` in `build/ir_library/` (the WAVs are not in the
 * repository, the manifest records their sha256 and origin) and run this test. With no manifest
 * it does nothing. Designed cabinets come from `build/ir_library/designed.json`, a list of
 * recipes, and are fitted the same way. The `facets` of each entry (brand, size, speakers,
 * microphones, source) are written by `tools/cab_facets.py` and carried over by id; a new entry
 * gets them when the script is run again.
 */
class LibraryBuildHarness {
    @Test
    fun buildTheShippedLibrary() {
        val dir = File("build/ir_library")
        val manifest = File(dir, "manifest.json")
        assumeTrue("no build/ir_library/manifest.json, nothing to do", manifest.isFile)
        val profiles = FactoryProfiles(File("src/main/assets/cab_profiles.json").readText())
        val out = JSONArray()
        val entries = JSONArray(manifest.readText())
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val wav = File(dir, e.getString("file"))
            val c = IrConversion.convert(wav.readBytes(), e.getString("name"), listOf(-1, -1, -1), profiles, IrOptions(template = IrOptions.AUTO))
            check(c.verdict.passed) { "${e.getString("id")} refused: ${c.verdict.summary}" }
            out.put(entry(e, c, "captured"))
            println("%-22s template %-8s error %.2f dB (lo %.2f mid %.2f hi %.2f) nearest %s".format(java.util.Locale.US,
                e.getString("id"), c.templateKey, c.errorDb, c.bands.lowDb, c.bands.midDb, c.bands.highDb, c.nearest.first().first))
        }
        val designed = File(dir, "designed.json")
        if (designed.isFile) {
            val list = JSONArray(designed.readText())
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                val recipe = Recipe.parse(e.getJSONObject("recipe"))
                val c = IrConversion.design(recipe, e.getString("name"), profiles)
                check(c.verdict.passed) { "${e.getString("id")} refused: ${c.verdict.summary}" }
                out.put(entry(e, c, "designed").put("recipe", recipe.json()))
                println("%-22s template %-8s error %.2f dB".format(java.util.Locale.US, e.getString("id"), c.templateKey, c.errorDb))
            }
        }
        // the facets come from tools/cab_facets.py, not from the fit: keep the ones already in the asset
        val asset = File("src/main/assets/cab_library.json")
        val facets = runCatching { JSONArray(asset.readText()) }.getOrNull()?.let { old ->
            (0 until old.length()).map { old.getJSONObject(it) }.filter { it.has("facets") }.associate { it.getString("id") to it.getJSONObject("facets") }
        }.orEmpty()
        for (i in 0 until out.length()) out.getJSONObject(i).let { e -> facets[e.getString("id")]?.let { e.put("facets", it) } }
        asset.writeText(out.toString(1))
        println("wrote ${out.length()} profiles to src/main/assets/cab_library.json")
    }

    private fun entry(e: JSONObject, c: Conversion, kind: String): JSONObject = JSONObject()
        .put("id", e.getString("id")).put("name", e.getString("name")).put("kind", kind)
        .put("cabinet", e.optString("cabinet")).put("speaker", e.optString("speaker")).put("mic", e.optString("mic"))
        .put("style", e.optString("style")).put("pack", e.optString("pack")).put("author", e.optString("author"))
        .put("license", e.optString("license")).put("url", e.optString("url")).put("sha256", e.optString("sha256"))
        .put("templateKey", c.templateKey).put("cab", c.cab).put("mic_index", c.mic).put("axis", c.axis)
        .put("header", c.header).put("chunks", JSONArray(c.chunks))
        .put("errorDb", c.errorDb).put("attenuatedDb", c.attenuatedDb)
        .put("bands", JSONObject().put("low", c.bands.lowDb).put("mid", c.bands.midDb).put("high", c.bands.highDb))
}

/**
 * Surveys every candidate impulse response in `build/ir_library/candidates*.json` with the same
 * fit the library uses and writes `build/ir_library/survey.json`: pass or refusal, error overall
 * and per band, pole bank, and the fitted curve, so the shipped set can be chosen offline by
 * error and by how different each result is from the others. Never fails on a bad file.
 */
class LibrarySurveyHarness {
    @Test
    fun surveyTheCandidates() {
        val dir = File("build/ir_library")
        val lists = dir.listFiles { f -> f.name.startsWith("candidates") && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no build/ir_library/candidates*.json, nothing to do", lists.isNotEmpty())
        val profiles = FactoryProfiles(File("src/main/assets/cab_profiles.json").readText())
        val out = JSONArray()
        var ok = 0; var refused = 0; var failed = 0
        for (list in lists) {
            val entries = JSONArray(list.readText())
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val row = JSONObject().put("id", e.getString("id")).put("list", list.name)
                try {
                    val c = IrConversion.convert(File(dir, e.getString("file")).readBytes(), e.getString("name"), listOf(-1, -1, -1), profiles, IrOptions(template = IrOptions.AUTO))
                    val values: FloatArray = com.example.amped3controller.dsp.CabProfile.decode(c.header, c.chunks) ?: error("undecodable")
                    val curve = com.example.amped3controller.dsp.CabLab.curveDb(values)
                    row.put("passed", c.verdict.passed).put("summary", c.verdict.summary).put("templateKey", c.templateKey)
                        .put("errorDb", c.errorDb).put("attenuatedDb", c.attenuatedDb)
                        .put("low", c.bands.lowDb).put("mid", c.bands.midDb).put("high", c.bands.highDb)
                        .put("curve", JSONArray(curve.map { Math.round(it * 100) / 100.0 }))
                    if (c.verdict.passed) ok++ else refused++
                } catch (t: Throwable) {
                    row.put("passed", false).put("summary", "failed: ${t.message}"); failed++
                }
                out.put(row)
                if (out.length() % 50 == 0) println("surveyed ${out.length()} (ok $ok, refused $refused, failed $failed)")
            }
        }
        File(dir, "survey.json").writeText(out.toString())
        println("survey: ${out.length()} candidates, ok $ok, refused $refused, failed $failed")
    }
}
