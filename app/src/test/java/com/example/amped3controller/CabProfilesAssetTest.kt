package com.example.amped3controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CabProfilesAssetTest {
    @Test
    fun assetContainsEveryCabMicAxisCombination() {
        val asset = File("src/main/assets/cab_profiles.json")
        assertTrue("cab_profiles.json is missing", asset.isFile)
        val objectPattern = Regex(
            """\{\s*"cab":\s*(\d+),\s*"mic":\s*(\d+),\s*"axis":\s*(\d+),\s*"header":\s*"([0-9a-f]+)",\s*"chunks":\s*\[(.*?)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val chunkPattern = Regex(""""([0-9a-f]+)"""")
        val profiles = objectPattern.findAll(asset.readText()).toList()
        assertEquals(24 * 6 * 2, profiles.size)

        val combinations = mutableSetOf<Triple<Int, Int, Int>>()
        profiles.forEach { profile ->
            val cabinet = profile.groupValues[1].toInt()
            val microphone = profile.groupValues[2].toInt()
            val axis = profile.groupValues[3].toInt()
            assertTrue(cabinet in 0..23)
            assertTrue(microphone in 0..5)
            assertTrue(axis in 0..1)
            assertEquals(128, profile.groupValues[4].length)

            val chunks = chunkPattern.findAll(profile.groupValues[5]).map { it.groupValues[1] }.toList()
            assertEquals(5, chunks.size)
            chunks.forEach { encoded ->
                assertEquals(128, encoded.length)
                assertTrue(encoded.startsWith("ac"))
            }
            assertTrue(
                "duplicate profile for $cabinet/$microphone/$axis",
                combinations.add(Triple(cabinet, microphone, axis)),
            )
        }

        val expected = buildSet {
            for (cabinet in 0..23) {
                for (microphone in 0..5) {
                    for (axis in 0..1) {
                        add(Triple(cabinet, microphone, axis))
                    }
                }
            }
        }
        assertEquals(expected, combinations)
    }
}
