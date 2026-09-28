package com.example.amped3controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the app keeps on the phone: local presets, converted profiles, the audition recovery
 * snapshot and the slot backups. No USB here; every write goes through [Recovery.write], which is
 * atomic and fsynced, so a crash leaves either the old file or the new one, never half of each.
 */
class LibraryStore(private val dir: File, private val log: (String) -> Unit) {
    private val customFile = File(dir, "custom_profiles.json")
    private val auditionFile = File(dir, "audition.json")
    private val presetsFile = File(dir, "presets.json")

    private val presetsMutable = MutableStateFlow(readPresets())
    val presets = presetsMutable.asStateFlow()
    private val customMutable = MutableStateFlow(readCustom())
    val customProfiles = customMutable.asStateFlow()
    private val recoveryMutable = MutableStateFlow(readRecoveries())
    val recoveries = recoveryMutable.asStateFlow()

    /** The state captured on the Mac before the first ever write, shipped with the app. */
    fun seedOriginalBackup(text: () -> String) {
        val backup = File(dir, "backup-originale-mac.json")
        if (!backup.exists()) backup.writeText(text())
    }

    private fun readRecoveries(): List<LocalPreset> = dir.listFiles().orEmpty()
        .filter { (it.name.startsWith("backup-") || it.name == auditionFile.name) && !it.name.endsWith(".tmp") }
        .mapNotNull { file -> runCatching { Recovery.cabPreset(file.name, JSONObject(file.readText())) }.getOrNull() }
        .sortedByDescending { it.id }

    fun refreshRecoveries() { recoveryMutable.value = readRecoveries() }

    private fun readPresets(): List<LocalPreset> {
        if (!presetsFile.exists()) return emptyList()
        return runCatching {
            val (good, rejected) = Recovery.recoverPresets(presetsFile.readText())
            if (rejected > 0) {
                presetsFile.copyTo(File(dir, "backup-invalid-presets-${System.nanoTime()}.txt"))
                log("Recuperati ${good.size} preset validi; $rejected record non validi conservati nel backup")
            }
            good
        }.getOrElse {
            presetsFile.copyTo(File(dir, "backup-invalid-presets-${System.nanoTime()}.txt"))
            log("Libreria non valida conservata per recupero: ${it.message}")
            emptyList()
        }
    }

    private fun readCustom(): List<CustomProfile> = runCatching {
        if (!customFile.exists()) emptyList() else CustomProfile.parseList(customFile.readText())
    }.getOrDefault(emptyList())

    @Synchronized fun persistPresets(list: List<LocalPreset>) {
        val json = JSONArray(list.map { it.json() }).toString(2)
        LocalPreset.parseList(json)
        Recovery.write(presetsFile, json)
        presetsMutable.value = list
    }

    @Synchronized fun addPreset(p: LocalPreset) = persistPresets(presetsMutable.value + p)

    @Synchronized private fun persistCustom(list: List<CustomProfile>) {
        Recovery.write(customFile, JSONArray(list.map { it.json() }).toString())
        customMutable.value = list
    }

    /** Null on success, otherwise the reason it was not stored. */
    @Synchronized fun addCustom(entry: CustomProfile): String? {
        if (customMutable.value.size >= 200) return "Limite 200 profili raggiunto"
        return runCatching { persistCustom(customMutable.value + entry) }.exceptionOrNull()?.let { it.message ?: "" }
    }

    @Synchronized fun deleteCustom(id: String) {
        runCatching { persistCustom(customMutable.value.filterNot { it.id == id }) }
    }

    /** Writes a backup and reads it back, so a failed disk write stops the caller before the pedal is touched. */
    fun writeBackup(prefix: String, root: JSONObject): File {
        val file = File(dir, "$prefix-${System.nanoTime()}.json")
        val text = root.toString(2)
        Recovery.write(file, text)
        check(file.readText() == text) { "Backup non riletto correttamente: ${file.name}" }
        log("Backup persistito: ${file.name}")
        return file
    }

    fun writeHardwareSnapshot(reports: Map<String, String>) {
        val root = JSONObject().put("device", "AMPED 3").put("created", System.currentTimeMillis()).put("reports", JSONObject(reports as Map<*, *>))
        val file = File(dir, "backup-hardware-${System.currentTimeMillis()}.json")
        Recovery.write(file, root.toString(2)); log("Backup hardware: ${file.name}")
    }

    // --- audition recovery ---------------------------------------------------------------

    fun auditionOutstanding() = auditionFile.exists()
    fun auditionName(): String = runCatching { JSONObject(auditionFile.readText()).optString("name") }.getOrDefault("")
    fun readAudition() = JSONObject(auditionFile.readText())
    /** First snapshot wins until an acknowledged restore; repeated auditions must not replace it. */
    fun preserveAudition(text: () -> String) = Recovery.preserve(auditionFile, text)
    fun clearAudition() = check(auditionFile.delete()) { "Impossibile eliminare il recupero confermato" }

    // --- export and import ---------------------------------------------------------------

    @Synchronized fun export(): String {
        val backups = JSONArray()
        dir.listFiles()?.filter { it.name.startsWith("backup-") && !it.name.endsWith(".tmp") }?.forEach {
            backups.put(JSONObject().put("name", it.name).put("contents", it.readText()))
        }
        val root = JSONObject().put("format", "amped-usb-library-v2")
            .put("presets", JSONArray(presetsMutable.value.map { it.json() }))
            .put("customProfiles", JSONArray(customMutable.value.map { it.json() }))
            .put("hardwareBackups", backups)
        if (auditionFile.exists()) root.put("audition", JSONObject(auditionFile.readText()))
        return root.toString(2)
    }

    @Synchronized fun importPreset(p: LocalPreset) = persistPresets((presetsMutable.value + p).distinctBy { it.id })

    @Synchronized fun importArchive(data: String): String {
        val archive = LibraryArchive.parse(data)
        val list = (presetsMutable.value + archive.presets).distinctBy { it.id }
        val customs = (customMutable.value + archive.custom).distinctBy { it.id }
        require(list.size <= 1000 && customs.size <= 200) { "Library capacity exceeded" }
        // First persist the original import. A failure later never destroys its recovery data.
        Recovery.write(File(dir, "library-import-source-${System.nanoTime()}.json"), data)
        archive.backups.forEach { Recovery.write(File(dir, "backup-imported-${System.nanoTime()}.txt"), it) }
        archive.audition?.let {
            // An imported recovery may belong to another device: never activate it automatically.
            Recovery.write(File(dir, "backup-imported-audition-${System.nanoTime()}.json"), it.toString())
        }
        persistCustom(customs)
        persistPresets(list)
        refreshRecoveries()
        return "Importati ${archive.presets.size} preset, ${archive.custom.size} profili custom e ${archive.backups.size} backup; nessuna scrittura USB"
    }
}
