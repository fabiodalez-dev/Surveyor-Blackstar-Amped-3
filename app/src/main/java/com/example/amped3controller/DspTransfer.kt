package com.example.amped3controller

/** Blocking protocol transaction; the caller owns the write gate until this returns. */
object DspTransfer {
    fun run(chunks: List<ByteArray>, send: (ByteArray) -> Unit, read: () -> ByteArray?,
            receive: (ByteArray) -> Unit, connected: () -> Boolean,
            now: () -> Long = System::currentTimeMillis, timeout: Long = 6000) {
        val seen = mutableSetOf<Int>()
        val end = now() + timeout
        while (connected() && now() < end) {
            val report = read() ?: continue
            require(report.size == 64) { "Report DSP incompleto" }
            when (report[0].toInt() and 255) {
                0xab -> {
                    val i = report[1].toInt() and 255
                    check(i in chunks.indices) { "Indice DSP non valido" }
                    send(chunks[i]); seen.add(i)
                }
                0xad -> {
                    check(seen.size == chunks.size) { "ACK DSP prima di tutti i blocchi" }
                    return
                }
                else -> receive(report)
            }
        }
        error("Trasferimento DSP non confermato; conserva il recupero e riconnetti USB")
    }
}
