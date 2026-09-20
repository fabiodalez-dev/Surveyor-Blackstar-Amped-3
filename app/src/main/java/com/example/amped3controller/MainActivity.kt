package com.example.amped3controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush


import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Coal=Color(0xff171719)
private val Panel=Color(0xff242427)
private val Paper=Color(0xfff3eeea)
private val Red=Color(0xffdd0000)
private val Muted=Color(0xffbab3b0)

class MainActivity : ComponentActivity() {
    private lateinit var controller:AmpedMidiController
    private var notice by mutableStateOf("")
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){ uri ->
        if(uri!=null) runCatching {contentResolver.openOutputStream(uri)!!.bufferedWriter().use {it.write(controller.exportData())}}
            .onSuccess {notice="Libreria e backup esportati"}.onFailure {notice="Esportazione fallita: ${it.message}"}
    }
    private val import=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null) notice=runCatching {contentResolver.openInputStream(uri)!!.bufferedReader().use {reader ->
            val chars=CharArray(2_000_001);var count=0
            while(count<chars.size){val n=reader.read(chars,count,chars.size-count);if(n<0)break;count+=n}
            controller.importData(String(chars,0,count))
        }}.getOrElse {"File non leggibile: ${it.message}"}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        controller=AmpedMidiController(applicationContext)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Red,onPrimary=Coal,background=Coal,surface=Coal,surfaceVariant=Panel,onSurface=Paper,onSurfaceVariant=Muted,secondary=Red)) {
                AmpedApp(controller,notice,{export.launch("Amped3-preset-e-backup.json")},{import.launch(arrayOf("application/json","text/plain","text/xml","application/xml","*/*"))})
            }
        }
        controller.connectToAmp()
    }
    override fun onDestroy(){controller.close();super.onDestroy()}
}

var lang by mutableStateOf("en")
fun T(it: String, en: String) = if (lang == "it") it else en

@Composable fun AmpedApp(c:AmpedMidiController,notice:String,onExport:()->Unit,onImport:()->Unit){
    val s by c.state.collectAsState()
    val presets by c.presets.collectAsState()
    var tab by remember {mutableIntStateOf(0)}
    val titles=listOf(T("Amp","Amp"),T("CabRig","CabRig"),T("Preset","Preset"),T("Impostazioni","Settings"))
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Coal,
            bottomBar = {
            NavigationBar(containerColor = Panel) {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(listOf("A", "C", "P", "↗")[i], fontWeight = FontWeight.Bold, fontSize = 19.sp) },
                        label = { Text(t) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)){
            Box(Modifier.fillMaxWidth().height(90.dp)) {
                Image(painter = painterResource(R.drawable.tolex_header), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Row(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Image(painter = painterResource(R.drawable.blackstar_logo), contentDescription = "Blackstar", modifier = Modifier.height(40.dp), contentScale = ContentScale.Fit)
                    Column(horizontalAlignment = Alignment.End) {
                        if (s.synced || s.connected) {
                            Text(s.ampNames[s.ampSlot]?.uppercase() ?: "CH ${s.ampSlot}", color=Color.White, fontSize=16.sp, fontWeight=FontWeight.Black, letterSpacing=1.sp)
                        }
                        Text(if(s.synced)"● LIVE" else if(s.connected)"● USB" else "○ USB",color=if(s.synced)Color(0xffff3300) else Muted,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                Text(s.status.ifEmpty { T("Collega AMPED 3 via USB", "Connect AMPED 3 over USB") },Modifier.weight(1f),fontSize=12.sp,color=Muted)
                TextButton(onClick={c.connectToAmp()},enabled=!s.busy){Text(if(s.connected)T("Sincronizza", "Sync") else T("Connetti", "Connect"))}
            }
            if(notice.isNotBlank())Text(notice,color=Red,fontSize=12.sp,modifier=Modifier.padding(16.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                when(tab){
                    0->AmpPage(s,c)
                    1->CabPage(s,c)
                    2->PresetsPage(s,c,presets,onExport,onImport)
                    3->{
                        Heading(T("Impostazioni", "Settings"),T("Sistema e diagnostica", "System and Diagnostics"))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            Text(T("Lingua dell'app:", "App Language:"), color=Color.White, modifier=Modifier.weight(1f))
                            SegmentedButton(listOf("Italiano" to "it", "English" to "en"), lang) { lang = it }
                        }
                        HorizontalDivider(Modifier.padding(bottom=12.dp),color=Panel)
                        Heading(T("Connessione", "Connection"),"USB HID · AMPED 3")
                        Text(T("Collega il telefono direttamente alla pedaliera con un cavo USB dati. Consenti l’accesso USB quando richiesto. I valori compaiono dopo la lettura della pedaliera.", "Connect your phone directly to the pedal using a USB data cable. Allow USB access when prompted. Values will appear after the pedal is read."),color=Muted)
                        Text(T("Backup automatico", "Auto Backup"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=16.dp))
                        Text(T("I tre slot AMP e i tre slot CabRig vengono letti e salvati nel telefono. Esporta i backup per conservarli anche fuori dall’app.", "The three AMP slots and three CabRig slots are read and saved to your phone. Export the backups to keep them outside the app."),color=Muted)
                        OutlinedButton(onClick=onExport,modifier=Modifier.fillMaxWidth()){Text(T("Esporta libreria e backup", "Export Library & Backups"))}
                        Text(T("Diagnostica", "Diagnostics"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                        Text(s.logs.ifBlank {T("In attesa del dispositivo USB.", "Waiting for USB device.")},fontSize=11.sp,color=Muted)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    }
}
@Composable private fun Heading(title:String,subtitle:String){Column(Modifier.padding(top=10.dp,bottom=14.dp)){Text(subtitle.uppercase(),fontSize=10.sp,color=Red,letterSpacing=1.6.sp);Text(title,fontSize=28.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun Section(title:String){HorizontalDivider(Modifier.padding(top=18.dp,bottom=12.dp),color=Panel);Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold)}
@Composable private fun AmpPage(s:AmpState,c:AmpedMidiController){
    Heading(s.ampNames[s.ampSlot] ?: T("Il tuo amplificatore", "Your Amplifier"),if(s.ampSlot>0)"Slot ${s.ampSlot} · impostazioni attuali" else T("Impostazioni attuali", "Current Settings"))
    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
        listOf("Clean","Crunch","Overdrive").chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, name ->
                    val i = rowIndex * 2 + colIndex
                    val sel = s.ampSlot == i+1
                    Box(modifier = Modifier.weight(1f).aspectRatio(2.2f).background(Panel, RoundedCornerShape(8.dp)).clickable(enabled=s.synced&&!s.busy){c.recallAmp(i+1)}, contentAlignment=Alignment.Center) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Canvas(Modifier.size(8.dp)) {
                                if(sel) { drawCircle(Red); drawCircle(Color.White.copy(alpha=0.6f), radius=2.dp.toPx()) }
                                else { drawCircle(Color(0xFF222222)) }
                            }
                            Text(name.uppercase(), color=if(sel) Color.White else Muted, fontWeight=FontWeight.Black, fontSize=16.sp)
                        }
                    }
                }
                if(row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if(!s.synced)Text(T("In attesa dei valori reali della pedaliera.", "Waiting for live values from the pedal."),color=Muted,fontSize=13.sp)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) { listOf("Gain" to 0,"Volume preamp" to 1).forEach {(label,index)->Parameter(label,s.amp[index],127,s.synced&&!s.busy,{"%.1f".format(Locale.US,it*10f/127)}){c.setParameter(false,index,it)}} }
    Section(T("Equalizzazione", "Equalization"))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) { listOf("Bass" to 4,"Middle" to 5,"Treble" to 6,"ISF" to 7,"Presence" to 8).forEach {(label,index)->Parameter(label,s.amp[index],127,s.synced&&!s.busy,{"%.1f".format(Locale.US,it*10f/127)}){c.setParameter(false,index,it)}} }
    Section(T("Carattere", "Character"))
    Choices(T("Risposta", "Response"),s.amp[22],listOf("EL84" to 2,"EL34" to 3,"6L6" to 1),s.synced&&!s.busy){c.setParameter(false,22,it)}
    Choices(T("Voce Clean", "Clean Voice"),s.amp[26],listOf("Warm" to 1,"Bright" to 0),s.synced&&!s.busy){c.setParameter(false,26,it)}
    Choices(T("Voce Crunch", "Crunch Voice"),s.amp[27],listOf("Crunch" to 1,"Super" to 0),s.synced&&!s.busy){c.setParameter(false,27,it)}
    Choices(T("Voce Overdrive", "Overdrive Voice"),s.amp[28],listOf("OD1" to 1,"OD2" to 0),s.synced&&!s.busy){c.setParameter(false,28,it)}
    Section(T("Boost e riverbero", "Boost and Reverb"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = (s.amp.getOrNull(AmpedProtocol.AMP_STATUS) ?: 0) and AmpedProtocol.AMP_BOOST_BIT != 0, onCheckedChange = { c.setParameter(false, AmpedProtocol.AMP_STATUS, (s.amp.getOrNull(AmpedProtocol.AMP_STATUS) ?: 0) xor AmpedProtocol.AMP_BOOST_BIT) }, enabled = s.synced && !s.busy)
        Text(T("Attiva Boost", "Enable Boost"), modifier = Modifier.padding(start = 4.dp))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter("Boost",s.amp[2],127,s.synced&&!s.busy){c.setParameter(false,2,it)} }
    Choices(T("Posizione boost", "Boost Position"),s.amp[24],listOf("Pre" to 1,"Post" to 0),s.synced&&!s.busy){c.setParameter(false,24,it)}
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.amp.getOrNull(AmpedProtocol.AMP_REVERB_ON) == 1, onCheckedChange = { c.setParameter(false, AmpedProtocol.AMP_REVERB_ON, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text(T("Attiva Riverbero", "Enable Reverb"), modifier = Modifier.padding(start = 4.dp))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Riverbero", "Reverb"),s.amp[3],127,s.synced&&!s.busy){c.setParameter(false,3,it)} }
    Choices(T("Carattere riverbero", "Reverb Character"),s.amp[25],listOf("Dark" to 1,"Light" to 0),s.synced&&!s.busy){c.setParameter(false,25,it)}
    Section(T("Uscita", "Output"))
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter("Master",s.amp[9],127,s.synced&&!s.busy){c.setParameter(false,9,it)} }
    Section(T("Salvataggio Rapido", "Quick Save"))
    var saveName by remember {mutableStateOf(s.ampNames[s.ampSlot] ?: "Mio Suono")}
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text("Nome")},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveAmpHardware(if (s.ampSlot > 0) s.ampSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text("Salva su Slot ${if (s.ampSlot > 0) s.ampSlot else 1}",color=Color.White)}
    }
}
@Composable private fun CabPage(s:AmpState,c:AmpedMidiController){
    val name=AmpedProtocol.cabinetNames.getOrNull(s.cab[0])?:"CabRig"
    Heading(name,if(s.cabSlot>0)"CabRig · slot ${s.cabSlot}" else "CabRig")
    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)){
        listOf("Cab 1","Cab 2","Cab 3").chunked(3).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, t ->
                    val i = rowIndex * 3 + colIndex
                    val sel = s.cabSlot == i+1
                    Box(modifier = Modifier.weight(1f).aspectRatio(2.5f).background(Panel, RoundedCornerShape(8.dp)).clickable(enabled=s.synced&&!s.busy){c.recallCab(i+1)}, contentAlignment=Alignment.Center) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Canvas(Modifier.size(8.dp)) {
                                if(sel) { drawCircle(Red); drawCircle(Color.White.copy(alpha=0.6f), radius=2.dp.toPx()) }
                                else { drawCircle(Color(0xFF222222)) }
                            }
                            Text(t.uppercase(), color=if(sel) Color.White else Muted, fontWeight=FontWeight.Black, fontSize=16.sp)
                        }
                    }
                }
            }
        }
    }
    CabinetDrawing(name)
    var cabinet by remember(s.cab[0]) { mutableIntStateOf(if (s.cab[0] in AmpedProtocol.cabinetNames.indices) s.cab[0] else 0) }
    var cabinetExpanded by remember { mutableStateOf(false) }
    var mic by remember(s.cab[1]){mutableIntStateOf(if(s.cab[1] in 0..5) s.cab[1] else 0)}
    var axis by remember(s.cab[2]){mutableIntStateOf(if(s.cab[2]==1) 1 else 0)}
    Text("Profili DSP completi: 288/288 · 24 casse × 6 microfoni × 2 assi",color=Muted,fontSize=12.sp)
    Text(T("Cassa", "Cabinet"), color=Muted, fontSize=12.sp, fontWeight=FontWeight.Bold)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={cabinetExpanded=true}, enabled=!s.busy, modifier=Modifier.fillMaxWidth()) {
            Text(AmpedProtocol.cabinetNames[cabinet])
        }
        DropdownMenu(expanded=cabinetExpanded,onDismissRequest={cabinetExpanded=false}) {
            AmpedProtocol.cabinetNames.forEachIndexed { i,n ->
                DropdownMenuItem(text={Text(n)},onClick={cabinet=i;cabinetExpanded=false})
            }
        }
    }
    Choices(T("Microfono", "Microphone"),mic,listOf("57 Dynamic" to 0,"421 Dynamic" to 1,"67 Condenser" to 2,"414 Condenser" to 3,"121 Ribbon" to 4,"160 Ribbon" to 5),!s.busy){mic=it}
    Choices("Asse",axis,listOf("On Axis" to 0,"Off Axis" to 1),!s.busy){axis=it}
    Text("Selezionato: ${AmpedProtocol.cabinetNames[cabinet]} · ${listOf("57 Dyn","421 Dyn","67 Cond","414 Cond","121 Rib","160 Rib")[mic]} · ${if(axis==0)"On Axis" else "Off Axis"}",color=Muted,fontSize=12.sp)
    Text("Attuale: $name · ${listOf("57 Dyn","421 Dyn","67 Cond","414 Cond","121 Rib","160 Rib").getOrNull(s.cab[1])?:"—"} · ${if(s.cab[2]==0)"On Axis" else if(s.cab[2]==1)"Off Axis" else "—"}",color=Muted,fontSize=12.sp)
    Button(onClick={c.chooseCab(cabinet,mic,axis)},enabled=s.synced&&!s.busy,modifier=Modifier.fillMaxWidth()){Text("Applica profilo DSP")}
    if (!s.synced) Text("Collega AMPED 3 via USB OTG e premi Connect per applicare il profilo selezionato.", color=Muted, fontSize=12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(5) == 1, onCheckedChange = { c.setParameter(true, 5, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Solo", modifier = Modifier.padding(start = 4.dp, end = 16.dp))
        Checkbox(checked = s.cab.getOrNull(6) == 1, onCheckedChange = { c.setParameter(true, 6, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Mute", modifier = Modifier.padding(start = 4.dp))
    }
    Section(T("Equalizzatore CabRig", "CabRig Equalizer"))
    var eqPreset by remember { mutableStateOf("") }
    var eqExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedButton(onClick = { eqExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = s.synced && !s.busy) {
            Text(if (eqPreset.isBlank()) T("Scegli Preset EQ...", "Select EQ Preset...") else T("Preset EQ: $eqPreset", "EQ Preset: $eqPreset"))
        }
        DropdownMenu(expanded = eqExpanded, onDismissRequest = { eqExpanded = false }) {
            AmpedProtocol.eqPresets.keys.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    eqPreset = name
                    c.applyEqPreset(name)
                    eqExpanded = false
                })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(74) == 1, onCheckedChange = { c.setParameter(true, 74, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text(T("Bypass EQ", "Bypass EQ"), modifier = Modifier.padding(start = 4.dp))
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) { listOf("Low" to 70,"Low mids" to 73,"High mids" to 77,"High" to 81).forEach {(label,index)->
        if (s.cab.getOrNull(74) != 1) Parameter(label,s.cab[index],255,s.synced&&!s.busy,{"%+.1f dB".format(Locale.US,(it-128)*10f/127)}){c.setParameter(true,index,it)}
    } }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Livello Cassa", "Cabinet Level"), s.cab.getOrNull(AmpedProtocol.CAB_CABINET_LEVEL) ?: 0, 127, s.synced && !s.busy, format = { "%+.1f dB".format(java.util.Locale.US, AmpedProtocol.levelDb(it)) }) { c.setParameter(true, AmpedProtocol.CAB_CABINET_LEVEL, it) } }

    Section(T("Stanza (Room)", "Room"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(58) == 1, onCheckedChange = { c.setParameter(true, 58, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Solo", modifier = Modifier.padding(start = 4.dp, end = 16.dp))
        Checkbox(checked = s.cab.getOrNull(59) == 1, onCheckedChange = { c.setParameter(true, 59, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Mute", modifier = Modifier.padding(start = 4.dp))
    }
    Choices("Tipo di Stanza", s.cab.getOrNull(56) ?: 0, listOf("Small" to 0, "Small Damped" to 1, "Medium" to 2, "Medium Damped" to 3, "Large" to 4, "Large Damped" to 5), s.synced && !s.busy) { c.setParameter(true, 56, it) }
    Choices(T("Ampiezza Stereo", "Stereo Width"), s.cab.getOrNull(60) ?: 0, listOf("Mono" to 0, "Stereo" to 1, "Wide" to 2), s.synced && !s.busy) { c.setParameter(true, 60, it) }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Livello Room", "Room Level"), s.cab.getOrNull(AmpedProtocol.CAB_ROOM_LEVEL) ?: 0, 127, s.synced && !s.busy, format = { "%+.1f dB".format(java.util.Locale.US, AmpedProtocol.levelDb(it)) }) { c.setParameter(true, AmpedProtocol.CAB_ROOM_LEVEL, it) } }
    
    Section("Filtri (Cut)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_ON) == 1, onCheckedChange = { c.setParameter(true, AmpedProtocol.CAB_LOW_CUT_ON, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Low-Cut", modifier = Modifier.padding(start = 8.dp, end = 24.dp))
        Checkbox(checked = s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_ON) == 1, onCheckedChange = { c.setParameter(true, AmpedProtocol.CAB_HIGH_CUT_ON, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("High-Cut", modifier = Modifier.padding(start = 8.dp))
    }
    if (s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_ON) == 1) Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Frequenza Low-Cut", "Low-Cut Frequency"), s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_FREQ) ?: 0, 255, s.synced && !s.busy, format = { "~%.0f Hz".format(java.util.Locale.US, 20.0 * Math.pow(400.0/20.0, it / 255.0)) }) { c.setParameter(true, AmpedProtocol.CAB_LOW_CUT_FREQ, it) } }
    if (s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_ON) == 1) Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Frequenza High-Cut", "High-Cut Frequency"), s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_FREQ) ?: 0, 255, s.synced && !s.busy, format = { "~%.0f Hz".format(java.util.Locale.US, 2000.0 * Math.pow(20000.0/2000.0, it / 255.0)) }) { c.setParameter(true, AmpedProtocol.CAB_HIGH_CUT_FREQ, it) } }

    Section("Master CabRig")
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Volume Globale", "Master Level"), s.cab.getOrNull(AmpedProtocol.CAB_MASTER_LEVEL) ?: 0, 255, s.synced && !s.busy) { c.setParameter(true, AmpedProtocol.CAB_MASTER_LEVEL, it) } }

    Section(T("Salvataggio Rapido", "Quick Save"))
    var saveName by remember {mutableStateOf(s.cabNames[if (s.cabSlot > 0) s.cabSlot else 1] ?: "Mio CabRig")}
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text("Nome")},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveCabHardware(if (s.cabSlot > 0) s.cabSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text("Salva su Cab ${if (s.cabSlot > 0) s.cabSlot else 1}",color=Color.White)}
    }

    Text("Le modifiche sono live.",color=Muted,fontSize=12.sp)
}
@Composable private fun CabinetDrawing(name:String){
    val speakers=if(name.startsWith("4x"))4 else if(name.startsWith("2x"))2 else 1
    Canvas(Modifier.fillMaxWidth().height(168.dp).background(Panel,RoundedCornerShape(12.dp))){
        val w=if(speakers==1)110.dp.toPx() else 146.dp.toPx();val h=130.dp.toPx();val x=(size.width-w)/2;val y=(size.height-h)/2
        drawRoundRect(Color(0xff918985),Offset(x,y),Size(w,h),cornerRadius=androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),style=Stroke(2.dp.toPx()))
        for(i in 0 until speakers){val cols=if(speakers>1)2 else 1;val rows=if(speakers==4)2 else 1;val r=if(speakers==4)23.dp.toPx() else 26.dp.toPx();val cx=x+w*(i%cols+.5f)/cols;val cy=y+h*(i/cols+.5f)/rows
            drawCircle(Color(0xff6a6462),r,Offset(cx,cy),style=Stroke(2.dp.toPx()));drawCircle(Color(0xff494546),r*.68f,Offset(cx,cy),style=Stroke(1.dp.toPx()));drawCircle(Red,4.dp.toPx(),Offset(cx,cy))}
    }
}
@Composable private fun PresetsPage(s:AmpState,c:AmpedMidiController,presets:List<LocalPreset>,onExport:()->Unit,onImport:()->Unit){
    var saveDest by remember {mutableIntStateOf(0)} // 0=Phone, 1-3=Amp, 4-6=CabRig
    Heading("La tua libreria","Gestione preset")
    var name by remember {mutableStateOf("")}
    OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text("Nome del suono")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Choices("Destinazione di salvataggio", saveDest, listOf("Telefono" to 0, "Amp 1" to 1, "Amp 2" to 2, "Amp 3" to 3, "Cab 1" to 4, "Cab 2" to 5, "Cab 3" to 6), true) { saveDest = it }
    Button(onClick={
        if (saveDest == 0) c.saveLocal(name)
        else if (saveDest in 1..3) c.saveAmpHardware(saveDest, name)
        else if (saveDest in 4..6) c.saveCabHardware(saveDest - 3, name)
        name=""
    },enabled=s.synced&&!s.busy&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(if (saveDest==0) "Salva impostazioni sul telefono" else "Brucia nella memoria della pedaliera",color=Color.White)}
    
    if(presets.isEmpty())Text("La libreria locale è vuota.",Modifier.padding(vertical=20.dp),color=Muted)
    presets.forEach {p->
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold);Text(AmpedProtocol.cabinetNames.getOrNull(p.cab[0])?:"CabRig",fontSize=12.sp,color=Muted)};OutlinedButton(onClick={c.applyLocal(p)},enabled=s.synced&&!s.busy){Text("Carica")}}
    }
    Section("Memorie della pedaliera")
    for(i in 1..3)Text("AMP $i · ${s.ampNames[i]?:"Da leggere"}",color=Muted)
    for(i in 1..3)Text("CAB $i · ${s.cabNames[i]?:"Da leggere"}",color=Muted)
    
    Section("Backup e Ripristino")
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onClick=onExport,modifier=Modifier.weight(1f)){Text(T("Esporta", "Export"))};OutlinedButton(onClick=onImport,modifier=Modifier.weight(1f)){Text(T("Importa", "Import"))}}
}
@Composable private fun Choices(label:String,value:Int,options:List<Pair<String,Int>>,enabled:Boolean,onSelect:(Int)->Unit){
    Text(label.uppercase(),color=Muted,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=8.dp))
    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                row.forEach { (name, id) ->
                    val sel = value == id
                    Box(modifier = Modifier.weight(1f).aspectRatio(2.2f).background(Panel, RoundedCornerShape(8.dp)).clickable(enabled=enabled){onSelect(id)}, contentAlignment=Alignment.Center) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Canvas(Modifier.size(6.dp)) {
                                if(sel) { drawCircle(Red); drawCircle(Color.White.copy(alpha=0.6f), radius=1.5f.dp.toPx()) }
                                else { drawCircle(Color(0xFF222222)) }
                            }
                            Text(name.uppercase(), color=if(sel) Color.White else Muted, fontWeight=FontWeight.Bold, fontSize=13.sp, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                if(row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
@Composable private fun Parameter(label:String,value:Int,max:Int,enabled:Boolean,format:(Int)->String={"%.1f".format(java.util.Locale.US,it*10f/127)},onSet:(Int)->Unit){
    var dragged by remember(value){mutableFloatStateOf(value.coerceAtLeast(0).toFloat())}
    Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.fillMaxWidth().padding(vertical=12.dp, horizontal=4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Bottom){
            Text(label.uppercase(), color=Color.White, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.sp)
            Text(if(value<0)"—" else format(dragged.toInt()), color=if(enabled)Red else Muted, fontSize=20.sp, fontWeight=FontWeight.Black)
        }
        Box(contentAlignment=Alignment.Center, modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
            Canvas(modifier=Modifier.fillMaxWidth().height(24.dp).padding(horizontal=12.dp)) {
                drawLine(Color(0xFF000000), Offset(0f, size.height/2), Offset(size.width, size.height/2), 12.dp.toPx(), StrokeCap.Round)
                
                if (dragged > 0) {
                    val fillW = size.width * (dragged / max.toFloat())
                    if (label.equals("Gain", ignoreCase=true) || label.equals("Gain", ignoreCase=true)) {
                        val heat = Brush.horizontalGradient(
                            0.0f to Color(0xFF333333),
                            0.33f to Color(0xFFFFD700),
                            0.66f to Color(0xFFFF6600),
                            1.0f to Color(0xFFFF0000),
                            startX = 0f, endX = size.width
                        )
                        drawLine(heat, Offset(0f, size.height/2), Offset(fillW, size.height/2), 12.dp.toPx(), StrokeCap.Round)
                        drawLine(heat, Offset(0f, size.height/2), Offset(fillW, size.height/2), 24.dp.toPx(), StrokeCap.Round, alpha=0.4f)
                    } else {
                        drawLine(Red, Offset(0f, size.height/2), Offset(fillW, size.height/2), 12.dp.toPx(), StrokeCap.Round)
                    }
                }
                
                val steps = 10
                val stepWidth = size.width / steps
                for (i in 0..steps) {
                    val x = i * stepWidth
                    val h = if (i == 0 || i == steps || i == steps/2) 8.dp.toPx() else 4.dp.toPx()
                    drawLine(Color(0xFF333333), Offset(x, size.height/2 - h), Offset(x, size.height/2 + h), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            Slider(
                value=dragged,
                onValueChange={dragged=it},
                onValueChangeFinished={onSet(dragged.toInt())},
                enabled=enabled&&value>=0,
                valueRange=0f..max.toFloat(),
                colors=SliderDefaults.colors(thumbColor=Color.White, activeTrackColor=Color.Transparent, inactiveTrackColor=Color.Transparent, disabledActiveTrackColor=Color.Transparent, disabledInactiveTrackColor=Color.Transparent),
                modifier=Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable private fun SegmentedButton(options: List<Pair<String,String>>, selected: String, onSelect: (String) -> Unit) {
    Row(modifier=Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(4.dp)) {
        options.forEach { (label, value) ->
            val sel = selected == value
            Box(modifier=Modifier.background(if(sel) Red else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onSelect(value) }.padding(horizontal=12.dp, vertical=6.dp)) {
                Text(label, color=if(sel) Color.White else Muted, fontWeight=FontWeight.Bold, fontSize=13.sp)
            }
        }
    }
}
