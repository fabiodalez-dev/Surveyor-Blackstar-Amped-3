package com.example.amped3controller

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** The raw vendor HID pipe to the pedal: 64-byte reports out, 64-byte reports in. It knows nothing
 *  about the protocol; every method must be called from the single worker thread that opened it. */
class UsbHidLink(private val manager: UsbManager, private val log: (String) -> Unit) {
    private var connection: UsbDeviceConnection? = null
    private var hidInterface: UsbInterface? = null
    private var request: UsbRequest? = null
    private var epOut: UsbEndpoint? = null
    private var queued = false
    private val input = ByteBuffer.allocateDirect(64)

    fun open(d: UsbDevice) {
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
        log("HID ${iface.id}, endpoint ${ep.address}, packet ${ep.maxPacketSize}")
    }

    fun write(bytes: ByteArray) {
        var count = connection?.controlTransfer(0x21, 9, 0x0200, hidInterface!!.id, bytes, 64, 1000) ?: -1
        if (count < 0 && epOut != null) {
            count = connection?.bulkTransfer(epOut, bytes, bytes.size, 1000) ?: -1
        }
        check(count == 64) { "Invio USB incompleto ($count/64)" }
        log("→ " + AmpedProtocol.hex(bytes).take(32))
    }

    /** One report, or null when none arrived within 40 ms. [running] tells a deliberate close
     *  apart from a cable pulled out, which is an error. */
    fun read(running: Boolean): ByteArray? {
        if (!queued) { input.clear(); check(request!!.queue(input)) { "Lettura HID non avviata" }; queued = true }
        val result = try { connection!!.requestWait(40) } catch (_: TimeoutException) { return null }
        if (result == null) { if (!running) return null; error("Connessione USB interrotta") }
        queued = false
        val length = input.position()
        if (length != 64) { log("Report incompleto: $length"); return null }
        input.flip(); return ByteArray(length).also { input.get(it) }
    }

    /** Unblocks a pending [read] from another thread. */
    fun cancel() { runCatching { request?.cancel() } }

    fun close() {
        runCatching { request?.cancel() }; runCatching { request?.close() }
        hidInterface?.let { runCatching { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        request = null; connection = null; queued = false
    }
}
