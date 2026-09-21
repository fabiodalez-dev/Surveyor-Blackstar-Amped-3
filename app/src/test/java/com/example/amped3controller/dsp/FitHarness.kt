package com.example.amped3controller.dsp

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Produces, with the app's own code, the exact profile the app would send, so a hardware test
 * exercises the shipping algorithm rather than a re-implementation of it.
 *
 * Put a WAV at build/fit_input.wav and the fitted profile appears at build/fit_output.json.
 * With no input file the test does nothing, so it stays out of the way of the normal suite.
 */
class FitHarness {
    @Test
    fun fitWhateverIsInTheBuildDirectory() {
        val input = File("build/fit_input.wav")
        assumeTrue("no build/fit_input.wav, nothing to do", input.isFile)
        val key = File("build/fit_template.txt").let {
            if (it.isFile) it.readText().trim() else "21:5:0"
        }
        val pattern = Regex(
            """\{\s*"cab":\s*(\d+),\s*"mic":\s*(\d+),\s*"axis":\s*(\d+),\s*"header":\s*"([0-9a-f]+)",\s*"chunks":\s*\[(.*?)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val chunkPattern = Regex(""""([0-9a-f]+)"""")
        val match = pattern.findAll(File("src/main/assets/cab_profiles.json").readText()).first {
            "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}" == key
        }
        val templateHeader = match.groupValues[4]
        val templateChunks = chunkPattern.findAll(match.groupValues[5]).map { it.groupValues[1] }.toList()
        val template = CabProfile.decode(templateHeader, templateChunks)!!

        val audio = Wav.decode(input.readBytes())!!
        val prepared = Fit.prepare(audio)
        val result = Fit.fit(template, Fit.minimumPhase(prepared), audio.rate)
        val (header, chunks) = CabProfile.encode(templateHeader, result.values)!!
        val m = result.verdict.metrics!!

        File("build/fit_output.json").writeText(buildString {
            append("{\n")
            append(""" "template": "$key",""").append('\n')
            append(""" "cab": ${match.groupValues[1]}, "mic": ${match.groupValues[2]}, "axis": ${match.groupValues[3]},""").append('\n')
            append(""" "source": "${input.name}", "rate": ${audio.rate}, "channels": ${audio.channels},""").append('\n')
            append(""" "usedSamples": ${prepared.size},""").append('\n')
            append(""" "passed": ${result.verdict.passed}, "errorDb": ${"%.4f".format(java.util.Locale.US, result.errorDb)},""").append('\n')
            append(""" "attenuatedDb": ${"%.3f".format(java.util.Locale.US, result.attenuatedDb)}, "ridge": ${result.ridge},""").append('\n')
            append(""" "failures": [${result.verdict.failures.joinToString(",") { "\"$it\"" }}],""").append('\n')
            append(""" "metrics": {"peakDb": ${"%.3f".format(java.util.Locale.US, m.peakDb)}, "peakHz": ${"%.1f".format(java.util.Locale.US, m.peakHz)},""").append('\n')
            append("""  "dcDb": ${"%.2f".format(java.util.Locale.US, m.dcDb)}, "hz20Db": ${"%.2f".format(java.util.Locale.US, m.hz20Db)},""").append('\n')
            append("""  "hz20kDb": ${"%.2f".format(java.util.Locale.US, m.hz20kDb)}, "nyquistDb": ${"%.2f".format(java.util.Locale.US, m.nyquistDb)},""").append('\n')
            append("""  "irPeak": ${"%.5f".format(java.util.Locale.US, m.irPeak)}, "irL1": ${"%.4f".format(java.util.Locale.US, m.irL1)},""").append('\n')
            append("""  "energy": ${"%.5f".format(java.util.Locale.US, m.energy)}, "t60": ${"%.4f".format(java.util.Locale.US, m.t60)},""").append('\n')
            append("""  "tailDb": ${"%.2f".format(java.util.Locale.US, m.tailDb)}, "float32DriftDb": ${"%.2f".format(java.util.Locale.US, m.float32DriftDb)}},""").append('\n')
            append(""" "header": "$header",""").append('\n')
            append(""" "chunks": [${chunks.joinToString(",") { "\"$it\"" }}],""").append('\n')
            append(""" "values": [${result.values.joinToString(",") { "%.9g".format(java.util.Locale.US, it) }}]""").append('\n')
            append("}\n")
        })
        println("scritto build/fit_output.json")
    }
}
