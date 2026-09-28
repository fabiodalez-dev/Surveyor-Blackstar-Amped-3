package com.example.amped3controller

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class RecoveryTest {
    @Test fun repeatedAuditionsKeepOriginalRecovery() {
        val dir=Files.createTempDirectory("amped-first-snapshot").toFile()
        try {
            val f=File(dir,"audition.json")
            Recovery.preserve(f){"original cabinet and level"}
            Recovery.preserve(f){"second cabinet at attenuated level"}
            assertEquals("original cabinet and level", f.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun oldInvalidImportDoesNotHideValidPresets() {
        val valid=ArchitectPreset.parse(fixture("real.amped"))
        val bad=valid.json().put("amp",JSONArray(List(52){-1}))
        val (good,rejected)=Recovery.recoverPresets(JSONArray().put(valid.json()).put(bad).toString())
        assertEquals(listOf(valid),good);assertEquals(1,rejected)
    }
    private fun fixture(name: String) = javaClass.getResource("/architect/$name")!!.readText()
    @Test fun architectAmpOfflineKeepsScopedBoostAndChannel() {
        val p = ArchitectPreset.parse(fixture("real.amped"))
        assertEquals("amp", p.scope); assertEquals(3, p.ampSlot)
        assertEquals(70, p.amp[2]); assertEquals(0, p.amp[32]); assertEquals(74, p.amp[3])
        assertEquals(0, p.amp[33]); assertTrue((p.amp+p.cab).all { it in 0..255 })
        assertEquals(p, LocalPreset.parseList(JSONArray().put(p.json()).toString()).single())
    }
    @Test fun architectCabOffsetsMatchActualExport() {
        val p = ArchitectPreset.parse(fixture("real.cabrig"))
        assertEquals("cab", p.scope)
        assertEquals(77, p.cab[3]); assertEquals(71,p.cab[57])
        assertEquals(96,p.cab[67]); assertEquals(95,p.cab[83])
        assertEquals(listOf(16,0,1),p.cab.take(3))
        assertEquals(1,p.cab[56]);assertEquals(1,p.cab[60])
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsExternalEntities() {
        ArchitectPreset.parse("<!DOCTYPE Sound [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><Sound>&x;</Sound>")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsIncompletePresetBeforePersisting() {
        ArchitectPreset.parse("<Sound><Info><Name>x</Name></Info><Amplifier><Gain>85</Gain></Amplifier></Sound>")
    }
    @Test fun sameNameDoesNotVerifyDifferentCabData() {
        val values=List(84){it}
        val stored=mapOf("4:4" to "74657374", "5:60" to AmpedProtocol.hex(values.take(60).map{it.toByte()}.toByteArray()),
            "5:24" to AmpedProtocol.hex(values.drop(60).map{it.toByte()}.toByteArray()))
        assertTrue(Recovery.verifyCab(stored,"test",values))
        assertFalse(Recovery.verifyCab(stored,"test",values.mapIndexed { i,v -> if(i==70) v+1 else v }))
    }
    @Test fun limitStopsReadingAtLimitPlusOne() {
        val input=ByteArrayInputStream(ByteArray(10000))
        assertThrows(IllegalArgumentException::class.java){Recovery.readLimited(input,100)}
        assertEquals(9899,input.available())
        assertEquals(100,Recovery.readLimited(ByteArrayInputStream(ByteArray(100)),100).size)
    }
    @Test fun persistedRecoverySurvivesFailedTransfer() {
        val dir=Files.createTempDirectory("amped-recovery").toFile()
        try {
            val f=File(dir,"audition.json");Recovery.write(f,"original")
            assertThrows(IllegalStateException::class.java) {
                DspTransfer.run(listOf(ByteArray(64)), {}, {null}, {}, {false})
                f.delete()
            }
            assertEquals("original",f.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun archiveRetainsCustomAndBackups() {
        val raw=JSONArray(File("src/main/assets/cab_profiles.json").readText()).getJSONObject(0)
        val chunks=raw.getJSONArray("chunks")
        val custom=CustomProfile("c","test",1,"source","0:0:0",0,0,0,raw.getString("header"),
            (0 until chunks.length()).map{chunks.getString(it)},0.1,0.0)
        val preset=ArchitectPreset.parse(fixture("real.cabrig")).copy(dsp=raw.toString())
        val root=JSONObject().put("format","amped-usb-library-v2")
            .put("presets",JSONArray().put(preset.json()))
            .put("customProfiles",JSONArray().put(custom.json()))
            .put("hardwareBackups",JSONArray().put(JSONObject().put("name","../../escape").put("contents","raw backup")))
        val parsed=LibraryArchive.parse(root.toString())
        assertEquals(custom,parsed.custom.single());assertEquals("raw backup",parsed.backups.single())
        assertEquals(preset,parsed.presets.single())
    }
    @Test fun gateCannotBeAcquiredTwiceUntilOperationCompletes() {
        val gate=OperationGate();assertTrue(gate.begin());assertFalse(gate.begin())
        assertTrue(gate.active);gate.end();assertTrue(gate.begin())
    }
    @Test fun dspTransactionHandlesUnsolicitedReportsAndRepeatedChunks() {
        val chunks=(0..4).map { AmpedProtocol.packet(0xac,it) }
        val input=ArrayDeque(listOf(AmpedProtocol.packet(0xa9),AmpedProtocol.packet(0xab,0),
            AmpedProtocol.packet(0xab,0))+(1..4).map{AmpedProtocol.packet(0xab,it)}+listOf(AmpedProtocol.packet(0xad)))
        val sent=mutableListOf<ByteArray>();var stateReports=0
        DspTransfer.run(chunks,{sent.add(it)},{input.removeFirst()}, {stateReports++},{true})
        assertEquals(6,sent.size);assertEquals(1,stateReports)
    }
    @Test fun dspTransactionRefusesPrematureAckAndTimeout() {
        assertThrows(IllegalStateException::class.java) {
            DspTransfer.run(List(5){ByteArray(64)}, {}, {AmpedProtocol.packet(0xad)}, {}, {true})
        }
        var time=0L
        assertThrows(IllegalStateException::class.java) {
            DspTransfer.run(List(5){ByteArray(64)}, {}, {null}, {}, {true},{time++},3)
        }
    }
}
