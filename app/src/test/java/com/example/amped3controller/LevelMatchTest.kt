package com.example.amped3controller

import com.example.amped3controller.dsp.CabProfile
import com.example.amped3controller.dsp.Loudness
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LevelMatchTest {
    private val profiles = FactoryProfiles(File("src/main/assets/cab_profiles.json").readText())
    private val usable = profiles.keys.filter { it !in profiles.marginal }
    private fun stepDb(steps: Int) = steps * 24.0 / 127.0

    @Test fun loudnessAgreesWithTheOfflineTool() {
        // tools/loudness.py, same grid and weighting, numpy instead of this code
        assertEquals(-8.372, profiles.loudnessDb("2:0:0"), 0.02)
        assertEquals(-2.939, profiles.loudnessDb("17:5:1"), 0.02)
        assertEquals(-7.189, profiles.loudnessDb("21:0:0"), 0.02)
        assertEquals(profiles.loudnessDb("2:0:0"), profiles.loudnessReference, 0.0)
    }

    @Test fun everyFactoryCabinetLandsOnTheReferenceWithinOneStep() {
        val corrected = usable.map { profiles.loudnessDb(it) + stepDb(LevelMatch.steps(profiles.correctionDb(it))) }
        for (c in corrected) assertEquals(profiles.loudnessReference, c, 24.0 / 127.0 / 2 + 1e-9)
        val spreadBefore = usable.maxOf { profiles.loudnessDb(it) } - usable.minOf { profiles.loudnessDb(it) }
        assertTrue("spread before $spreadBefore", spreadBefore > 5.0)
        assertTrue(corrected.max() - corrected.min() <= 24.0 / 127.0 + 1e-9)
    }

    @Test fun levellingOnlyEverTurnsDown() {
        val lib = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText())
        val all = usable.map { profiles.correctionDb(it) } +
            lib.map { profiles.correctionDb(CabProfile.decode(it.header, it.chunks)!!) }
        assertTrue(all.all { it <= 0.0 })
        assertTrue("largest cut ${all.min()}", all.min() > -6.5)
        // a shipped cabinet quieter than every factory one is left alone, not raised
        assertTrue(lib.any { Loudness.db(CabProfile.decode(it.header, it.chunks)!!) < profiles.loudnessReference })
        assertEquals(0, LevelMatch.steps(+3.0))
    }

    @Test fun planKeepsTheUsersLevel() {
        // loud cabinet: 5 steps down from the user's 90
        val loud = LevelMatch.plan(now = 90, applied = 0, target = -5)
        assertEquals(LevelMatch.Plan(90, 85), loud); assertEquals(-5, loud.applied)
        // back to a cabinet that needs nothing: the user's level, not more
        assertEquals(90, LevelMatch.plan(now = 85, applied = -5, target = 0).level)
        // the fader moved down 10 while corrected: the user's level moved with it
        assertEquals(LevelMatch.Plan(80, 77), LevelMatch.plan(now = 75, applied = -5, target = -3))
        // clamped at the bottom, the correction recorded is what really happened
        val low = LevelMatch.plan(now = 2, applied = 0, target = -5)
        assertEquals(0, low.level); assertEquals(-2, low.applied)
        assertEquals(2, LevelMatch.plan(now = 0, applied = low.applied, target = 0).level)
        // never above the user's level, whatever is passed
        for (now in listOf(0, 64, 127)) for (applied in -10..0) for (target in -10..10) {
            val p = LevelMatch.plan(now, applied, target)
            assertTrue(p.level <= p.own && p.level in 0..127)
        }
    }
}

class LevelMatchPrefsTest {
    private val dir: File = Files.createTempDirectory("amped-level").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun offUntilTurnedOnAndRemembered() {
        val s = LibraryStore(dir) {}
        assertFalse(s.cabinetPrefs.value.levelMatch)
        s.toggleFavorite("21:0:0")
        s.setLevelMatch(true)
        val again = LibraryStore(dir) {}.cabinetPrefs.value
        assertTrue(again.levelMatch); assertEquals(listOf("21:0:0"), again.favorites)
    }
}
