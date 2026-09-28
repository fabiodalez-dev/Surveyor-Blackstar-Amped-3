package com.example.amped3controller

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

/** Parsed against real Architect 2.1.3 .amped and .cabrig exports. No live state needed. */
object ArchitectPreset {
    val ampFields = mapOf("Gain" to 0, "Volume" to 1, "Boost" to 2, "Reverb" to 3,
        "Bass" to 4, "Middle" to 5, "Treble" to 6, "ISF" to 7, "Presence" to 8)
    val switches = mapOf("Response" to 22, "PrePost" to 24, "DarkLight" to 25,
        "Clean" to 26, "Crunch" to 27, "Drive" to 28)
    val cabFields = mapOf("Cab_One" to 0, "Mic_One" to 1, "Axis_One" to 2,
        "Level_One" to AmpedProtocol.CAB_CABINET_LEVEL, "Solo_One" to 5, "Mute_One" to 6,
        "Room_Type" to 56, "Room_Level" to AmpedProtocol.CAB_ROOM_LEVEL, "Room_Solo" to 58,
        "Room_Mute" to 59, "Room_Width" to 60, "EQ_Master_Bypass" to 74,
        "EQ_Master_Low" to 70, "EQ_Master_LowMid" to 73, "EQ_Master_HighMid" to 77,
        "EQ_Master_High" to 81, "EQ_Master_LowCut" to AmpedProtocol.CAB_LOW_CUT_FREQ,
        "EQ_Master_HighCut" to AmpedProtocol.CAB_HIGH_CUT_FREQ, "EQ_Master_LowCutToggle" to 66,
        "EQ_Master_HighCutToggle" to 82)

    fun parse(xml: String): LocalPreset {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "XML entities are not supported" }
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        require(root.tagName == "Sound") { "Not an Architect preset" }
        fun text(parent: Element, name: String): String {
            val list = parent.getElementsByTagName(name)
            require(list.length == 1) { "Missing or ambiguous field: $name" }
            return list.item(0).textContent.trim()
        }
        fun value(parent: Element, name: String): Int = text(parent, name).toInt().also { require(it in 0..255) }
        val amp = MutableList(52) { 0 }; val cab = MutableList(84) { 0 }
        val ampNode = root.getElementsByTagName("Amplifier").item(0) as? Element
        val isCab = root.getElementsByTagName("Channels").length > 0
        require((ampNode != null) xor isCab) { "Expected a single AMP or CabRig preset" }
        var slot = 0
        if (ampNode != null) {
            ampFields.forEach { (tag, offset) -> amp[offset] = value(ampNode, tag).also { require(it <= 127) } }
            val toggle = root.getElementsByTagName("TogglesSwitches").item(0) as? Element ?: error("Missing switches")
            switches.forEach { (tag, offset) -> amp[offset] = value(toggle, tag) }
            amp[32] = if (value(toggle, "Boost") != 0) AmpedProtocol.AMP_BOOST_BIT else 0
            amp[33] = value(toggle, "Reverb").also { require(it in 0..1) }
            slot = value(root, "Voice") + 1
            require(slot in 1..3)
        } else {
            cabFields.forEach { (tag, offset) -> cab[offset] = value(root, tag) }
            require(cab[0] in 0..23 && cab[1] in 0..5 && cab[2] in 0..1)
            require(cab[3] <= 127 && cab[57] <= 127)
        }
        // Power, Master, the other bank and unidentified fields are intentionally not applied.
        return LocalPreset("arch-${System.nanoTime()}", text(root, "Name").take(60), amp, cab,
            slot, scope = if (isCab) "cab" else "amp")
    }
}
