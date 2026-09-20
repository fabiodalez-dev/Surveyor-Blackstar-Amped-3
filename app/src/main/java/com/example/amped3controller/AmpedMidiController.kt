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
    private val presetsFile = File(context.filesDir, "presets.json")
    private val presetsMutable = MutableStateFlow(readPresets())
    val presets = presetsMutable.asStateFlow()
    private var registered = true
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                permissionAction -> if (d != null && d.vendorId == AmpedProtocol.VID && d.productId == AmpedProtocol.PID) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(d)
                    else mutable.update { it.copy(status = "Permesso USB negato. Tocca Connetti per riprovare.") }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectToAmp()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (d?.deviceId == deviceId) disconnect()
            }
        }
    }
    init {
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
        if (d == null) { mutable.update { it.copy(status = "AMPED 3 non collegata · usa un cavo USB dati / OTG") }; return }
        deviceId = d.deviceId
        if (manager.hasPermission(d)) open(d) else {
            mutable.update { it.copy(status = "Autorizza l’accesso USB ad AMPED 3") }
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
                mutable.update { AmpState(connected = true, status = "Lettura della pedaliera…", logs = it.logs) }
                log("HID ${iface.id}, endpoint ${ep.address}, packet ${ep.maxPacketSize}")
                startSync()
                for (slot in 1..3) for (kind in listOf(0x14,0x15,4,5)) write(AmpedProtocol.packet(2,kind,slot,0))
                while (running.get()) {
                    commands.poll()?.invoke()
                    readReport()?.let(::receive)
                    val now = System.currentTimeMillis()
                    if (pendingDsp != null && now > dspDeadline) error("Timeout trasferimento CabRig. Ricollega USB per rileggere lo stato.")
                    if (syncDeadline > 0 && now > syncDeadline) {
                        syncDeadline = 0
                        mutable.update { it.copy(synced = false, busy = false, status = "Nessuna risposta completa. Tocca Sincronizza.") }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) { log("Errore: ${e.message}"); mutable.update { it.copy(connected = false, synced = false, busy = false, status = e.message ?: "Errore USB") } }
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
        mutable.update { it.copy(synced = false, amp = List(52){-1}, cab = List(84){-1}, status = "Sincronizzazione…") }
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
                if (complete) { syncDeadline = 0; updated.copy(synced = true, busy = false, status = "AMPED 3 sincronizzata") } else updated
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
        commands.offer { write(AmpedProtocol.parameter(cab,offset,value)); startSync() }
    }
    fun recallAmp(slot: Int) {
        if (!state.value.synced || state.value.busy) return
        mutable.update { it.copy(busy = true, status = "Cambio canale…") }
        commands.offer { write(AmpedProtocol.packet(2,0x11,slot,0)); startSync() }
    }
    private fun profile(cab: Int, mic: Int, axis: Int): JSONObject? = (0 until profiles.length()).map { profiles.getJSONObject(it) }.firstOrNull { it.getInt("cab")==cab && it.getInt("mic")==mic && it.getInt("axis")==axis }
    fun chooseCab(cab: Int, mic: Int, axis: Int) {
        if (!state.value.synced || state.value.busy) return
        val p = profile(cab,mic,axis) ?: return
        mutable.update { it.copy(busy = true, status = "Caricamento CabRig…") }
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
        if (!state.value.synced || state.value.busy || slot !in 1..3) return
        mutable.update { it.copy(busy = true, status = "Salvataggio Amp in memoria…") }
        commands.offer {
            val payload = ByteArray(64)
            payload[0] = 0x02; payload[1] = 0x13; payload[2] = slot.toByte(); payload[3] = 52.toByte()
            System.arraycopy(state.value.amp, 0, payload, 4, 52)
            write(payload)
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val namePayload = ByteArray(64)
            namePayload[0] = 0x02; namePayload[1] = 0x12; namePayload[2] = slot.toByte(); namePayload[3] = minOf(nameBytes.size, 60).toByte()
            System.arraycopy(nameBytes, 0, namePayload, 4, minOf(nameBytes.size, 60))
            write(namePayload)
            startSync()
        }
    }
    fun saveCabHardware(slot: Int, name: String) {
        if (!state.value.synced || state.value.busy || slot !in 1..3) return
        mutable.update { it.copy(busy = true, status = "Salvataggio CabRig in memoria…") }
        commands.offer {
            val burn = ByteArray(64)
            burn[0] = 0xaf.toByte(); burn[1] = (slot - 1).toByte(); burn[2] = 0; burn[3] = 0
            write(burn)
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val namePayload = ByteArray(64)
            namePayload[0] = 0x02; namePayload[1] = 0x02; namePayload[2] = slot.toByte(); namePayload[3] = minOf(nameBytes.size, 60).toByte()
            System.arraycopy(nameBytes, 0, namePayload, 4, minOf(nameBytes.size, 60))
            write(namePayload)
            startSync()
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
        check(temp.renameTo(presetsFile)) { "Salvataggio preset fallito" }
        presetsMutable.value = list
    }
    fun applyLocal(p: LocalPreset) {
        if (!state.value.synced || state.value.busy) return
        val coeff = profile(p.cab[0],p.cab[1],p.cab[2])
        if (coeff == null && p.cab.take(3) != state.value.cab.take(3)) {
            mutable.update { it.copy(status = "Profilo microfono non acquisito: preset non applicato") }; return
        }
        mutable.update { it.copy(busy = true, status = "Caricamento ${p.name}…") }
        commands.offer {
            // Master and power are deliberately left at their current hardware setting.
            for (i in listOf(0,1,2,3,4,5,6,7,8,22,24,25,26,27,28)) write(AmpedProtocol.parameter(false,i,p.amp[i]))
            for (i in listOf(70,73,77,81)) write(AmpedProtocol.parameter(true,i,p.cab[i]))
            if (coeff != null) transfer(coeff) else { mutable.update { it.copy(busy=false) }; startSync() }
        }
    }
    fun exportData(): String = JSONObject().put("format","amped-usb-library-v1")
        .put("presets", JSONArray(presetsMutable.value.map { it.json() }))
        .put("hardwareBackups",JSONArray(context.filesDir.listFiles()?.filter { it.name.startsWith("backup-") }?.map { JSONObject(it.readText()) } ?: emptyList<JSONObject>()))
        .toString(2)
    fun importData(json: String): String = runCatching {
        require(json.length <= 2_000_000) { "File troppo grande" }
        val root=JSONObject(json); require(root.getString("format")=="amped-usb-library-v1") { "Formato non riconosciuto" }
        val imported=LocalPreset.parseList(root.getJSONArray("presets").toString())
        val list=(presetsMutable.value+imported).distinctBy { it.id }
        persist(list); "Importati ${imported.size} preset"
    }.getOrElse { "Importazione fallita: ${it.message}" }
    fun disconnect() {
        running.set(false)
        runCatching { request?.cancel() }
        worker?.join(1500)
        mutable.update { it.copy(connected=false,synced=false,busy=false,status="AMPED 3 scollegata") }
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
