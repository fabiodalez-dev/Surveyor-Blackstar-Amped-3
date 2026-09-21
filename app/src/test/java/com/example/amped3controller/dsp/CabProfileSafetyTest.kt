package com.example.amped3controller.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The factory library is the yardstick: the checks must accept what Blackstar ships and reject
 * what it does not. Three profiles (7:5:0, 22:5:0, 22:5:1) quantise onto the unit circle; they
 * stay usable as shipped data but must never be accepted as something we generated.
 */
class CabProfileSafetyTest {
    data class Entry(val key: String, val header: String, val chunks: List<String>)

    private val marginal = setOf("7:5:0", "22:5:0", "22:5:1")

    private fun library(): List<Entry> {
        val asset = File("src/main/assets/cab_profiles.json")
        assertTrue("cab_profiles.json is missing", asset.isFile)
        val objectPattern = Regex(
            """\{\s*"cab":\s*(\d+),\s*"mic":\s*(\d+),\s*"axis":\s*(\d+),\s*"header":\s*"([0-9a-f]+)",\s*"chunks":\s*\[(.*?)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val chunkPattern = Regex(""""([0-9a-f]+)"""")
        return objectPattern.findAll(asset.readText()).map {
            Entry("${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}",
                it.groupValues[4],
                chunkPattern.findAll(it.groupValues[5]).map { c -> c.groupValues[1] }.toList())
        }.toList()
    }

    private fun payload(v: FloatArray) = v.joinToString(",") { it.toRawBits().toString() }

    @Test
    fun everyFactoryProfileDecodesAndSurvivesARoundTrip() {
        val all = library()
        assertEquals(288, all.size)
        all.forEach { e ->
            val values = CabProfile.decode(e.header, e.chunks)
            assertTrue("${e.key} does not decode", values != null)
            val (header, chunks) = CabProfile.encode(e.header, values!!)!!
            val again = CabProfile.decode(header, chunks)
            assertEquals("${e.key} changes across a round trip", payload(values), payload(again!!))
            assertEquals("${e.key} chunk count", 5, chunks.size)
            // Architect pads its last chunk with whatever was in memory past the declared length;
            // the encoder writes zeros there, so compare the payloads, not the raw reports
            assertEquals("${e.key} header payload changed",
                CabProfile.bytesToHex(CabProfile.unwrap(e.header, 0xaa)!!),
                CabProfile.bytesToHex(CabProfile.unwrap(header, 0xaa)!!))
            e.chunks.forEachIndexed { i, original ->
                assertEquals("${e.key} chunk $i payload changed",
                    CabProfile.bytesToHex(CabProfile.unwrap(original, 0xac)!!),
                    CabProfile.bytesToHex(CabProfile.unwrap(chunks[i], 0xac)!!))
            }
        }
    }

    @Test
    fun theLibraryPassesItsOwnLimitsExceptTheThreeMarginalProfiles() {
        val failures = StringBuilder()
        var checked = 0
        library().forEach { e ->
            val values = CabProfile.decode(e.header, e.chunks)!!
            val verdict = SafetyCheck.verify(values)
            checked++
            if (e.key in marginal) {
                assertFalse("${e.key} sits on the unit circle and must not be accepted", verdict.passed)
                assertTrue("${e.key} should fail on the pole margin, not ${verdict.failures}",
                    verdict.failures.any { it.contains("z=1") })
            } else if (!verdict.passed) {
                failures.append("${e.key}: ${verdict.summary}\n")
            }
        }
        assertEquals(288, checked)
        assertEquals("factory profiles rejected by our own limits:\n$failures", 0, failures.length)
    }

    @Test
    fun loudAndRingingCandidatesAreRefused() {
        val e = library().first { it.key == "21:5:0" }
        val good = CabProfile.decode(e.header, e.chunks)!!
        assertTrue("the template itself must be acceptable", SafetyCheck.verify(good).passed)

        val loud = good.copyOf()
        for (s in 0 until CabProfile.SECTIONS) { loud[1 + 4 * s] *= 12f; loud[2 + 4 * s] *= 12f }
        loud[0] *= 12f
        assertFalse("a profile 20 dB louder than the cabinet must be refused",
            SafetyCheck.verify(loud, good).passed)

        val integrator = good.copyOf()
        integrator[3 + 4 * 15] = -1.9999f
        integrator[4 + 4 * 15] = 0.9999f
        assertFalse("a pole on z=1 must be refused", SafetyCheck.verify(integrator).passed)

        val moved = good.copyOf()
        moved[4 + 4 * 3] = moved[4 + 4 * 3] * 0.999f
        val movedVerdict = SafetyCheck.verify(moved, good)
        assertFalse("a changed pole bank must be refused", movedVerdict.passed)
        assertTrue(movedVerdict.failures.any { it.contains("pole bank") })
    }
}
