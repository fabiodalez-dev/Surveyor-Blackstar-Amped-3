package com.example.amped3controller

import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import com.example.amped3controller.dsp.SafetyCheck
import com.example.amped3controller.dsp.Wav
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Builds small WAV files for tests. */
object WavBuilder {
    fun pcm16(vararg channels: DoubleArray, rate: Int): ByteArray {
        val frames = channels[0].size
        val data = ByteArrayOutputStream()
        for (i in 0 until frames) for (ch in channels) {
            val v = (ch[i].coerceIn(-1.0, 1.0) * 32767).toInt()
            data.write(v and 255); data.write((v shr 8) and 255)
        }
        val payload = data.toByteArray()
        val out = ByteArrayOutputStream()
        fun le32(v: Int) { for (k in 0 until 4) out.write((v shr (8 * k)) and 255) }
        fun le16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); le32(36 + payload.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels.size); le32(rate)
        le32(rate * channels.size * 2); le16(channels.size * 2); le16(16)
        out.write("data".toByteArray()); le32(payload.size); out.write(payload)
        return out.toByteArray()
    }
    fun mono16(samples: DoubleArray, rate: Int): ByteArray = pcm16(samples, rate = rate)
}

class CabinetLibraryTest {
    private val profiles = FactoryProfiles(File("src/main/assets/cab_profiles.json").readText())

    @Test fun shippedLibraryIsReadableSafeAndAttributed() {
        val lib = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText())
        assertTrue("fewer than 25 cabinets: ${lib.size}", lib.size >= 25)
        assertEquals(lib.size, lib.map { it.id }.distinct().size)
        for (cab in lib) {
            val values: FloatArray = CabProfile.decode(cab.header, cab.chunks) ?: error("${cab.id} unreadable")
            val template = profiles.values(cab.templateKey)
            val verdict = SafetyCheck.verify(values, template)
            assertTrue("${cab.id}: ${verdict.summary}", verdict.passed)
            assertTrue(cab.license.isNotBlank() || !cab.captured)
            assertEquals(listOf(cab.cab, cab.micIndex, cab.axis), cab.templateKey.split(":").map { it.toInt() })
            if (!cab.captured) assertNotNull("${cab.id} has no recipe", cab.recipe)
        }
        assertTrue(lib.count { it.captured } >= 20)
        assertTrue(lib.all { it.captured || it.recipe != null })
        assertTrue(lib.filter { it.captured }.all { it.pack.isNotBlank() && it.author.isNotBlank() })
        assertTrue("brands: ${lib.map { it.brand }.distinct()}", setOf("Mesa", "Fender", "Marshall").all { b -> lib.any { it.brand == b } })
    }

    @Test fun aShippedCabinetBecomesAnOrdinaryCustomProfile() {
        val cab = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText()).first()
        val p = cab.toCustomProfile()
        assertEquals("lib:${cab.id}", p.id)
        assertTrue(p.passed)
        assertEquals(p, CustomProfile.parse(p.json()))
    }

    @Test fun stereoWavChannelsCanBeChosen() {
        val left = DoubleArray(64) { if (it == 0) 0.5 else 0.0 }
        val right = DoubleArray(64) { if (it == 1) 0.25 else 0.0 }
        val wav = WavBuilder.pcm16(left, right, rate = 48000)
        val l = Wav.decode(wav, 0)!!; val r = Wav.decode(wav, 1)!!; val m = Wav.decode(wav, Wav.MIX)!!
        assertEquals(2, l.channels)
        assertEquals(0.5, l.samples[0], 1e-3); assertEquals(0.0, l.samples[1], 1e-3)
        assertEquals(0.25, r.samples[1], 1e-3)
        assertEquals(0.25, m.samples[0], 1e-3); assertEquals(0.125, m.samples[1], 1e-3)
        assertNull(Wav.decode(wav, 2))
    }

    @Test fun lengthOptionShortensWhatIsFitted() {
        val ir = DoubleArray(48000) { if (it == 0) 1.0 else 0.001 }
        val audio = com.example.amped3controller.dsp.Audio(48000.0, ir)
        assertEquals(48000 * 170 / 1000, com.example.amped3controller.dsp.Fit.prepare(audio).size)
        assertEquals(48000 * 40 / 1000, com.example.amped3controller.dsp.Fit.prepare(audio, 40).size)
    }

    @Test fun recipeSurvivesJsonAndClampsNonsense() {
        val r = Recipe("21:0:0", "12:4:1", 0.3, CabLab.BlendMode.ENERGY, CabLab.Shape(tiltDbPerOctave = -1.5, scale = 0.9), "20:0:0")
        assertEquals(r, Recipe.parse(r.json()))
        val wild = Recipe.parse(JSONObject().put("a", "21:0:0").put("ratioA", 7).put("shape", JSONObject().put("tilt", 99).put("scale", 0.1)))
        assertEquals(1.0, wild.ratioA, 0.0); assertEquals(6.0, wild.shape.tiltDbPerOctave, 0.0); assertEquals(0.7, wild.shape.scale, 0.0)
        assertFalse(wild.isBlend)
        assertTrue(r.describe { it }.contains("30% 21:0:0 + 70% 12:4:1"))
    }

    @Test(expected = IllegalArgumentException::class) fun recipeWithoutSourceIsRefused() { Recipe.parse(JSONObject().put("b", "1:0:0")) }

    @Test fun customProfileKeepsRecipeNotesAndFavourite() {
        val cab = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText()).first { !it.captured }
        val p = cab.toCustomProfile().copy(notes = "for the ballad", favorite = true)
        val back = CustomProfile.parse(p.json())
        assertEquals(p, back); assertEquals(cab.recipe, back.recipe)
        val exported = CustomProfile.parseExport(p.exportJson())!!
        assertEquals(p, exported)
        assertNull(CustomProfile.parseExport("{\"format\":\"something-else\"}"))
    }
}

class CustomLibraryStoreTest {
    private val dir: File = Files.createTempDirectory("amped-lab").toFile()
    private fun store() = LibraryStore(dir) {}
    @After fun cleanup() { dir.deleteRecursively() }
    private val sample = LibraryCab.parseList(File("src/main/assets/cab_library.json").readText()).first().toCustomProfile()

    @Test fun editDuplicateAndFavouritePersist() {
        val s = store()
        assertNull(s.importCustom(sample))
        val id = s.customProfiles.value.single().id
        assertNotEquals(sample.id, id)
        s.updateCustom(id) { it.copy(name = "Renamed", notes = "n", favorite = true) }
        assertNull(s.duplicateCustom(id))
        val again = store().customProfiles.value
        assertEquals(2, again.size)
        assertEquals("Renamed", again[0].name); assertTrue(again[0].favorite); assertEquals("n", again[0].notes)
        assertEquals("Renamed copy", again[1].name); assertFalse(again[1].favorite)
    }

    @Test fun favouritesAndRecentsPersistAndStayShort() {
        val s = store()
        s.toggleFavorite("21:0:0"); s.toggleFavorite("12:4:1"); s.toggleFavorite("21:0:0")
        repeat(12) { s.noteRecent("$it:0:0") }
        s.noteRecent("3:0:0")
        val p = store().cabinetPrefs.value
        assertEquals(listOf("12:4:1"), p.favorites)
        assertEquals("3:0:0", p.recents.first())
        assertEquals(8, p.recents.size)
        assertEquals(p.recents.size, p.recents.distinct().size)
    }
}
