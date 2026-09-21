package com.example.amped3controller

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import com.example.amped3controller.dsp.CabProfile

/** One owner for USB writes, even while parameter reports arrive. */
class OperationGate {
    private val held = AtomicBoolean(false)
    fun begin() = held.compareAndSet(false, true)
    fun end() { held.set(false) }
    val active get() = held.get()
}

object Recovery {
    fun recoverPresets(text: String): Pair<List<LocalPreset>, Int> {
        val array = org.json.JSONArray(text)
        val good = mutableListOf<LocalPreset>()
        var rejected = 0
        for (i in 0 until array.length()) {
            runCatching { LocalPreset.parseList(org.json.JSONArray().put(array.get(i)).toString()).single() }
                .onSuccess { good.add(it) }.onFailure { rejected++ }
        }
        return good to rejected
    }
    /** First snapshot wins until an acknowledged restore; repeated auditions must not replace it. */
    fun preserve(file: File, text: () -> String) {
        if (!file.exists()) write(file, text())
    }

    fun cabPreset(id: String, data: JSONObject): LocalPreset? = runCatching {
        val dsp = data.optJSONObject("dsp") ?: data.optJSONObject("factory") ?: return null
        validateProfile(dsp)
        val stored = data.optJSONObject("stored")
        val cab = if (stored != null) storedCab(stored.keys().asSequence().associateWith { stored.getString(it) })
            else data.getJSONArray("cab").let { a -> require(a.length() == 84); (0 until 84).map { a.getInt(it) } }
        require(cab.all { it in 0..255 })
        require(cab.take(3) == listOf(dsp.getInt("cab"),dsp.getInt("mic"),dsp.getInt("axis")))
        LocalPreset(id, "Recupero CAB ${data.optInt("slot", 0)} · $id", List(52){0}, cab, dsp=dsp.toString(), scope="cab")
    }.getOrNull()

    fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(file)) { "Cannot persist ${file.name}" }
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
            require(out.size() <= limit) { "File troppo grande / File too large" }
        }
    }

    fun validateProfile(p: JSONObject): JSONObject {
        require(p.getInt("cab") in 0..23 && p.getInt("mic") in 0..5 && p.getInt("axis") in 0..1)
        val chunks = p.getJSONArray("chunks")
        require(CabProfile.decode(p.getString("header"), (0 until chunks.length()).map { chunks.getString(it) }) != null) { "Invalid DSP payload" }
        return p
    }

    fun storedCab(records: Map<String, String>): List<Int> {
        val bytes = AmpedProtocol.decodeHex(records.getValue("5:60") + records.getValue("5:24"))
        require(bytes.size == 84)
        return bytes.map { it.toInt() and 255 }
    }

    fun verifyCab(records: Map<String, String>, name: String, expected: List<Int>): Boolean {
        val n = records.entries.first { it.key.startsWith("4:") }.value
        val actualName = AmpedProtocol.decodeHex(n).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        return actualName == name && storedCab(records) == expected
    }
}
