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
    private val operation = OperationGate()
    private var liveProfile: JSONObject? = null
    private val slotProfiles = mutableMapOf<Int, JSONObject>() // valid only in this uninterrupted USB session
    private var syncDeadline = 0L
    private val savedReports = linkedMapOf<String, String>()
    private val customFile = File(context.filesDir, "custom_profiles.json")
    private val auditionFile = File(context.filesDir, "audition.json")
    private val presetsFile = File(context.filesDir, "presets.json")
    private val presetsMutable = MutableStateFlow(readPresets())
    val presets = presetsMutable.asStateFlow()
    private val customMutable = MutableStateFlow(readCustom())
    val customProfiles = customMutable.asStateFlow()
    private val recoveryMutable = MutableStateFlow(readRecoveries())
    val recoveries = recoveryMutable.asStateFlow()
    private fun readRecoveries(): List<LocalPreset> = context.filesDir.listFiles().orEmpty()
        .filter { (it.name.startsWith("backup-") || it.name == "audition.json") && !it.name.endsWith(".tmp") }
        .mapNotNull { file -> runCatching { Recovery.cabPreset(file.name, JSONObject(file.readText())) }.getOrNull() }
        .sortedByDescending { it.id }

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
        if (worker?.isAlive == true || !running.compareAndSet(false, true)) return
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
                liveProfile = null
                slotProfiles.clear()
                mutable.update { AmpState(connected = true, status = T("Lettura della pedaliera…", "Reading the pedal…"), logs = it.logs, customLoaded = auditionFile.exists(), customName = it.customName) }
                log("HID ${iface.id}, endpoint ${ep.address}, packet ${ep.maxPacketSize}")
                startSync()
                for (slot in 1..3) for (kind in listOf(0x14,0x15,4,5)) write(AmpedProtocol.packet(2,kind,slot,0))
                while (running.get()) {
                    commands.poll()?.invoke()
                    readReport()?.let(::receive)
                    val now = System.currentTimeMillis()
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
                request = null; connection = null; liveProfile = null; queued = false; operation.end()
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
        AmpedProtocol.chunk(b)?.let { chunk ->
            mutable.update { s ->
                val values = (if (chunk.cab) s.cab else s.amp).toMutableList()
                chunk.values.forEachIndexed { i, v -> values[chunk.offset+i] = v }
                val updated = if (chunk.cab) s.copy(cab = values) else s.copy(amp = values)
                val complete = updated.amp.none { it < 0 } && updated.cab.none { it < 0 }
                if (complete) { syncDeadline = 0; updated.copy(synced = true, status = T("AMPED 3 sincronizzata", "AMPED 3 in sync")) } else updated
            }
        }
        if (cmd == 2) {
            val kind = b[1].toInt() and 255; val slot = b[2].toInt() and 255; val len = b[3].toInt() and 255
            if (kind == 0x16 && slot in 1..3) mutable.update { it.copy(ampSlot = slot) }
            if (kind == 6 && slot in 1..3) {
                if (state.value.cabSlot != slot) liveProfile = slotProfiles[slot]
                mutable.update { it.copy(cabSlot = slot) }
            }
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
        Recovery.write(file, root.toString(2)); log("Backup hardware: ${file.name}")
    }
    /** Reservation happens on the caller thread; unsolicited reports cannot release it. */
    private fun enqueue(label: String, requireSync: Boolean = true, action: () -> Unit) {
        if (!running.get() || (requireSync && !state.value.synced) || !operation.begin()) return
        mutable.update { it.copy(busy = true, status = label) }
        commands.offer {
            try {
                action()
                syncNow()
            } catch (e: Exception) {
                log("Operazione non confermata: ${e.message}")
                mutable.update { it.copy(synced = false, status = e.message ?: "USB error") }
            } finally {
                recoveryMutable.value = readRecoveries()
                operation.end()
                mutable.update { it.copy(busy = false) }
            }
        }
    }
    private fun syncNow() {
        startSync()
        val end = System.currentTimeMillis() + 4000
        while (running.get() && !state.value.synced && System.currentTimeMillis() < end) {
            readReport()?.let(::receive)
        }
        check(running.get() && state.value.synced) { "Sincronizzazione non confermata" }
    }
    fun refresh() = enqueue("Sincronizzazione…", false) { }
    fun setParameter(cab: Boolean, offset: Int, value: Int) = enqueue("Aggiornamento…") {
        write(AmpedProtocol.parameter(cab, offset, value))
    }
    fun recallCab(slot: Int) = enqueue("Cambio CabRig…") {
        require(slot in 1..3)
        write(AmpedProtocol.packet(2, 1, slot, 0))
        liveProfile = slotProfiles[slot]
    }
    fun recallAmp(slot: Int) = enqueue("Cambio canale…") {
        require(slot in 1..3)
        write(AmpedProtocol.packet(2, 0x11, slot, 0))
    }
    private fun profile(cab: Int, mic: Int, axis: Int): JSONObject? = (0 until profiles.length()).map { profiles.getJSONObject(it) }.firstOrNull { it.getInt("cab")==cab && it.getInt("mic")==mic && it.getInt("axis")==axis }
    fun chooseCab(cab: Int, mic: Int, axis: Int) {
        val p = profile(cab,mic,axis) ?: return
        enqueue("Caricamento CabRig…") { transfer(p) }
    }
    fun applyEqPreset(name: String) {
        val preset = AmpedProtocol.eqPresets[name] ?: return
        enqueue("Equalizzazione…") {
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
        enqueue("Backup prima del salvataggio…") {
            run {
                val previous = readStored(cab, slot)
                val oldDsp = if (cab) requireSlotDsp(slot, previous) else null
                if (cab) check(liveProfile != null) { "Applica prima un profilo DSP noto" }
                val backup = JSONObject().put("kind", if (cab) "cab" else "amp").put("slot", slot)
                    .put("stored", JSONObject(previous as Map<*, *>)).put("dsp", oldDsp)
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
                if (cab) slotProfiles[slot] = liveProfile!!
            }
        }
    }
    /** Unknown slots cannot be recovered from their selector bytes alone. */
    /** User explicitly identifies a factory slot. This reads only; custom slots must not use it. */
    fun confirmFactorySlot(slot: Int) = enqueue("Backup della cassa originale nel banco $slot…") {
        require(slot in 1..3)
        val records = readStored(true, slot)
        val cab = Recovery.storedCab(records)
        val dsp = profile(cab[0], cab[1], cab[2]) ?: error("Profilo originale sconosciuto")
        val backup = JSONObject().put("kind", "cab").put("slot", slot)
            .put("stored", JSONObject(records as Map<*, *>)).put("dsp", dsp)
            .put("provenance", "factory-confirmed-by-user")
        Recovery.write(File(context.filesDir, "backup-factory-${System.nanoTime()}.json"), backup.toString(2))
        slotProfiles[slot] = dsp
        log("Backup parametri e profilo originale banco $slot salvato; nessuna scrittura USB")
    }
    fun confirmCustomSlot(custom: CustomProfile, slot: Int) = enqueue("Backup del custom già presente nel banco $slot…") {
        require(slot in 1..3)
        val records = readStored(true, slot)
        val values = Recovery.storedCab(records)
        check(values.take(3) == listOf(custom.cab, custom.mic, custom.axis)) { "Il template del banco non coincide con quello del custom" }
        val dsp = Recovery.validateProfile(custom.transferJson())
        Recovery.write(File(context.filesDir, "backup-custom-confirmed-${System.nanoTime()}.json"),
            JSONObject().put("kind", "cab").put("slot", slot).put("stored", JSONObject(records as Map<*, *>))
                .put("dsp", dsp).put("provenance", "custom-identity-confirmed-by-user").toString(2))
        slotProfiles[slot] = dsp
    }
    private fun requireSlotDsp(slot: Int, stored: Map<String, String>): JSONObject {
        val known = slotProfiles[slot]
            ?: error("Backup DSP dello slot assente: impossibile sovrascrivere garantendo il ripristino. Se è una cassa originale, confermala nella pagina Preset; per un custom occorre identificarne il profilo esatto.")
        val values = Recovery.storedCab(stored)
        check(values.take(3) == listOf(known.getInt("cab"), known.getInt("mic"), known.getInt("axis"))) { "Slot modificato esternamente: backup DSP non valido" }
        return known
    }
    private fun transfer(p: JSONObject) {
        Recovery.validateProfile(p)
        liveProfile = null
        write(AmpedProtocol.parameter(true,0,p.getInt("cab")))
        write(AmpedProtocol.parameter(true,1,p.getInt("mic")))
        write(AmpedProtocol.parameter(true,2,p.getInt("axis")))
        write(AmpedProtocol.decodeHex(p.getString("header")))
        val chunks = p.getJSONArray("chunks")
        DspTransfer.run((0 until chunks.length()).map { AmpedProtocol.decodeHex(chunks.getString(it)) },
            ::write, ::readReport, ::receive, { running.get() })
        liveProfile = JSONObject(p.toString())
    }
    private fun readPresets(): List<LocalPreset> {
        if (!presetsFile.exists()) return emptyList()
        return runCatching {
            val (good, rejected) = Recovery.recoverPresets(presetsFile.readText())
            if (rejected > 0) {
                presetsFile.copyTo(File(context.filesDir, "backup-invalid-presets-${System.nanoTime()}.txt"))
                log("Recuperati ${good.size} preset validi; $rejected record non validi conservati nel backup")
            }
            good
        }.getOrElse {
            presetsFile.copyTo(File(context.filesDir, "backup-invalid-presets-${System.nanoTime()}.txt"))
            log("Libreria non valida conservata per recupero: ${it.message}")
            emptyList()
        }
    }
    @Synchronized fun saveLocal(name: String) {
        val s = state.value
        if (!s.synced || s.busy || name.isBlank()) return
        val dsp = liveProfile?.takeIf { listOf(it.getInt("cab"),it.getInt("mic"),it.getInt("axis")) == s.cab.take(3) } ?: run {
            mutable.update { it.copy(status = "Profilo DSP attuale sconosciuto: applica una cassa o un custom prima di salvare il preset locale") }; return
        }
        val list = presetsMutable.value + LocalPreset(System.currentTimeMillis().toString(), name.trim().take(60), s.amp, s.cab, s.ampSlot, dsp.toString())
        runCatching { persist(list) }.onFailure { e -> mutable.update { it.copy(status = "Salvataggio locale fallito: ${e.message}") } }
    }
    @Synchronized private fun persist(list: List<LocalPreset>) {
        val json = JSONArray(list.map { it.json() }).toString(2)
        LocalPreset.parseList(json)
        Recovery.write(presetsFile, json)
        presetsMutable.value = list
    }
    fun applyLocal(p: LocalPreset) {
        val coeff = p.dsp?.let { JSONObject(it) } ?: profile(p.cab[0],p.cab[1],p.cab[2]) ?: return
        enqueue("Caricamento ${p.name}…") {
            val originalAmp = state.value.amp
            if (p.scope != "cab" && p.ampSlot in 1..3) {
                write(AmpedProtocol.packet(2,0x11,p.ampSlot,0))
                syncNow()
                write(AmpedProtocol.parameter(false,9,originalAmp[9]))
                write(AmpedProtocol.parameter(false,AmpedProtocol.AMP_POWER,originalAmp[AmpedProtocol.AMP_POWER]))
            }
            if (p.scope != "cab") {
            for (i in listOf(0,1,2,3,4,5,6,7,8,22,24,25,26,27,28,33)) write(AmpedProtocol.parameter(false,i,p.amp[i]))
            val status = (state.value.amp[AmpedProtocol.AMP_STATUS] and AmpedProtocol.AMP_BOOST_BIT.inv()) or (p.amp[AmpedProtocol.AMP_STATUS] and AmpedProtocol.AMP_BOOST_BIT)
            write(AmpedProtocol.parameter(false,AmpedProtocol.AMP_STATUS,status))
            }
            if (p.scope != "amp") {
                transfer(coeff)
                for (i in AmpedProtocol.cabPresetOffsets) write(AmpedProtocol.parameter(true,i,p.cab[i]))
            }
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
        if (!operation.begin()) return
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
            operation.end()
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

    @Synchronized fun saveConversion(name: String) {
        val c = conversionMutable.value ?: return
        if (name.isBlank()) return
        val entry = CustomProfile(System.currentTimeMillis().toString(), name.trim().take(60),
            System.currentTimeMillis(), c.source, c.templateKey, c.cab, c.mic, c.axis,
            c.header, c.chunks, c.errorDb, c.attenuatedDb, c.verdict.passed)
        if (customMutable.value.size >= 200) { mutable.update { it.copy(status = "Limite 200 profili raggiunto") }; return }
        val list = customMutable.value + entry
        runCatching {
            Recovery.write(customFile, JSONArray(list.map { it.json() }).toString())
            customMutable.value = list
        }.onFailure { e -> mutable.update { it.copy(status = e.message ?: "") } }
    }

    @Synchronized fun deleteCustom(id: String) {
        val list = customMutable.value.filterNot { it.id == id }
        runCatching {
            Recovery.write(customFile, JSONArray(list.map { it.json() }).toString())
            customMutable.value = list
        }
    }

    /**
     * Loads a profile into the live DSP for listening. Before anything goes out, the current live
     * state and the factory payload for the cabinet in use are written to disk, so a crash in the
     * middle still leaves a way back; then the cabinet level is dropped to its minimum, because
     * the first time you hear something new it should not be at gig volume.
     */
    private fun preserveAudition(s: AmpState, name: String) {
        if (auditionFile.exists()) return // keep the first pre-audition state across A/B tests
        val back = liveProfile?.takeIf { listOf(it.getInt("cab"),it.getInt("mic"),it.getInt("axis")) == s.cab.take(3) } ?: error("DSP attuale sconosciuto: applica prima una cassa nota; nessuna prova inviata")
        Recovery.preserve(auditionFile) { JSONObject().put("cab", JSONArray(s.cab))
            .put("factory", back).put("level", s.cab[AmpedProtocol.CAB_CABINET_LEVEL])
            .put("name", name).put("at", System.currentTimeMillis()).toString() }
        mutable.update { it.copy(customLoaded = true, customName = name) }
    }
    fun audition(custom: CustomProfile) {
        if (!state.value.synced || state.value.busy || refuses(custom)) return
        enqueue("Prova del profilo…") {
            preserveAudition(state.value, custom.name)
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, 0))
            transfer(custom.transferJson())
            mutable.update { it.copy(customLoaded = true, customName = custom.name) }
        }
    }
    fun revertAudition() {
        enqueue("Ripristino del profilo precedente…") {
            val saved = JSONObject(auditionFile.readText())
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, 0))
            transfer(saved.getJSONObject("factory"))
            val old = saved.getJSONArray("cab")
            for (i in AmpedProtocol.cabPresetOffsets) if (i != AmpedProtocol.CAB_CABINET_LEVEL)
                write(AmpedProtocol.parameter(true, i, old.getInt(i)))
            syncNow()
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, saved.getInt("level")))
            syncNow()
            check(state.value.cab[AmpedProtocol.CAB_CABINET_LEVEL] == saved.getInt("level"))
            check(auditionFile.delete()) { "Impossibile eliminare il recupero confermato" }
            mutable.update { it.copy(customLoaded = false, customName = "") }
        }
    }
    fun writeCustomToSlot(custom: CustomProfile, slot: Int, name: String) {
        if (!state.value.synced || state.value.busy || slot !in 1..3 || refuses(custom)) return
        val encodedName = try { AmpedProtocol.saveNamePacket(true, slot, name) } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(status = e.message ?: "Nome non valido") }; return
        }
        enqueue("Scrittura nel banco $slot…") {
            val s = state.value
            val previous = readStored(true, slot)
            val oldDsp = requireSlotDsp(slot, previous)
            val backup = JSONObject().put("kind", "cab").put("slot", slot)
                .put("stored", JSONObject(previous as Map<*, *>)).put("dsp", oldDsp)
                .put("liveCab", JSONArray(s.cab)).put("replacedBy", custom.name)
            Recovery.write(File(context.filesDir, "backup-before-save-${System.nanoTime()}.json"), backup.toString(2))
            preserveAudition(s, custom.name)
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, 0))
            transfer(custom.transferJson())
            // Save the original level, not the temporary audition attenuation.
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, s.cab[AmpedProtocol.CAB_CABINET_LEVEL]))
            syncNow()
            val expected = state.value.cab
            write(AmpedProtocol.packet(0xaf, slot-1, 0, 0))
            write(encodedName)
            awaitReport { (it[0].toInt() and 255) == 0xaf && (it[1].toInt() and 255) == slot-1 }
            check(Recovery.verifyCab(readStored(true, slot), name, expected)) { "Nome o parametri del banco non coincidono: conserva il backup" }
            slotProfiles[slot] = custom.transferJson()
            log("Nome e 84 parametri verificati. Coefficienti inviati con ACK, non rileggibili dal dispositivo.")
            // Retain the audition recovery until an explicit, acknowledged restore.
        }
    }

    /** True when a previous session left a custom profile in the live DSP. */
    fun auditionOutstanding() = auditionFile.exists()

    @Synchronized fun exportData(): String {
        val backups = JSONArray()
        context.filesDir.listFiles()?.filter { it.name.startsWith("backup-") && !it.name.endsWith(".tmp") }?.forEach {
            backups.put(JSONObject().put("name", it.name).put("contents", it.readText()))
        }
        val root = JSONObject().put("format", "amped-usb-library-v2")
            .put("presets", JSONArray(presetsMutable.value.map { it.json() }))
            .put("customProfiles", JSONArray(customMutable.value.map { it.json() }))
            .put("hardwareBackups", backups)
        if (auditionFile.exists()) root.put("audition", JSONObject(auditionFile.readText()))
        return root.toString(2)
    }
    @Synchronized fun importData(data: String): String = runCatching {
        check(!operation.active) { "Attendi la fine dell’operazione in corso" }
        require(data.length <= 32_000_000) { "File troppo grande" }
        if (data.trim().startsWith("<")) {
            var p = ArchitectPreset.parse(data)
            if (p.scope == "cab") p = p.copy(dsp = profile(p.cab[0],p.cab[1],p.cab[2])!!.toString())
            persist((presetsMutable.value + p).distinctBy { it.id })
            return "Importato preset Architect ${p.scope}: ${p.name} (casse originali)"
        }
        val archive = LibraryArchive.parse(data)
        val list = (presetsMutable.value + archive.presets).distinctBy { it.id }
        val customs = (customMutable.value + archive.custom).distinctBy { it.id }
        require(list.size <= 1000 && customs.size <= 200) { "Library capacity exceeded" }
        // First persist the original import. A failure later never destroys its recovery data.
        Recovery.write(File(context.filesDir, "library-import-source-${System.nanoTime()}.json"), data)
        archive.backups.forEach { Recovery.write(File(context.filesDir, "backup-imported-${System.nanoTime()}.txt"), it) }
        archive.audition?.let {
            // An imported recovery may belong to another device: never activate it automatically.
            Recovery.write(File(context.filesDir, "backup-imported-audition-${System.nanoTime()}.json"), it.toString())
        }
        Recovery.write(customFile, JSONArray(customs.map { it.json() }).toString())
        customMutable.value = customs
        persist(list)
        recoveryMutable.value = readRecoveries()
        "Importati ${archive.presets.size} preset, ${archive.custom.size} profili custom e ${archive.backups.size} backup; nessuna scrittura USB"
    }.getOrElse { "Importazione fallita: ${it.message}" }
    fun disconnect() {
        running.set(false)
        runCatching { request?.cancel() }
        worker?.join(1500)
        mutable.update { it.copy(connected=false,synced=false,busy=false,status=T("AMPED 3 scollegata", "AMPED 3 disconnected")) }
    }
    fun close() { disconnect(); if (registered) { context.unregisterReceiver(receiver); registered=false } }
}

data class LocalPreset(val id:String,val name:String,val amp:List<Int>,val cab:List<Int>, val ampSlot:Int = 0, val dsp:String? = null, val scope:String = "both") {
    fun json()=JSONObject().put("id",id).put("name",name).put("amp",JSONArray(amp)).put("cab",JSONArray(cab)).put("ampSlot",ampSlot).put("scope",scope).put("dsp",dsp?.let { JSONObject(it) })
    companion object {
        fun parseList(s:String):List<LocalPreset> {
            val a=JSONArray(s);require(a.length()<=1000)
            return (0 until a.length()).map { i ->
                val o=a.getJSONObject(i)
                fun values(key:String,size:Int):List<Int> {val ar=o.getJSONArray(key);require(ar.length()==size);return (0 until size).map { ar.getInt(it).also { n->require(n in 0..255) } }}
                val scope = o.optString("scope", "both"); require(scope in listOf("both", "amp", "cab"))
                val slot = o.optInt("ampSlot", 0); require(slot in 0..3)
                val payload = o.optJSONObject("dsp")?.let { Recovery.validateProfile(it).toString() }
                LocalPreset(o.getString("id").take(100),o.getString("name").take(60),values("amp",52),values("cab",84),slot,payload,scope)
            }
        }
    }
}
