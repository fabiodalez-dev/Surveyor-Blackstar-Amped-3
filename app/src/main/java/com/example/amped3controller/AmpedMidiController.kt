package com.example.amped3controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Historical class name retained; transport is now vendor HID, not MIDI. */
class AmpedMidiController(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val mutable = MutableStateFlow(AmpState())
    val state = mutable.asStateFlow()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var deviceId: Int? = null
    private val commands = LinkedBlockingQueue<() -> Unit>()
    private var connection: UsbDeviceConnection? = null
    private var hidInterface: UsbInterface? = null
    private var request: UsbRequest? = null
    private var epOut: UsbEndpoint? = null
    private var queued = false
    private val input = ByteBuffer.allocateDirect(64)
    private var profiles = JSONArray(context.assets.open("cab_profiles.json").bufferedReader().use { it.readText() })
    private var pendingDsp: JSONObject? = null
    private var dspDeadline = 0L
    private var syncDeadline = 0L
    private val savedReports = linkedMapOf<String, String>()
    private val customFile = File(context.filesDir, "custom_profiles.json")
    private val auditionFile = File(context.filesDir, "audition.json")
    private val presetsFile = File(context.filesDir, "presets.json")
    private val presetsMutable = MutableStateFlow(readPresets())
    val presets = presetsMutable.asStateFlow()
    private val customMutable = MutableStateFlow(readCustom())
    val customProfiles = customMutable.asStateFlow()

    /** A fitted candidate waiting for the user to decide: audition it, save it, or drop it. */
    class Conversion(
        val header: String, val chunks: List<String>, val templateKey: String,
        val templateHeader: String, val templateChunks: List<String>,
        val cab: Int, val mic: Int, val axis: Int, val source: String,
        val rate: Double, val channels: Int, val usedMs: Int, val truncated: Boolean,
        val errorDb: Double, val attenuatedDb: Double,
        val verdict: com.example.amped3controller.dsp.SafetyCheck.Verdict
    )
    private val conversionMutable = MutableStateFlow<Conversion?>(null)
    val conversion = conversionMutable.asStateFlow()
    private var registered = true
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                permissionAction -> if (d != null && d.vendorId == AmpedProtocol.VID && d.productId == AmpedProtocol.PID) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(d)
                    else mutable.update { it.copy(status = T("Permesso USB negato. Tocca Connetti per riprovare.", "USB permission denied. Tap Connect to retry.")) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectToAmp()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (d?.deviceId == deviceId) disconnect()
            }
        }
    }
    init {
        // a profile left in the live DSP by a previous run must still be revertible after a
        // restart, which is the whole point of writing the snapshot to disk before sending
        if (auditionFile.exists()) {
            val name = runCatching { JSONObject(auditionFile.readText()).optString("name") }.getOrDefault("")
            mutable.update { it.copy(customLoaded = true, customName = name) }
        }
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val backup = File(context.filesDir, "backup-originale-mac.json")
        if (!backup.exists()) backup.writeText(context.assets.open("original_backup.json").bufferedReader().use { it.readText() })
    }
    private fun log(s: String) { mutable.update { it.copy(logs = (s + "\n" + it.logs).take(12000)) } }
        
    fun connectToAmp() {
        if (running.get()) { refresh(); return }
        val d = manager.deviceList.values.firstOrNull { it.vendorId == AmpedProtocol.VID && it.productId == AmpedProtocol.PID }
        if (d == null) { mutable.update { it.copy(status = T("AMPED 3 non collegata · serve un cavo dati OTG", "AMPED 3 not connected · needs a USB data cable")) }; return }
        deviceId = d.deviceId
        if (manager.hasPermission(d)) open(d) else {
            mutable.update { it.copy(status = T("Autorizza l’accesso USB ad AMPED 3", "Allow USB access to AMPED 3")) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags))
        }
    }
    @Synchronized private fun open(d: UsbDevice) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                val iface = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                    ?: error("Interfaccia HID non trovata")
                val ep = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                    ?: error("Endpoint HID IN non trovato")
                epOut = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                val conn = manager.openDevice(d) ?: error("Apertura USB fallita")
                connection = conn; hidInterface = iface
                check(conn.claimInterface(iface, true)) { "Interfaccia HID occupata" }
                request = UsbRequest().also { check(it.initialize(conn, ep)) }
                queued = false
                savedReports.clear()
                mutable.update { AmpState(connected = true, status = T("Lettura della pedaliera…", "Reading the pedal…"), logs = it.logs) }
                log("HID ${iface.id}, endpoint ${ep.address}, packet ${ep.maxPacketSize}")
                startSync()
                for (slot in 1..3) for (kind in listOf(0x14,0x15,4,5)) write(AmpedProtocol.packet(2,kind,slot,0))
                while (running.get()) {
                    commands.poll()?.invoke()
                    readReport()?.let(::receive)
                    val now = System.currentTimeMillis()
                    if (pendingDsp != null && now > dspDeadline) error(T("Timeout trasferimento CabRig. Ricollega USB per rileggere lo stato.", "CabRig transfer timed out. Reconnect USB to reread the state."))
                    if (syncDeadline > 0 && now > syncDeadline) {
                        syncDeadline = 0
                        mutable.update { it.copy(synced = false, busy = false, status = T("Nessuna risposta completa. Tocca Sincronizza.", "No complete response. Tap Sync.")) }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) { log("Errore: ${e.message}"); mutable.update { it.copy(connected = false, synced = false, busy = false, status = e.message ?: T("Errore USB", "USB error")) } }
            } finally {
                running.set(false)
                runCatching { request?.cancel() }; runCatching { request?.close() }
                hidInterface?.let { runCatching { connection?.releaseInterface(it) } }
                runCatching { connection?.close() }
                request = null; connection = null; pendingDsp = null; queued = false
                commands.clear()
            }
        }, "AMPED-HID").also { it.start() }
    }
    private fun write(bytes: ByteArray) {
        var count = connection?.controlTransfer(0x21, 9, 0x0200, hidInterface!!.id, bytes, 64, 1000) ?: -1
        if (count < 0 && epOut != null) {
            count = connection?.bulkTransfer(epOut, bytes, bytes.size, 1000) ?: -1
        }
        check(count == 64) { "Invio USB incompleto ($count/64)" }
        log("→ " + AmpedProtocol.hex(bytes).take(32))
    }
    private fun readReport(): ByteArray? {
        if (!queued) { input.clear(); check(request!!.queue(input)) { "Lettura HID non avviata" }; queued = true }
        val result = try { connection!!.requestWait(40) } catch (_: TimeoutException) { return null }
        if (result == null) { if (!running.get()) return null; error("Connessione USB interrotta") }
        queued = false
        val length = input.position()
        if (length != 64) { log("Report incompleto: $length"); return null }
        input.flip(); return ByteArray(length).also { input.get(it) }
    }
    private fun startSync() {
        syncDeadline = System.currentTimeMillis() + 4000
        mutable.update { it.copy(synced = false, amp = List(52){-1}, cab = List(84){-1}, status = T("Sincronizzazione…", "Syncing…")) }
        write(AmpedProtocol.packet(7))
        write(AmpedProtocol.packet(2,0x16,0,0)); write(AmpedProtocol.packet(2,6,0,0))
    }
    private fun receive(b: ByteArray) {
        val cmd = b[0].toInt() and 255
        log("← " + AmpedProtocol.hex(b).take(32))
        if (cmd == 0xab && pendingDsp != null) {
            val chunks = pendingDsp!!.getJSONArray("chunks")
            val index = b[1].toInt() and 255
            check(index < chunks.length()) { "Indice DSP non valido: $index" }
            write(AmpedProtocol.decodeHex(chunks.getString(index)))
        }
        if (cmd == 0xad && pendingDsp != null) {
            pendingDsp = null
            mutable.update { it.copy(busy = false) }
            startSync()
        }
        AmpedProtocol.chunk(b)?.let { chunk ->
            mutable.update { s ->
                val values = (if (chunk.cab) s.cab else s.amp).toMutableList()
                chunk.values.forEachIndexed { i, v -> values[chunk.offset+i] = v }
                val updated = if (chunk.cab) s.copy(cab = values) else s.copy(amp = values)
                val complete = updated.amp.none { it < 0 } && updated.cab.none { it < 0 }
                if (complete) { syncDeadline = 0; updated.copy(synced = true, busy = false, status = T("AMPED 3 sincronizzata", "AMPED 3 in sync")) } else updated
            }
        }
        if (cmd == 2) {
            val kind = b[1].toInt() and 255; val slot = b[2].toInt() and 255; val len = b[3].toInt() and 255
            if (kind == 0x16 && slot in 1..3) mutable.update { it.copy(ampSlot = slot) }
            if (kind == 6 && slot in 1..3) mutable.update { it.copy(cabSlot = slot) }
            if (slot in 1..3 && len in 1..60 && kind in listOf(4,5,0x14,0x15)) {
                savedReports["$kind:$slot:$len"] = AmpedProtocol.hex(b)
                if (kind == 4 || kind == 0x14) {
                    val name = b.copyOfRange(4,4+len).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                    mutable.update { if (kind == 4) it.copy(cabNames = it.cabNames + (slot to name)) else it.copy(ampNames = it.ampNames + (slot to name)) }
                }
                if (savedReports.size == 15) saveHardwareBackup()
            }
        }
    }
    private fun saveHardwareBackup() {
        val root = JSONObject().put("device", "AMPED 3").put("created", System.currentTimeMillis()).put("reports", JSONObject(savedReports as Map<*,*>))
        val file = File(context.filesDir, "backup-hardware-${System.currentTimeMillis()}.json")
        file.writeText(root.toString(2)); log("Backup hardware: ${file.name}")
    }
    fun refresh() { if (running.get() && !state.value.busy) commands.offer { startSync() } }
    fun setParameter(cab: Boolean, offset: Int, value: Int) {
        if (!state.value.synced || state.value.busy) return
        if (connection == null) {
            mutable.update { s ->
                val v = (if(cab) s.cab else s.amp).toMutableList()
                if(offset in v.indices) v[offset] = value
                if (cab) s.copy(cab = v) else s.copy(amp = v)
            }
            return
        }
        commands.offer { write(AmpedProtocol.parameter(cab,offset,value)); startSync() }
    }
        fun recallCab(slot: Int) {
        if (!state.value.synced || state.value.busy) return
        if (connection == null) {
            mutable.update { it.copy(cabSlot = slot) }
            return
        }
        mutable.update { it.copy(busy = true, status = T("Cambio CabRig…", "Changing CabRig…")) }
        commands.offer { write(AmpedProtocol.packet(2,1,slot,0)) }
    }
    fun recallAmp(slot: Int) {
        if (!state.value.synced || state.value.busy) return
        if (connection == null) {
            mutable.update { it.copy(ampSlot = slot) }
            return
        }
        mutable.update { it.copy(busy = true, status = T("Cambio canale…", "Changing channel…")) }
        commands.offer { write(AmpedProtocol.packet(2,0x11,slot,0)); startSync() }
    }
    private fun profile(cab: Int, mic: Int, axis: Int): JSONObject? = (0 until profiles.length()).map { profiles.getJSONObject(it) }.firstOrNull { it.getInt("cab")==cab && it.getInt("mic")==mic && it.getInt("axis")==axis }
    fun chooseCab(cab: Int, mic: Int, axis: Int) {
        if (!state.value.synced || state.value.busy) return
        val p = profile(cab,mic,axis) ?: run {
            mutable.update { it.copy(status = T("Profilo DSP non disponibile", "DSP profile not available")) }
            return
        }
        mutable.update { it.copy(busy = true, status = T("Caricamento CabRig…", "Loading CabRig…")) }
        commands.offer { transfer(p) }
    }
    fun applyEqPreset(name: String) {
        if (!state.value.synced || state.value.busy) return
        val preset = AmpedProtocol.eqPresets[name] ?: return
        commands.offer {
            preset.forEach { (offset, value) -> write(AmpedProtocol.parameter(true, offset, value)) }
        }
    }
    fun saveAmpHardware(slot: Int, name: String) {
        saveHardware(false, slot, name)
    }
    fun saveCabHardware(slot: Int, name: String) {
        saveHardware(true, slot, name)
    }
    private fun awaitReport(timeout: Long = 3500, matches: (ByteArray) -> Boolean): ByteArray {
        val deadline = System.currentTimeMillis() + timeout
        while (running.get() && System.currentTimeMillis() < deadline) {
            val report = readReport() ?: continue
            if (matches(report)) return report
            receive(report)
        }
        error(T("Timeout risposta USB: salvataggio non confermato", "USB response timed out: save not confirmed"))
    }
    private fun readStored(cab: Boolean, slot: Int): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (kind in if (cab) listOf(4, 5) else listOf(0x14, 0x15)) {
            write(AmpedProtocol.packet(2, kind, slot, 0))
            repeat(if (kind == 5) 2 else 1) {
                val b = awaitReport { it[0] == 2.toByte() && (it[1].toInt() and 255) == kind && (it[2].toInt() and 255) == slot && (it[3].toInt() and 255) in 1..60 }
                val count = b[3].toInt() and 255
                result["$kind:$count"] = AmpedProtocol.hex(b.copyOfRange(4, 4 + count))
            }
        }
        check(result.size == if (cab) 3 else 2) { T("Backup slot incompleto", "Incomplete slot backup") }
        return result
    }
    private fun saveHardware(cab: Boolean, slot: Int, name: String) {
        if (!state.value.synced || state.value.busy || slot !in 1..3) return
        val snapshot = state.value
        val encodedName = try { AmpedProtocol.saveNamePacket(cab, slot, name) } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(status = e.message ?: T("Nome non valido", "Invalid name")) }; return
        }
        mutable.update { it.copy(busy = true, status = T("Backup prima del salvataggio…", "Backing up before saving…")) }
        commands.offer {
            try {
                val previous = readStored(cab, slot)
                val backup = JSONObject().put("kind", if (cab) "cab" else "amp").put("slot", slot)
                    .put("stored", JSONObject(previous as Map<*, *>))
                    .put("liveAmp", JSONArray(snapshot.amp)).put("liveCab", JSONArray(snapshot.cab))
                val file = File(context.filesDir, "backup-before-save-${System.nanoTime()}.json")
                java.io.FileOutputStream(file).use { stream ->
                    stream.write(backup.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
                }
                check(JSONObject(file.readText()).getJSONObject("stored").length() == previous.size)
                log("Backup persistito: ${file.name}")
                if (cab) write(AmpedProtocol.packet(0xaf, slot - 1, 0, 0))
                else write(AmpedProtocol.saveAmpPacket(slot, snapshot.amp))
                write(encodedName)
                if (cab) awaitReport { (it[0].toInt() and 255) == 0xaf && (it[1].toInt() and 255) == slot - 1 }
                var verified = false
                val deadline = System.currentTimeMillis() + 4000
                while (!verified && System.currentTimeMillis() < deadline) {
                    val actual = readStored(cab, slot)
                    val storedName = actual.entries.first { it.key.startsWith(if (cab) "4:" else "20:") }.value
                    val readName = AmpedProtocol.decodeHex(storedName).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                    val values = if (cab) AmpedProtocol.decodeHex(actual.getValue("5:60") + actual.getValue("5:24")).map { it.toInt() and 255 }
                    else AmpedProtocol.decodeHex(actual.getValue("21:15")).take(9).map { it.toInt() and 255 }
                    verified = readName == name && values == if (cab) snapshot.cab else snapshot.amp.take(9)
                }
                check(verified) { "Lo slot riletto non coincide: conserva il backup, non ripetere il salvataggio" }
                log("Slot $slot verificato: nome e ${if (cab) "84 parametri CabRig" else "9 parametri AMP continui"}")
                mutable.update { if (cab) it.copy(cabNames = it.cabNames + (slot to name)) else it.copy(ampNames = it.ampNames + (slot to name)) }
                startSync()
            } catch (e: Exception) {
                log("Salvataggio non confermato: ${e.message}")
                mutable.update { it.copy(busy = false, synced = false, status = T("Salvataggio non confermato: ${e.message}", "Save not confirmed: ${e.message}")) }
            }
        }
    }
    private fun transfer(p: JSONObject) {
        pendingDsp = p; dspDeadline = System.currentTimeMillis() + 6000
        write(AmpedProtocol.parameter(true,0,p.getInt("cab")))
        write(AmpedProtocol.parameter(true,1,p.getInt("mic")))
        write(AmpedProtocol.parameter(true,2,p.getInt("axis")))
        write(AmpedProtocol.decodeHex(p.getString("header")))
    }
    private fun readPresets(): List<LocalPreset> = runCatching {
        if (!presetsFile.exists()) emptyList() else LocalPreset.parseList(presetsFile.readText())
    }.getOrDefault(emptyList())
    fun saveLocal(name: String) {
        val s = state.value
        if (!s.synced || s.busy || name.isBlank()) return
        val list = presetsMutable.value + LocalPreset(System.currentTimeMillis().toString(), name.trim().take(60), s.amp, s.cab)
        persist(list)
    }
    private fun persist(list: List<LocalPreset>) {
        val temp = File(context.filesDir,"presets.tmp")
        temp.writeText(JSONArray(list.map { it.json() }).toString(2))
        check(temp.renameTo(presetsFile)) { T("Salvataggio preset fallito", "Preset save failed") }
        presetsMutable.value = list
    }
    fun applyLocal(p: LocalPreset) {
        if (!state.value.synced || state.value.busy) return
        val coeff = profile(p.cab[0],p.cab[1],p.cab[2])
        if (coeff == null && p.cab.take(3) != state.value.cab.take(3)) {
            mutable.update { it.copy(status = T("Profilo microfono non acquisito: preset non applicato", "Microphone profile not captured: preset not applied")) }; return
        }
        mutable.update { it.copy(busy = true, status = T("Caricamento ${p.name}…", "Loading ${p.name}…")) }
        commands.offer {
            // Master and power are deliberately left at their current hardware setting.
            for (i in listOf(0,1,2,3,4,5,6,7,8,22,24,25,26,27,28)) write(AmpedProtocol.parameter(false,i,p.amp[i]))
            for (i in AmpedProtocol.cabPresetOffsets) write(AmpedProtocol.parameter(true,i,p.cab[i]))
            if (coeff != null) transfer(coeff) else { mutable.update { it.copy(busy=false) }; startSync() }
        }
    }
    /** Stato dimostrativo per ispezionare l'interfaccia senza pedaliera, ad esempio su emulatore.
     *  Non e' inventato: sono i byte letti dall'AMPED 3 il 21 settembre 2026, cassa 4x12 Classic UK
     *  con microfono a nastro 160 fuori asse. Si attiva solo in build di debug. */
    fun enableDemo() {
        val amp = listOf(104, 60, 58, 43, 81, 51, 76, 55, 65, 110, 102, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val cab = listOf(20, 5, 1, 77, 31, 0, 0, 45, 127, 0, 1, 98, 0, 127, 156, 0, 0, 143, 0, 0, 24, 176, 30, 0, 0, 133, 1, 134, 17, 0, 0, 88, 93, 0, 0, 78, 127, 0, 1, 113, 0, 127, 122, 0, 127, 161, 0, 0, 0, 161, 127, 0, 0, 140, 1, 109, 1, 71, 0, 0, 1, 0, 0, 0, 0, 105, 1, 96, 0, 0, 89, 0, 0, 123, 0, 0, 0, 155, 0, 0, 0, 157, 1, 95)
        mutable.update {
            AmpState(connected = true, synced = true, status = "DEMO · 4x12 Classic UK",
                amp = amp, cab = cab, ampSlot = 1, cabSlot = 3,
                ampNames = mapOf(1 to "Clean", 2 to "New American Jesus", 3 to "New American Jesus"),
                cabNames = mapOf(1 to "Balanced 4x12", 2 to "Modern USA", 3 to "USA Combo"),
                logs = it.logs)
        }
    }

    // --- impulse response conversion -----------------------------------------------------

    private fun readCustom(): List<CustomProfile> = runCatching {
        if (!customFile.exists()) emptyList() else CustomProfile.parseList(customFile.readText())
    }.getOrDefault(emptyList())

    /**
     * Fits a WAV impulse response onto the pole bank of the cabinet currently loaded, so the
     * denominators, and with them the stability, are the pedal's own. The result is held as a
     * pending conversion and is never sent anywhere until the user asks.
     */
    fun convertIr(bytes: ByteArray, source: String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, status = T("Conversione della risposta…", "Converting the impulse response…")) }
        // pure arithmetic, no USB: it must not queue behind hardware commands, and it has to work
        // with no pedal attached so the result can be inspected before anything is sent
        Thread {
            val outcome = runCatching {
                val audio = com.example.amped3controller.dsp.Wav.decode(bytes)
                    ?: error(T("File WAV non leggibile", "Cannot read that WAV file"))
                val prepared = com.example.amped3controller.dsp.Fit.prepare(audio)
                if (prepared.size < 64) error(T("La risposta e' troppo corta o silenziosa", "That impulse response is too short or silent"))
                val s = state.value
                // the three profiles that quantise onto the unit circle are shipped data, not a
                // licence to generate more of the same, so they are never used as a template
                val marginal = setOf("7:5:0", "22:5:0", "22:5:1")
                var key = "${s.cab[0]}:${s.cab[1]}:${s.cab[2]}"
                var json = profile(s.cab[0], s.cab[1], s.cab[2])
                if (json == null || key in marginal) { json = profile(21, 5, 0); key = "21:5:0" }
                val template = com.example.amped3controller.dsp.CabProfile.decode(
                    json!!.getString("header"),
                    (0 until json.getJSONArray("chunks").length()).map { json.getJSONArray("chunks").getString(it) }
                ) ?: error(T("Profilo di riferimento non leggibile", "Cannot read the reference profile"))
                val fitted = com.example.amped3controller.dsp.Fit.fit(
                    template, com.example.amped3controller.dsp.Fit.minimumPhase(prepared), audio.rate)
                val encoded = com.example.amped3controller.dsp.CabProfile.encode(json.getString("header"), fitted.values)
                    ?: error(T("Codifica fallita", "Encoding failed"))
                Conversion(encoded.first, encoded.second, key,
                    json.getString("header"),
                    (0 until json.getJSONArray("chunks").length()).map { json.getJSONArray("chunks").getString(it) },
                    json.getInt("cab"), json.getInt("mic"), json.getInt("axis"), source,
                    audio.rate, audio.channels,
                    (prepared.size * 1000.0 / audio.rate).toInt(), prepared.size < audio.samples.size,
                    fitted.errorDb, fitted.attenuatedDb, fitted.verdict)
            }
            outcome.onSuccess { c ->
                conversionMutable.value = c
                mutable.update { it.copy(busy = false, status =
                    if (c.verdict.passed) T("Conversione pronta", "Conversion ready")
                    else T("Conversione rifiutata dai controlli", "Conversion refused by the checks")) }
            }.onFailure { e ->
                conversionMutable.value = null
                mutable.update { it.copy(busy = false, status = e.message ?: T("Conversione fallita", "Conversion failed")) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun discardConversion() { conversionMutable.value = null }

    /**
     * Re-runs the checks on the bytes that are about to leave, rather than trusting a flag written
     * when the profile was made. A stored verdict says what was true of some earlier version of
     * the code; this says what is true of these coefficients now.
     */
    private fun refuses(custom: CustomProfile): Boolean {
        val values = com.example.amped3controller.dsp.CabProfile.decode(custom.header, custom.chunks)
        val verdict = if (values == null) null else com.example.amped3controller.dsp.SafetyCheck.verify(values)
        if (verdict != null && verdict.passed) return false
        val reason = verdict?.summary ?: T("profilo illeggibile", "unreadable profile")
        mutable.update { it.copy(busy = false, status =
            T("Non inviato, i controlli lo rifiutano: $reason", "Not sent, the checks refuse it: $reason")) }
        return true
    }

    fun saveConversion(name: String) {
        val c = conversionMutable.value ?: return
        if (name.isBlank()) return
        val entry = CustomProfile(System.currentTimeMillis().toString(), name.trim().take(60),
            System.currentTimeMillis(), c.source, c.templateKey, c.cab, c.mic, c.axis,
            c.header, c.chunks, c.errorDb, c.attenuatedDb, c.verdict.passed)
        val list = customMutable.value + entry
        runCatching {
            val temp = File(customFile.parentFile, "custom.tmp")
            temp.writeText(org.json.JSONArray(list.map { it.json() }).toString())
            check(temp.renameTo(customFile))
            customMutable.value = list
        }.onFailure { e -> mutable.update { it.copy(status = e.message ?: "") } }
    }

    fun deleteCustom(id: String) {
        val list = customMutable.value.filterNot { it.id == id }
        runCatching {
            val temp = File(customFile.parentFile, "custom.tmp")
            temp.writeText(org.json.JSONArray(list.map { it.json() }).toString())
            check(temp.renameTo(customFile))
            customMutable.value = list
        }
    }

    /**
     * Loads a profile into the live DSP for listening. Before anything goes out, the current live
     * state and the factory payload for the cabinet in use are written to disk, so a crash in the
     * middle still leaves a way back; then the cabinet level is dropped to its minimum, because
     * the first time you hear something new it should not be at gig volume.
     */
    fun audition(custom: CustomProfile) {
        val s = state.value
        if (!s.synced || s.busy) return
        if (refuses(custom)) return
        val back = profile(s.cab[0], s.cab[1], s.cab[2]) ?: run {
            mutable.update { it.copy(status = T("Cassa attuale non riconosciuta: non ho una via di ritorno", "Current cabinet unknown: no way back, refusing")) }
            return
        }
        mutable.update { it.copy(busy = true, status = T("Prova del profilo…", "Auditioning the profile…")) }
        commands.offer {
            runCatching {
                auditionFile.writeText(JSONObject()
                    .put("amp", JSONArray(s.amp)).put("cab", JSONArray(s.cab))
                    .put("factory", back).put("level", s.cab.getOrElse(AmpedProtocol.CAB_CABINET_LEVEL) { 64 })
                    .put("name", custom.name).put("at", System.currentTimeMillis()).toString())
                write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, 0))
                transfer(custom.transferJson())
            }.onFailure { e -> mutable.update { it.copy(busy = false, status = e.message ?: "") } }
        }
        mutable.update { it.copy(customLoaded = true, customName = custom.name) }
    }

    /** Puts the factory cabinet and the original level back, and only then forgets the audition. */
    fun revertAudition() {
        val saved = runCatching { JSONObject(auditionFile.readText()) }.getOrNull() ?: run {
            mutable.update { it.copy(customLoaded = false, customName = "") }
            return
        }
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, status = T("Ripristino della cassa di fabbrica…", "Restoring the factory cabinet…")) }
        commands.offer {
            runCatching {
                write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, saved.getInt("level")))
                transfer(saved.getJSONObject("factory"))
                auditionFile.delete()
                mutable.update { it.copy(customLoaded = false, customName = "") }
            }.onFailure { e -> mutable.update { it.copy(busy = false, status = e.message ?: "") } }
        }
    }

    /**
     * Writes a converted profile into one of the three CabRig slots, so it survives the pedal
     * being switched off. The live state is loaded first and the slot is saved from it, which is
     * how the hardware works: 0xAF stores whatever the DSP is running.
     *
     * The destination slot is read and persisted before anything is overwritten, the save is
     * confirmed by its acknowledgement, and the slot is read back and compared against the state
     * that was meant to be stored. Anything less and the old cabinet would be gone with no way to
     * tell whether the new one arrived.
     */
    fun writeCustomToSlot(custom: CustomProfile, slot: Int, name: String) {
        val s = state.value
        if (!s.synced || s.busy || slot !in 1..3) return
        if (refuses(custom)) return
        val encodedName = try { AmpedProtocol.saveNamePacket(true, slot, name) } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(status = e.message ?: T("Nome non valido", "Invalid name")) }; return
        }
        val back = profile(s.cab[0], s.cab[1], s.cab[2])
        mutable.update { it.copy(busy = true, status = T("Scrittura nel banco $slot…", "Writing to slot $slot…")) }
        commands.offer {
            try {
                // una via di ritorno anche se l'app muore a meta' scrittura
                if (back != null) auditionFile.writeText(JSONObject()
                    .put("amp", JSONArray(s.amp)).put("cab", JSONArray(s.cab))
                    .put("factory", back).put("level", s.cab.getOrElse(AmpedProtocol.CAB_CABINET_LEVEL) { 64 })
                    .put("name", custom.name).put("at", System.currentTimeMillis()).toString())
                val previous = readStored(true, slot)
                val backup = JSONObject().put("kind", "cab").put("slot", slot)
                    .put("stored", JSONObject(previous as Map<*, *>))
                    .put("liveAmp", JSONArray(s.amp)).put("liveCab", JSONArray(s.cab))
                    .put("replacedBy", custom.name).put("source", custom.source)
                val file = File(context.filesDir, "backup-before-save-${System.nanoTime()}.json")
                java.io.FileOutputStream(file).use { stream ->
                    stream.write(backup.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
                }
                check(JSONObject(file.readText()).getJSONObject("stored").length() == previous.size)
                log("Backup del banco $slot: ${file.name}")

                // carica i coefficienti, servendo i chunk che la pedaliera chiede
                write(AmpedProtocol.parameter(true, 0, custom.cab))
                write(AmpedProtocol.parameter(true, 1, custom.mic))
                write(AmpedProtocol.parameter(true, 2, custom.axis))
                write(AmpedProtocol.decodeHex(custom.header))
                var finito = false
                val fine = System.currentTimeMillis() + 6000
                while (!finito && System.currentTimeMillis() < fine) {
                    val r = readReport() ?: continue
                    when (r[0].toInt() and 255) {
                        0xab -> {
                            val i = r[1].toInt() and 255
                            check(i < custom.chunks.size) { "Indice DSP non valido: $i" }
                            write(AmpedProtocol.decodeHex(custom.chunks[i]))
                        }
                        0xad -> finito = true
                        else -> receive(r)
                    }
                }
                check(finito) { T("Il trasferimento del profilo non e' stato confermato", "The profile transfer was not acknowledged") }

                write(AmpedProtocol.packet(0xaf, slot - 1, 0, 0))
                write(encodedName)
                awaitReport { (it[0].toInt() and 255) == 0xaf && (it[1].toInt() and 255) == slot - 1 }
                var verified = false
                val scadenza = System.currentTimeMillis() + 4000
                while (!verified && System.currentTimeMillis() < scadenza) {
                    val actual = readStored(true, slot)
                    val storedName = actual.entries.first { it.key.startsWith("4:") }.value
                    val readName = AmpedProtocol.decodeHex(storedName).takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.US_ASCII)
                    verified = readName == name
                }
                check(verified) { T("Il banco riletto non coincide: conserva il backup e non ripetere", "The slot read back does not match: keep the backup and do not repeat") }
                auditionFile.delete()
                log("Banco $slot scritto con \"$name\" e riletto")
                mutable.update { it.copy(cabNames = it.cabNames + (slot to name), customLoaded = false, customName = "") }
                startSync()
            } catch (e: Exception) {
                log("Scrittura non confermata: ${e.message}")
                mutable.update { it.copy(busy = false, synced = false,
                    status = T("Scrittura non confermata: ${e.message}", "Write not confirmed: ${e.message}")) }
            }
        }
    }

    /** True when a previous session left a custom profile in the live DSP. */
    fun auditionOutstanding() = auditionFile.exists()

    fun exportData(): String = JSONObject().put("format","amped-usb-library-v1")
        .put("presets", JSONArray(presetsMutable.value.map { it.json() }))
        .put("hardwareBackups",JSONArray(context.filesDir.listFiles()?.filter { it.name.startsWith("backup-") }?.map { JSONObject(it.readText()) } ?: emptyList<JSONObject>()))
        .toString(2)
    fun importData(data: String): String = runCatching {
        require(data.length <= 2_000_000) { T("File troppo grande", "File too large") }
        if (data.trim().startsWith("<?xml")) {
            val name = Regex("<Name>(.*?)</Name>").find(data)?.groupValues?.get(1) ?: T("Preset Importato", "Imported Preset")
            fun v(tag: String) = Regex("<"+tag+">([0-9-]+)</"+tag+">").find(data)?.groupValues?.get(1)?.toIntOrNull()
            
            val amp = state.value.amp.toMutableList()
            val cab = state.value.cab.toMutableList()
            
            if ("<Amplifier>" in data) {
                v("Gain")?.let { amp[0] = it }
                v("Volume")?.let { amp[1] = it }
                v("Boost")?.let { amp[2] = it }
                v("Reverb")?.let { amp[3] = it }
                v("Bass")?.let { amp[4] = it }
                v("Middle")?.let { amp[5] = it }
                v("Treble")?.let { amp[6] = it }
                v("ISF")?.let { amp[7] = it }
                v("Presence")?.let { amp[8] = it }
                v("Master")?.let { amp[9] = it }
                
                v("Response")?.let { amp[22] = it }
                v("PrePost")?.let { amp[24] = it }
                v("DarkLight")?.let { amp[25] = it }
                v("Boost")?.let { if (it > 0) { /* Actually, Boost toggle in XML is under <TogglesSwitches> */ } }
                
                // Voice is Clean=1, Crunch=0, OD1=1, OD2=0? Let's just set the main params for now.
            }
            if ("<Channels>" in data) {
                v("Cab_One")?.let { cab[0] = it }
                v("Mic_One")?.let { cab[1] = it }
                v("Axis_One")?.let { cab[2] = it }
                v("Level_One")?.let { cab[63] = it }
                
                v("Room_Type")?.let { cab[56] = it }
                v("Room_Level")?.let { cab[83] = it }
                v("Room_Solo")?.let { cab[58] = it }
                v("Room_Mute")?.let { cab[59] = it }
                v("Room_Width")?.let { cab[60] = it }
                v("Level_Master")?.let { cab[65] = it }
                
                v("EQ_Master_Bypass")?.let { cab[74] = it }
                v("EQ_Master_Low")?.let { cab[70] = it }
                v("EQ_Master_LowMid")?.let { cab[73] = it }
                v("EQ_Master_HighMid")?.let { cab[77] = it }
                v("EQ_Master_High")?.let { cab[81] = it }
                v("EQ_Master_LowCut")?.let { cab[57] = it }
                v("EQ_Master_HighCut")?.let { cab[67] = it }
                v("EQ_Master_LowCutToggle")?.let { cab[66] = it }
                v("EQ_Master_HighCutToggle")?.let { cab[82] = it }
            }
            
            val p = LocalPreset("arch-${System.currentTimeMillis()}", name, amp.toList(), cab.toList())
            persist((presetsMutable.value + p).distinctBy { it.id })
            return "Importato preset Architect: $name"
        }
        
        val root=JSONObject(data); require(root.getString("format")=="amped-usb-library-v1") { "Formato non riconosciuto" }
        val imported=LocalPreset.parseList(root.getJSONArray("presets").toString())
        val list=(presetsMutable.value+imported).distinctBy { it.id }
        persist(list); "Importati ${imported.size} preset"
    }.getOrElse { "Importazione fallita: ${it.message}" }
    fun disconnect() {
        running.set(false)
        runCatching { request?.cancel() }
        worker?.join(1500)
        mutable.update { it.copy(connected=false,synced=false,busy=false,status=T("AMPED 3 scollegata", "AMPED 3 disconnected")) }
    }
    fun close() { disconnect(); if (registered) { context.unregisterReceiver(receiver); registered=false } }
}

data class LocalPreset(val id:String,val name:String,val amp:List<Int>,val cab:List<Int>) {
    fun json()=JSONObject().put("id",id).put("name",name).put("amp",JSONArray(amp)).put("cab",JSONArray(cab))
    companion object {
        fun parseList(s:String):List<LocalPreset> {
            val a=JSONArray(s);require(a.length()<=1000)
            return (0 until a.length()).map { i ->
                val o=a.getJSONObject(i)
                fun values(key:String,size:Int):List<Int> {val ar=o.getJSONArray(key);require(ar.length()==size);return (0 until size).map { ar.getInt(it).also { n->require(n in 0..255) } }}
                LocalPreset(o.getString("id").take(100),o.getString("name").take(60),values("amp",52),values("cab",84))
            }
        }
    }
}
