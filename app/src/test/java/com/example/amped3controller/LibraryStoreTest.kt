package com.example.amped3controller

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class LibraryStoreTest {
    private val dir: File = Files.createTempDirectory("amped-store").toFile()
    private val logs = mutableListOf<String>()
    private fun store() = LibraryStore(dir) { logs += it }
    @After fun cleanup() { dir.deleteRecursively() }

    private fun fixture(name: String) = javaClass.getResource("/architect/$name")!!.readText()

    @Test fun presetsSurviveARestart() {
        val p = ArchitectPreset.parse(fixture("real.amped"))
        store().addPreset(p)
        assertEquals(listOf(p), store().presets.value)
    }

    @Test fun oneBadRecordDoesNotEmptyTheLibrary() {
        val p = ArchitectPreset.parse(fixture("real.amped"))
        store().addPreset(p)
        val file = File(dir, "presets.json")
        file.writeText(file.readText().trimEnd().removeSuffix("]") + ",{\"id\":\"x\"}]")
        assertEquals(listOf(p), store().presets.value)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("backup-invalid-presets-") })
    }

    @Test fun exportThenImportRestoresPresetsAndBackups() {
        val source = store()
        source.importPreset(ArchitectPreset.parse(fixture("real.amped")))
        source.writeBackup("backup-before-save", JSONObject().put("kind", "amp").put("slot", 1))
        val exported = source.export()

        val other = Files.createTempDirectory("amped-store-2").toFile()
        try {
            val target = LibraryStore(other) {}
            val message = target.importArchive(exported)
            assertTrue(message, message.contains("1 preset"))
            assertEquals(source.presets.value, target.presets.value)
            assertTrue(other.listFiles()!!.any { it.name.startsWith("backup-imported-") })
        } finally { other.deleteRecursively() }
    }

    @Test fun backupIsReadBackBeforeReturning() {
        val file = store().writeBackup("backup-factory", JSONObject().put("slot", 2))
        assertTrue(file.name.startsWith("backup-factory-"))
        assertEquals(2, JSONObject(file.readText()).getInt("slot"))
    }

    @Test fun firstAuditionSnapshotWinsUntilCleared() {
        val s = store()
        s.preserveAudition { JSONObject().put("name", "A").toString() }
        s.preserveAudition { JSONObject().put("name", "B").toString() }
        assertEquals("A", store().auditionName())
        s.clearAudition()
        assertFalse(s.auditionOutstanding())
    }

    @Test fun originalBackupIsSeededOnce() {
        store().seedOriginalBackup { "first" }
        store().seedOriginalBackup { "second" }
        assertEquals("first", File(dir, "backup-originale-mac.json").readText())
    }
}
