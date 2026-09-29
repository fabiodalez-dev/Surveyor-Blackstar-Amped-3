package com.example.amped3controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pedal's protocol: synchronisation, parameter writes, slot saves, DSP transfers and the
 * audition recovery. The USB pipe lives in [UsbHidLink], everything kept on the phone in
 * [LibraryStore], the IR fit in [IrConversion]. Historical class name retained; the transport
 * is vendor HID, not MIDI.
 */
class AmpedMidiController(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val mutable = MutableStateFlow(AmpState())
    val state = mutable.asStateFlow()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var deviceId: Int? = null
    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val link = UsbHidLink(manager, ::log)
    private val profiles = FactoryProfiles(context.assets.open("cab_profiles.json").bufferedReader().use { it.readText() })
    private val operation = OperationGate()
    private var liveProfile: JSONObject? = null
    private val slotProfiles = mutableMapOf<Int, JSONObject>() // valid only in this uninterrupted USB session
    private var syncDeadline = 0L
    private val savedReports = linkedMapOf<String, String>()
    private val store = LibraryStore(context.filesDir, ::log)
    val presets = store.presets
    val customProfiles = store.customProfiles
    val recoveries = store.recoveries

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
    /** A value the pedal read back differently from what was written. The state is still in
     *  sync, it just is not the requested one, so this must not mark the pedal as unsynced. */
    private class NotAccepted(message: String) : IllegalStateException(message)

    init {
        // a profile left in the live DSP by a previous run must still be revertible after a
        // restart, which is the whole point of writing the snapshot to disk before sending
        if (store.auditionOutstanding()) mutable.update { it.copy(customLoaded = true, customName = store.auditionName()) }
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        store.seedOriginalBackup { context.assets.open("original_backup.json").bufferedReader().use { it.readText() } }
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
                link.open(d)
                savedReports.clear()
                liveProfile = null
                slotProfiles.clear()
                mutable.update { AmpState(connected = true, status = T("Lettura della pedaliera…", "Reading the pedal…"), logs = it.logs, customLoaded = store.auditionOutstanding(), customName = it.customName) }
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
                link.close()
                liveProfile = null; operation.end()
                commands.clear()
            }
        }, "AMPED-HID").also { it.start() }
    }
    private fun write(bytes: ByteArray) = link.write(bytes)
    private fun readReport(): ByteArray? = link.read(running.get())
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
                if (savedReports.size == 15) store.writeHardwareSnapshot(savedReports)
            }
        }
    }
    /** Reservation happens on the caller thread; unsolicited reports cannot release it.
     *  [afterSync] runs on the state read back after [action], to check what the pedal accepted. */
    private fun enqueue(label: String, requireSync: Boolean = true, afterSync: () -> Unit = {}, action: () -> Unit) {
        if (!running.get() || (requireSync && !state.value.synced) || !operation.begin()) return
        mutable.update { it.copy(busy = true, status = label) }
        commands.offer {
            try {
                action()
                syncNow()
                afterSync()
            } catch (e: NotAccepted) {
                log("Valore non accettato: ${e.message}")
                mutable.update { it.copy(status = e.message ?: "") }
            } catch (e: Exception) {
                log("Operazione non confermata: ${e.message}")
                mutable.update { it.copy(synced = false, status = e.message ?: "USB error") }
            } finally {
                store.refreshRecoveries()
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
    /** Compares the resynchronised state with what was written. A write the pedal ignores, or
     *  answers with a value of its own, is reported instead of being taken as done. */
    private fun requireAccepted(amp: Map<Int, Int>, cab: Map<Int, Int>) {
        val s = state.value
        val refused = amp.filter { (o, v) -> !AmpedProtocol.accepted(false, o, v, s.amp[o]) }.map { (o, v) -> "AMP $o: $v → ${s.amp[o]}" } +
            cab.filter { (o, v) -> !AmpedProtocol.accepted(true, o, v, s.cab[o]) }.map { (o, v) -> "CAB $o: $v → ${s.cab[o]}" }
        if (refused.isNotEmpty()) throw NotAccepted(T("La pedaliera non ha accettato ${refused.joinToString()}", "The pedal did not accept ${refused.joinToString()}"))
    }
    fun refresh() = enqueue("Sincronizzazione…", false) { }
    fun setParameter(cab: Boolean, offset: Int, value: Int) = enqueue("Aggiornamento…",
        afterSync = { if (cab) requireAccepted(emptyMap(), mapOf(offset to value)) else requireAccepted(mapOf(offset to value), emptyMap()) }) {
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
    fun chooseCab(cab: Int, mic: Int, axis: Int) {
        val p = profiles.find(cab,mic,axis) ?: return
        enqueue("Caricamento CabRig…") { transfer(p) }
    }
    fun applyEqPreset(name: String) {
        val preset = AmpedProtocol.eqPresets[name] ?: return
        enqueue("Equalizzazione…", afterSync = { requireAccepted(emptyMap(), preset) }) {
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
            val previous = readStored(cab, slot)
            val oldDsp = if (cab) requireSlotDsp(slot, previous) else null
            if (cab) check(liveProfile != null) { "Applica prima un profilo DSP noto" }
            store.writeBackup("backup-before-save", JSONObject().put("kind", if (cab) "cab" else "amp").put("slot", slot)
                .put("stored", JSONObject(previous as Map<*, *>)).put("dsp", oldDsp)
                .put("liveAmp", JSONArray(snapshot.amp)).put("liveCab", JSONArray(snapshot.cab)))
            if (cab) write(AmpedProtocol.packet(0xaf, slot - 1, 0, 0))
            else write(AmpedProtocol.saveAmpPacket(slot, snapshot.amp))
            write(encodedName)
            if (cab) awaitReport { (it[0].toInt() and 255) == 0xaf && (it[1].toInt() and 255) == slot - 1 }
            var verified = false
            val deadline = System.currentTimeMillis() + 4000
            while (!verified && System.currentTimeMillis() < deadline) {
                val actual = readStored(cab, slot)
                verified = if (cab) Recovery.verifyCab(actual, name, snapshot.cab) else Recovery.verifyAmp(actual, name, snapshot.amp)
            }
            check(verified) { "Lo slot riletto non coincide: conserva il backup, non ripetere il salvataggio" }
            log("Slot $slot verificato: nome e ${if (cab) "84 parametri CabRig" else "tutti i 15 byte AMP memorizzati"}")
            mutable.update { if (cab) it.copy(cabNames = it.cabNames + (slot to name)) else it.copy(ampNames = it.ampNames + (slot to name)) }
            if (cab) slotProfiles[slot] = liveProfile!!
        }
    }
    /** User explicitly identifies a factory slot. This reads only; custom slots must not use it,
     *  because selector indices alone cannot recover custom coefficients. */
    fun confirmFactorySlot(slot: Int) = enqueue("Backup della cassa originale nel banco $slot…") {
        require(slot in 1..3)
        val records = readStored(true, slot)
        val cab = Recovery.storedCab(records)
        val dsp = profiles.find(cab[0], cab[1], cab[2]) ?: error("Profilo originale sconosciuto")
        store.writeBackup("backup-factory", JSONObject().put("kind", "cab").put("slot", slot)
            .put("stored", JSONObject(records as Map<*, *>)).put("dsp", dsp)
            .put("provenance", "factory-confirmed-by-user"))
        slotProfiles[slot] = dsp
        log("Backup parametri e profilo originale banco $slot salvato; nessuna scrittura USB")
    }
    fun confirmCustomSlot(custom: CustomProfile, slot: Int) = enqueue("Backup del custom già presente nel banco $slot…") {
        require(slot in 1..3)
        val records = readStored(true, slot)
        val values = Recovery.storedCab(records)
        check(values.take(3) == listOf(custom.cab, custom.mic, custom.axis)) { "Il template del banco non coincide con quello del custom" }
        val dsp = Recovery.validateProfile(custom.transferJson())
        store.writeBackup("backup-custom-confirmed", JSONObject().put("kind", "cab").put("slot", slot)
            .put("stored", JSONObject(records as Map<*, *>)).put("dsp", dsp).put("provenance", "custom-identity-confirmed-by-user"))
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
    @Synchronized fun saveLocal(name: String) {
        val s = state.value
        if (!s.synced || s.busy || name.isBlank()) return
        val dsp = liveProfile?.takeIf { listOf(it.getInt("cab"),it.getInt("mic"),it.getInt("axis")) == s.cab.take(3) } ?: run {
            mutable.update { it.copy(status = "Profilo DSP attuale sconosciuto: applica una cassa o un custom prima di salvare il preset locale") }; return
        }
        val preset = LocalPreset(System.currentTimeMillis().toString(), name.trim().take(60), s.amp, s.cab, s.ampSlot, dsp.toString())
        runCatching { store.addPreset(preset) }.onFailure { e -> mutable.update { it.copy(status = "Salvataggio locale fallito: ${e.message}") } }
    }
    fun applyLocal(p: LocalPreset) {
        val coeff = p.dsp?.let { JSONObject(it) } ?: profiles.find(p.cab[0],p.cab[1],p.cab[2]) ?: return
        if (p.scope != "amp" && refusesPayload(coeff)) return
        val ampOffsets = listOf(0,1,2,3,4,5,6,7,8,22,24,25,26,27,28,33)
        val expectedAmp = if (p.scope != "cab") ampOffsets.associateWith { p.amp[it] } + (AmpedProtocol.AMP_STATUS to p.amp[AmpedProtocol.AMP_STATUS]) else emptyMap()
        val expectedCab = if (p.scope != "amp") AmpedProtocol.cabPresetOffsets.associateWith { p.cab[it] } else emptyMap()
        enqueue("Caricamento ${p.name}…", afterSync = { requireAccepted(expectedAmp, expectedCab) }) {
            val originalAmp = state.value.amp
            if (p.scope != "cab" && p.ampSlot in 1..3) {
                write(AmpedProtocol.packet(2,0x11,p.ampSlot,0))
                syncNow()
                write(AmpedProtocol.parameter(false,9,originalAmp[9]))
                write(AmpedProtocol.parameter(false,AmpedProtocol.AMP_POWER,originalAmp[AmpedProtocol.AMP_POWER]))
            }
            if (p.scope != "cab") {
                for (i in ampOffsets) write(AmpedProtocol.parameter(false,i,p.amp[i]))
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

    /** Runs [IrConversion] off the USB queue: it must not wait behind hardware commands, and it
     *  has to work with no pedal attached. The result is never sent anywhere until the user asks. */
    fun convertIr(bytes: ByteArray, source: String) {
        if (!operation.begin()) return
        mutable.update { it.copy(busy = true, status = T("Conversione della risposta…", "Converting the impulse response…")) }
        Thread {
            val outcome = runCatching { IrConversion.convert(bytes, source, state.value.cab, profiles) }
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
    private fun refuses(custom: CustomProfile): Boolean = refuses(custom.header, custom.chunks)

    /**
     * A payload from a local preset or an imported recovery file. Byte-identical to a factory
     * profile it goes out as it is; anything else has to pass the same checks as a custom profile,
     * so an imported archive is not a way around them.
     */
    private fun refusesPayload(dsp: JSONObject): Boolean {
        val header = dsp.optString("header")
        val array = dsp.optJSONArray("chunks") ?: return refuses(header, emptyList())
        val factory = profiles.find(dsp.optInt("cab", -1), dsp.optInt("mic", -1), dsp.optInt("axis", -1))
        if (factory != null && factory.getString("header") == header &&
            factory.getJSONArray("chunks").toString() == array.toString()) return false
        return refuses(header, (0 until array.length()).map { array.getString(it) })
    }

    private fun refuses(header: String, chunks: List<String>): Boolean {
        val values = com.example.amped3controller.dsp.CabProfile.decode(header, chunks)
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
        store.addCustom(entry)?.let { reason -> mutable.update { it.copy(status = reason) } }
    }

    fun deleteCustom(id: String) = store.deleteCustom(id)

    /**
     * Loads a profile into the live DSP for listening. Before anything goes out, the current live
     * state and the factory payload for the cabinet in use are written to disk, so a crash in the
     * middle still leaves a way back; then the cabinet level is dropped to its minimum, because
     * the first time you hear something new it should not be at gig volume.
     */
    private fun preserveAudition(s: AmpState, name: String) {
        if (store.auditionOutstanding()) return // keep the first pre-audition state across A/B tests
        val back = liveProfile?.takeIf { listOf(it.getInt("cab"),it.getInt("mic"),it.getInt("axis")) == s.cab.take(3) } ?: error("DSP attuale sconosciuto: applica prima una cassa nota; nessuna prova inviata")
        store.preserveAudition { JSONObject().put("cab", JSONArray(s.cab))
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
            val saved = store.readAudition()
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, 0))
            transfer(saved.getJSONObject("factory"))
            val old = saved.getJSONArray("cab")
            for (i in AmpedProtocol.cabPresetOffsets) if (i != AmpedProtocol.CAB_CABINET_LEVEL)
                write(AmpedProtocol.parameter(true, i, old.getInt(i)))
            syncNow()
            write(AmpedProtocol.parameter(true, AmpedProtocol.CAB_CABINET_LEVEL, saved.getInt("level")))
            syncNow()
            check(state.value.cab[AmpedProtocol.CAB_CABINET_LEVEL] == saved.getInt("level"))
            store.clearAudition()
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
            store.writeBackup("backup-before-save", JSONObject().put("kind", "cab").put("slot", slot)
                .put("stored", JSONObject(previous as Map<*, *>)).put("dsp", oldDsp)
                .put("liveCab", JSONArray(s.cab)).put("replacedBy", custom.name))
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

    fun exportData(): String = store.export()
    fun importData(data: String): String = runCatching {
        check(!operation.active) { "Attendi la fine dell’operazione in corso" }
        require(data.length <= 32_000_000) { "File troppo grande" }
        if (data.trim().startsWith("<")) {
            var p = ArchitectPreset.parse(data)
            if (p.scope == "cab") p = p.copy(dsp = profiles.find(p.cab[0],p.cab[1],p.cab[2])!!.toString())
            store.importPreset(p)
            return "Importato preset Architect ${p.scope}: ${p.name} (casse originali)"
        }
        store.importArchive(data)
    }.getOrElse { "Importazione fallita: ${it.message}" }
    fun disconnect() {
        running.set(false)
        link.cancel()
        worker?.join(1500)
        mutable.update { it.copy(connected=false,synced=false,busy=false,status=T("AMPED 3 scollegata", "AMPED 3 disconnected")) }
    }
    fun close() { disconnect(); if (registered) { context.unregisterReceiver(receiver); registered=false } }
}
