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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
    private val importIr=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null) runCatching {
            val bytes=contentResolver.openInputStream(uri)!!.use { stream ->
                val limit=16*1024*1024
                val data=stream.readBytes()
                require(data.size<=limit) {"File troppo grande"}
                data
            }
            val name=uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "impulse.wav"
            controller.convertIr(bytes,name)
        }.onFailure { notice=it.message ?: "Import fallito" }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        controller=AmpedMidiController(applicationContext)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Red,onPrimary=Coal,background=Coal,surface=Coal,surfaceVariant=Panel,onSurface=Paper,onSurfaceVariant=Muted,secondary=Red)) {
                AmpedApp(controller,notice,{export.launch("Amped3-preset-e-backup.json")},{import.launch(arrayOf("application/json","text/plain","text/xml","application/xml","*/*"))},{importIr.launch(arrayOf("audio/wav","audio/x-wav","application/octet-stream","*/*"))})
            }
        }
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debug && intent?.getBooleanExtra("demo", false) == true) controller.enableDemo() else controller.connectToAmp()
        // debug-only hook so the conversion path can be exercised without driving the file picker:
        //   adb shell am start ... --ez demo true --es ir /sdcard/Download/some.wav
        if (debug) intent?.getStringExtra("ir")?.let { path ->
            runCatching { java.io.File(path).readBytes() }
                .onSuccess { controller.convertIr(it, java.io.File(path).name) }
                .onFailure { notice = "IR non leggibile: ${it.message}" }
        }
    }
    override fun onDestroy(){controller.close();super.onDestroy()}
}

var lang by mutableStateOf("en")
fun T(it: String, en: String) = if (lang == "it") it else en

/** Accensione: il marchio resta dov'era nello splash di sistema e sotto si accende un filamento,
 *  come una valvola che scalda. Dura meno di un secondo e non blocca nulla: sotto l'app e' gia' viva. */
@Composable private fun Accensione(onFine:()->Unit){
    val avanzamento = remember { Animatable(0f) }
    val uscita = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        avanzamento.animateTo(1f, tween(760, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
        delay(90)
        uscita.animateTo(0f, tween(260))
        onFine()
    }
    val a = avanzamento.value
    Box(Modifier.fillMaxSize().background(Coal).alpha(uscita.value), contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.blackstar_logo),
                contentDescription = "Surveyor",
                modifier = Modifier.height(54.dp).alpha((a*2.2f).coerceAtMost(1f)).scale(0.96f + 0.04f*a),
                contentScale = ContentScale.Fit
            )
            Canvas(Modifier.padding(top=22.dp).width(220.dp).height(14.dp)) {
                val cy = size.height/2
                val meta = size.width/2 * a
                if (meta > 1f) {
                    val tinta = androidx.compose.ui.graphics.lerp(Color(0xFFFFC21A), Color(0xFFE01500), a)
                    drawLine(tinta.copy(alpha=0.18f*a), Offset(size.width/2-meta, cy), Offset(size.width/2+meta, cy), 10.dp.toPx(), StrokeCap.Round)
                    drawLine(tinta, Offset(size.width/2-meta, cy), Offset(size.width/2+meta, cy), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable fun AmpedApp(c:AmpedMidiController,notice:String,onExport:()->Unit,onImport:()->Unit,onImportIr:()->Unit){
    val s by c.state.collectAsState()
    val presets by c.presets.collectAsState()
    var tab by remember {mutableIntStateOf(0)}
    val titles=listOf(T("Amp","Amp"),T("CabRig","CabRig"),T("Preset","Preset"),T("Impostazioni","Settings"))
    var accensione by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Coal,
            bottomBar = {
            NavigationBar(containerColor = Panel) {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { NavGlyph(i, tab == i) },
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
                    1->CabPage(s,c,onImportIr)
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
    if (accensione) Accensione { accensione = false }
    }
}
@Composable private fun Heading(title:String,subtitle:String){Column(Modifier.padding(top=10.dp,bottom=14.dp)){Text(subtitle.uppercase(),fontSize=10.sp,color=Red,letterSpacing=1.6.sp);Text(title,fontSize=28.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun Section(title:String){
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title.uppercase(),color=Paper,fontSize=12.sp,fontWeight=FontWeight.Black,letterSpacing=2.2.sp)
        HorizontalDivider(Modifier.padding(start=12.dp),color=Color(0xFF2E2E31))
    }
}
/** Glifi della navigazione: manopola, cassa, lista, fader. Disegnati invece di importare un set
 *  di icone, cosi' restano nello stesso vocabolario grafico dei controlli. */
@Composable private fun NavGlyph(index:Int, selected:Boolean){
    val tinta = if(selected) Red else Muted
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension; val w = 2.dp.toPx()
        when(index){
            0 -> {
                drawCircle(tinta, radius=s*0.38f, style=Stroke(w))
                drawLine(tinta, Offset(s/2, s/2), Offset(s*0.5f-s*0.2f, s*0.5f-s*0.22f), w, StrokeCap.Round)
            }
            1 -> {
                drawRoundRect(tinta, Offset(s*0.16f, s*0.08f), Size(s*0.68f, s*0.84f),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style=Stroke(w))
                drawCircle(tinta, radius=s*0.15f, center=Offset(s*0.5f, s*0.3f), style=Stroke(w))
                drawCircle(tinta, radius=s*0.15f, center=Offset(s*0.5f, s*0.7f), style=Stroke(w))
            }
            2 -> {
                listOf(0.22f, 0.5f, 0.78f).forEachIndexed { i, y ->
                    val larghezza = if (i == 0) s*0.46f else s*0.68f
                    drawLine(tinta, Offset(s*0.16f, s*y), Offset(s*0.16f+larghezza, s*y), w, StrokeCap.Round)
                }
            }
            else -> {
                listOf(0.28f to 0.62f, 0.5f to 0.34f, 0.72f to 0.5f).forEach { (y, x) ->
                    drawLine(tinta.copy(alpha=0.55f), Offset(s*0.14f, s*y), Offset(s*0.86f, s*y), w, StrokeCap.Round)
                    drawLine(tinta, Offset(s*x, s*y-s*0.09f), Offset(s*x, s*y+s*0.09f), 3.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
@Composable private fun AmpPage(s:AmpState,c:AmpedMidiController){
    Heading(s.ampNames[s.ampSlot] ?: T("Il tuo amplificatore", "Your Amplifier"),if(s.ampSlot>0) T("Slot ${s.ampSlot} · impostazioni attuali", "Slot ${s.ampSlot} · current settings") else T("Impostazioni attuali", "Current settings"))
    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
            listOf("Clean","Crunch","Overdrive").forEachIndexed { i, name ->
                    val sel = s.ampSlot == i+1
                    Box(modifier = Modifier.weight(1f).height(76.dp)
                        .background(if(sel) Color(0xFF2B1A17) else Panel, RoundedCornerShape(10.dp))
                        .clickable(enabled=s.synced&&!s.busy){c.recallAmp(i+1)}, contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Canvas(Modifier.size(10.dp)) {
                                if(sel) { drawCircle(Red); drawCircle(Color(0xFFFFC9B0).copy(alpha=0.75f), radius=2.5.dp.toPx()) }
                                else { drawCircle(Color(0xFF232326)) }
                            }
                            Text(name.uppercase(), color=if(sel) Paper else Muted, fontWeight=FontWeight.Black, fontSize=14.sp, letterSpacing=0.6.sp)
                        }
                    }
            }
        }
    }
    if(!s.synced)Text(T("In attesa dei valori reali della pedaliera.", "Waiting for live values from the pedal."),color=Muted,fontSize=13.sp)
    Parameter("Gain",s.amp[0],127,s.synced&&!s.busy,{"%.1f".format(Locale.US,it*10f/127)},heat=true){c.setParameter(false,0,it)}
    Parameter(T("Volume preamp","Preamp Volume"),s.amp[1],127,s.synced&&!s.busy,{"%.1f".format(Locale.US,it*10f/127)}){c.setParameter(false,1,it)}
    Section(T("Equalizzazione", "Equalization"))
    listOf("Bass" to 4,"Middle" to 5,"Treble" to 6,"ISF" to 7,"Presence" to 8).forEach {(label,index)->Parameter(label,s.amp[index],127,s.synced&&!s.busy,{"%.1f".format(Locale.US,it*10f/127)}){c.setParameter(false,index,it)}}
    Section(T("Carattere", "Character"))
    Choices(T("Risposta", "Response"),s.amp[22],listOf("EL84" to 2,"EL34" to 3,"6L6" to 1),s.synced&&!s.busy){c.setParameter(false,22,it)}
    Choices(T("Potenza", "Power"),s.amp[AmpedProtocol.AMP_POWER],AmpedProtocol.powerOptions,s.synced&&!s.busy){c.setParameter(false,AmpedProtocol.AMP_POWER,it)}
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
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text(T("Nome", "Name"))},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveAmpHardware(if (s.ampSlot > 0) s.ampSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(T("Salva su Slot ", "Save to slot ") + "${if (s.ampSlot > 0) s.ampSlot else 1}",color=Color.White)}
    }
}
@Composable private fun CabPage(s:AmpState,c:AmpedMidiController,onImportIr:()->Unit={}){
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
    var cabinet by remember(s.cab[0]) { mutableIntStateOf(if (s.cab[0] in AmpedProtocol.cabinetNames.indices) s.cab[0] else 0) }
    var cabinetExpanded by remember { mutableStateOf(false) }
    var mic by remember(s.cab[1]){mutableIntStateOf(if(s.cab[1] in 0..5) s.cab[1] else 0)}
    var axis by remember(s.cab[2]){mutableIntStateOf(if(s.cab[2]==1) 1 else 0)}
    CabinetDrawing(AmpedProtocol.cabinetNames.getOrNull(cabinet) ?: name,
        applied = cabinet == s.cab[0] && mic == s.cab[1] && axis == s.cab[2])
    Text(T("Libreria DSP completa · 24 casse × 6 microfoni × 2 assi", "Complete DSP library · 24 cabinets × 6 mics × 2 axes"),color=Muted,fontSize=12.sp)
    Text(T("Cassa", "Cabinet").uppercase(), color=Muted, fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.4.sp)
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
    Choices(T("Asse", "Axis"),axis,listOf("On Axis" to 0,"Off Axis" to 1),!s.busy){axis=it}
    Text(T("Selezionato: ", "Selected: ") + "${AmpedProtocol.cabinetNames[cabinet]} · ${listOf("57 Dyn","421 Dyn","67 Cond","414 Cond","121 Rib","160 Rib")[mic]} · ${if(axis==0)"On Axis" else "Off Axis"}",color=Muted,fontSize=12.sp)
    Text(T("Caricato: ", "Loaded: ") + "$name · ${listOf("57 Dyn","421 Dyn","67 Cond","414 Cond","121 Rib","160 Rib").getOrNull(s.cab[1])?:"—"} · ${if(s.cab[2]==0)"On Axis" else if(s.cab[2]==1)"Off Axis" else "—"}",color=Muted,fontSize=12.sp)
    Button(onClick={c.chooseCab(cabinet,mic,axis)},enabled=s.synced&&!s.busy,modifier=Modifier.fillMaxWidth()){Text(T("Applica profilo DSP", "Load DSP profile"))}
    if (!s.synced) Text(T("Collega AMPED 3 via USB OTG e premi Connetti per applicare il profilo scelto.", "Connect the AMPED 3 over USB OTG and tap Connect to load the chosen profile."), color=Muted, fontSize=12.sp)
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
        if (s.cab.getOrNull(74) != 1) Parameter(label,s.cab[index],255,s.synced&&!s.busy,{"%+.1f dB".format(Locale.US,AmpedProtocol.eqDb(it))},bipolar=true){c.setParameter(true,index,it)}
    } }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Livello Cassa", "Cabinet Level"), s.cab.getOrNull(AmpedProtocol.CAB_CABINET_LEVEL) ?: 0, 127, s.synced && !s.busy, format = { "%+.1f dB".format(java.util.Locale.US, AmpedProtocol.levelDb(it)) }, bipolar = true) { c.setParameter(true, AmpedProtocol.CAB_CABINET_LEVEL, it) } }

    Section(T("Stanza (Room)", "Room"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(58) == 1, onCheckedChange = { c.setParameter(true, 58, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Solo", modifier = Modifier.padding(start = 4.dp, end = 16.dp))
        Checkbox(checked = s.cab.getOrNull(59) == 1, onCheckedChange = { c.setParameter(true, 59, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Mute", modifier = Modifier.padding(start = 4.dp))
    }
    Choices(T("Tipo di stanza", "Room type"), s.cab.getOrNull(56) ?: 0, listOf("Small" to 0, "Small Damped" to 1, "Medium" to 2, "Medium Damped" to 3, "Large" to 4, "Large Damped" to 5), s.synced && !s.busy) { c.setParameter(true, 56, it) }
    Choices(T("Ampiezza Stereo", "Stereo Width"), s.cab.getOrNull(60) ?: 0, listOf("Mono" to 0, "Stereo" to 1, "Wide" to 2), s.synced && !s.busy) { c.setParameter(true, 60, it) }
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Livello Room", "Room Level"), s.cab.getOrNull(AmpedProtocol.CAB_ROOM_LEVEL) ?: 0, 127, s.synced && !s.busy, format = { "%+.1f dB".format(java.util.Locale.US, AmpedProtocol.levelDb(it)) }, bipolar = true) { c.setParameter(true, AmpedProtocol.CAB_ROOM_LEVEL, it) } }
    
    Section(T("Filtri", "Cut filters"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_ON) == 1, onCheckedChange = { c.setParameter(true, AmpedProtocol.CAB_LOW_CUT_ON, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("Low-Cut", modifier = Modifier.padding(start = 8.dp, end = 24.dp))
        Checkbox(checked = s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_ON) == 1, onCheckedChange = { c.setParameter(true, AmpedProtocol.CAB_HIGH_CUT_ON, if (it) 1 else 0) }, enabled = s.synced && !s.busy)
        Text("High-Cut", modifier = Modifier.padding(start = 8.dp))
    }
    if (s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_ON) == 1) Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Frequenza Low-Cut", "Low-Cut Frequency"), s.cab.getOrNull(AmpedProtocol.CAB_LOW_CUT_FREQ) ?: 0, 255, s.synced && !s.busy, format = { "~%.0f Hz".format(java.util.Locale.US, 20.0 * Math.pow(400.0/20.0, it / 255.0)) }) { c.setParameter(true, AmpedProtocol.CAB_LOW_CUT_FREQ, it) } }
    if (s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_ON) == 1) Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Frequenza High-Cut", "High-Cut Frequency"), s.cab.getOrNull(AmpedProtocol.CAB_HIGH_CUT_FREQ) ?: 0, 255, s.synced && !s.busy, format = { "~%.0f Hz".format(java.util.Locale.US, 2000.0 * Math.pow(20000.0/2000.0, it / 255.0)) }) { c.setParameter(true, AmpedProtocol.CAB_HIGH_CUT_FREQ, it) } }

    Section(T("Uscita CabRig", "CabRig output"))
    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { Parameter(T("Volume Globale", "Master Level"), s.cab.getOrNull(AmpedProtocol.CAB_MASTER_LEVEL) ?: 0, 255, s.synced && !s.busy) { c.setParameter(true, AmpedProtocol.CAB_MASTER_LEVEL, it) } }

    Section(T("Salvataggio Rapido", "Quick Save"))
    var saveName by remember {mutableStateOf(s.cabNames[if (s.cabSlot > 0) s.cabSlot else 1] ?: "Mio CabRig")}
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text(T("Nome", "Name"))},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveCabHardware(if (s.cabSlot > 0) s.cabSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(T("Salva su Cab ", "Save to cab ") + "${if (s.cabSlot > 0) s.cabSlot else 1}",color=Color.White)}
    }

    Text(T("Le modifiche sono immediate.", "Changes take effect immediately."),color=Muted,fontSize=12.sp)
    CustomProfileSection(s,c,onImportIr)
}

/**
 * Converting an impulse response into a cabinet profile, and listening to it safely.
 *
 * Loading coefficients only writes the live DSP, never the stored slots, and the fit keeps the
 * pole bank of a factory cabinet, so a converted profile is as stable as one Blackstar ships.
 * What is not guaranteed is how it sounds, which is why nothing is sent until the checks pass and
 * why the first listen happens with the cabinet level at its minimum.
 */
@Composable private fun CustomProfileSection(s:AmpState,c:AmpedMidiController,onImportIr:()->Unit){
    val conversion by c.conversion.collectAsState()
    val saved by c.customProfiles.collectAsState()
    var name by remember { mutableStateOf("") }
    Section(T("Profili da risposta all'impulso", "Profiles from an impulse response"))
    Text(T("Sperimentale. La conversione tiene i poli della cassa caricata, quindi la stabilita' e' quella di un profilo di fabbrica. Il suono invece non e' garantito: nulla viene inviato se i controlli non passano.",
           "Experimental. The fit keeps the poles of the loaded cabinet, so stability is that of a factory profile. The sound is not guaranteed: nothing is sent unless the checks pass."),
        color=Muted,fontSize=12.sp)
    if (s.customLoaded) {
        Box(Modifier.fillMaxWidth().padding(vertical=10.dp).background(Color(0xFF3A1512),RoundedCornerShape(10.dp)).padding(14.dp)) {
            Column {
                Text(T("In prova: ${s.customName}", "Auditioning: ${s.customName}"),color=Paper,fontWeight=FontWeight.Black)
                Text(T("Livello cassa al minimo. La cassa di fabbrica e' salvata e torna con un tocco.",
                       "Cabinet level at minimum. The factory cabinet is saved and one tap brings it back."),color=Muted,fontSize=12.sp)
                Button(onClick={c.revertAudition()},enabled=!s.busy,modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                    Text(T("Ripristina la cassa di fabbrica", "Restore the factory cabinet"))
                }
            }
        }
    }
    OutlinedButton(onClick=onImportIr,enabled=!s.busy,modifier=Modifier.fillMaxWidth()) {
        Text(T("Converti un file WAV", "Convert a WAV file"))
    }
    conversion?.let { k ->
        val metrics = k.verdict.metrics
        Column(Modifier.fillMaxWidth().padding(top=12.dp).background(Panel,RoundedCornerShape(10.dp)).padding(14.dp)) {
            Text(k.source,color=Paper,fontWeight=FontWeight.Bold,fontSize=14.sp)
            Text(T("Scostamento dalla risposta d'origine: %.2f dB".format(Locale.US,k.errorDb),
                   "Deviation from the source response: %.2f dB".format(Locale.US,k.errorDb)),color=Muted,fontSize=12.sp)
            Text(T("Banco di poli: ${k.templateKey}", "Pole bank: ${k.templateKey}"),color=Muted,fontSize=12.sp)
            if (k.attenuatedDb < -0.05) Text(
                T("Attenuato di %.1f dB per rientrare nei limiti".format(Locale.US,k.attenuatedDb),
                  "Attenuated by %.1f dB to stay within limits".format(Locale.US,k.attenuatedDb)),color=Muted,fontSize=12.sp)
            Text(if (k.verdict.passed) T("Controlli superati", "Checks passed") else k.verdict.summary,
                color=if (k.verdict.passed) Color(0xFF7BD88F) else Red,fontSize=13.sp,fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(top=6.dp))
            metrics?.let {
                Text(T("picco %.1f dB · 20 Hz %.0f dB · coda %.0f ms".format(Locale.US,it.peakDb,it.hz20Db,it.t60*1000),
                       "peak %.1f dB · 20 Hz %.0f dB · tail %.0f ms".format(Locale.US,it.peakDb,it.hz20Db,it.t60*1000)),
                    color=Muted,fontSize=11.sp)
            }
            ResponseCurve(k.header,k.chunks,k.templateHeader,k.templateChunks)
            OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text(T("Nome", "Name"))},
                singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={c.saveConversion(name);name=""},enabled=name.isNotBlank(),modifier=Modifier.weight(1f)) {
                    Text(T("Salva", "Save"))
                }
                OutlinedButton(onClick={c.discardConversion()},modifier=Modifier.weight(1f)) {
                    Text(T("Scarta", "Discard"))
                }
            }
        }
    }
    saved.forEach { profile ->
        Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name,color=Paper,fontWeight=FontWeight.Bold)
                Text("${profile.source} · %.2f dB".format(Locale.US,profile.errorDb),color=Muted,fontSize=11.sp)
            }
            TextButton(onClick={c.audition(profile)},enabled=s.synced&&!s.busy){Text(T("Prova", "Audition"))}
            TextButton(onClick={c.deleteCustom(profile.id)}){Text(T("Elimina", "Delete"),color=Muted)}
        }
    }
}

/**
 * The converted profile in red over the cabinet it was fitted on in grey, 40 Hz to 16 kHz on a log
 * axis. It is the modelled response, from the coefficients: no sweep has ever been measured at the
 * pedal's output, so read it as what the maths says, not as what the speaker does.
 */
@Composable private fun ResponseCurve(header:String,chunks:List<String>,baseHeader:String?,baseChunks:List<String>?){
    val values = remember(header) { com.example.amped3controller.dsp.CabProfile.decode(header,chunks) }
    val base = remember(baseHeader) {
        if (baseHeader != null && baseChunks != null) com.example.amped3controller.dsp.CabProfile.decode(baseHeader,baseChunks) else null
    }
    if (values == null) return
    val points = 200
    val lo = kotlin.math.ln(40.0); val hi = kotlin.math.ln(16000.0)
    fun curve(v: FloatArray) = DoubleArray(points) { i ->
        val hz = kotlin.math.exp(lo + (hi - lo) * i / (points - 1.0))
        com.example.amped3controller.dsp.CabProfile.db(
            com.example.amped3controller.dsp.CabProfile.magnitudeAtHz(v, hz))
    }
    val db = curve(values)
    val dbBase = base?.let { curve(it) }
    Column(Modifier.fillMaxWidth().padding(top=12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val top = (maxOf(db.max(), dbBase?.max() ?: -99.0) + 4).coerceAtLeast(6.0)
            val bottom = top - 48
            fun y(v: Double) = (size.height * (top - v) / (top - bottom)).toFloat().coerceIn(0f, size.height)
            for (g in 1..3) {
                val yy = size.height * g / 4f
                drawLine(Color(0xFF2A2A2D), Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx())
            }
            // decade marks at 100 Hz, 1 kHz, 10 kHz so the eye has somewhere to stand
            listOf(100.0, 1000.0, 10000.0).forEach { hz ->
                val x = (size.width * (kotlin.math.ln(hz) - lo) / (hi - lo)).toFloat()
                drawLine(Color(0xFF2A2A2D), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            fun trace(data: DoubleArray, colour: Color, width: Float) {
                var previous = Offset(0f, y(data[0]))
                for (i in 1 until points) {
                    val point = Offset(size.width * i / (points - 1f), y(data[i]))
                    drawLine(colour, previous, point, width, StrokeCap.Round)
                    previous = point
                }
            }
            dbBase?.let { trace(it, Color(0xFF6E6A67), 1.5.dp.toPx()) }
            trace(db, Red, 2.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("40 Hz",color=Muted,fontSize=10.sp)
            Text("100 Hz · 1 kHz · 10 kHz",color=Color(0xFF3A3A3D),fontSize=10.sp)
            Text("16 kHz",color=Muted,fontSize=10.sp)
        }
        Text(T("rosso: convertito · grigio: cassa di partenza · risposta modellata, non misurata",
               "red: converted · grey: source cabinet · modelled response, not measured"),
            color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=2.dp))
    }
}
/** Silhouette della cassa scelta. Coni e pollici si leggono dal nome ("4x12 Classic UK"),
 *  cosi' una 2x12 non viene disegnata come una 4x12. La voce DI non ha cassa: si disegna la presa. */
@Composable private fun CabinetDrawing(name:String, applied:Boolean){
    val misure = Regex("^(\\d+)x(\\d+)").find(name)
    val coni = misure?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val pollici = misure?.groupValues?.get(2)?.toIntOrNull() ?: 12
    val combo = name.contains("Combo", true)
    val telaio = if (applied) Color(0xff9a918c) else Color(0xff57524f)
    val cono = if (applied) Color(0xff6a6462) else Color(0xff413d3b)
    Box(Modifier.fillMaxWidth().height(168.dp).background(Panel, RoundedCornerShape(12.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            if (coni == 0) { // DI: nessuna cassa, segnale diretto
                val l = 58.dp.toPx()
                val cx = size.width/2; val cy = size.height/2
                drawRoundRect(telaio, Offset(cx-l/2, cy-l/2), Size(l, l),
                    androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style=Stroke(2.dp.toPx()))
                drawCircle(telaio, 13.dp.toPx(), Offset(cx, cy), style=Stroke(2.dp.toPx()))
                drawCircle(if (applied) Red else cono, 5.dp.toPx(), Offset(cx, cy))
                drawLine(telaio, Offset(cx+l/2, cy), Offset(cx+l/2+22.dp.toPx(), cy), 3.dp.toPx(), StrokeCap.Round)
                return@Canvas
            }
            val colonne = if (coni >= 2) 2 else 1
            val righe = if (coni >= 4) 2 else 1
            // un 10 pollici e' visibilmente piu' piccolo di un 12: la differenza si deve vedere
            val raggio = (if (coni >= 4) 22.dp else 27.dp).toPx() * (if (pollici <= 10) 0.84f else 1f)
            val w = (raggio*2 + 14.dp.toPx()) * colonne
            val h = (raggio*2 + 14.dp.toPx()) * righe + (if (combo) 16.dp.toPx() else 0f)
            val x = (size.width - w)/2; val y = (size.height - h)/2
            drawRoundRect(telaio, Offset(x, y), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style=Stroke(2.dp.toPx()))
            if (combo) drawLine(telaio.copy(alpha=0.5f), Offset(x+8.dp.toPx(), y+h-12.dp.toPx()),
                Offset(x+w-8.dp.toPx(), y+h-12.dp.toPx()), 1.5.dp.toPx())
            for (i in 0 until coni) {
                val cx = x + w*(i%colonne + .5f)/colonne
                val cy = y + (h - (if (combo) 16.dp.toPx() else 0f))*(i/colonne + .5f)/righe
                drawCircle(cono, raggio, Offset(cx, cy), style=Stroke(2.dp.toPx()))
                drawCircle(cono.copy(alpha=0.75f), raggio*.66f, Offset(cx, cy), style=Stroke(1.dp.toPx()))
                drawCircle(if (applied) Red else cono, 4.dp.toPx(), Offset(cx, cy))
            }
        }
        Text(
            if (coni == 0) "DI" else "${coni}x${pollici}",
            Modifier.align(Alignment.TopStart).padding(14.dp),
            color = if (applied) Paper else Muted, fontSize = 13.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp
        )
        if (!applied) Text(
            T("DA APPLICARE", "NOT LOADED"),
            Modifier.align(Alignment.TopEnd).padding(14.dp),
            color = Red, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp
        )
    }
}
@Composable private fun PresetsPage(s:AmpState,c:AmpedMidiController,presets:List<LocalPreset>,onExport:()->Unit,onImport:()->Unit){
    var saveDest by remember {mutableIntStateOf(0)} // 0=Phone, 1-3=Amp, 4-6=CabRig
    Heading(T("La tua libreria", "Your library"), T("Gestione preset", "Preset management"))
    var name by remember {mutableStateOf("")}
    OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text(T("Nome del suono", "Sound name"))},singleLine=true,modifier=Modifier.fillMaxWidth())
    Choices(T("Destinazione di salvataggio", "Save destination"), saveDest, listOf(T("Telefono", "Phone") to 0, "Amp 1" to 1, "Amp 2" to 2, "Amp 3" to 3, "Cab 1" to 4, "Cab 2" to 5, "Cab 3" to 6), true) { saveDest = it }
    Button(onClick={
        if (saveDest == 0) c.saveLocal(name)
        else if (saveDest in 1..3) c.saveAmpHardware(saveDest, name)
        else if (saveDest in 4..6) c.saveCabHardware(saveDest - 3, name)
        name=""
    },enabled=s.synced&&!s.busy&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(if (saveDest==0) "Salva impostazioni sul telefono" else "Brucia nella memoria della pedaliera",color=Color.White)}
    
    if(presets.isEmpty())Text(T("La libreria locale è vuota.", "Your library is empty."),Modifier.padding(vertical=20.dp),color=Muted)
    presets.forEach {p->
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold);Text(AmpedProtocol.cabinetNames.getOrNull(p.cab[0])?:"CabRig",fontSize=12.sp,color=Muted)};OutlinedButton(onClick={c.applyLocal(p)},enabled=s.synced&&!s.busy){Text(T("Carica", "Load"))}}
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
@Composable private fun Parameter(
    label:String, value:Int, max:Int, enabled:Boolean,
    format:(Int)->String={"%.1f".format(java.util.Locale.US,it*10f/127)},
    heat:Boolean=false,
    bipolar:Boolean=false,
    onSet:(Int)->Unit
){
    var dragged by remember(value){mutableFloatStateOf(value.coerceAtLeast(0).toFloat())}
    val frazione = if (max > 0) (dragged / max.toFloat()).coerceIn(0f, 1f) else 0f
    val attivo = enabled && value >= 0
    val testo = if (value < 0) "—" else format(dragged.toInt())
    // Il numero e la parte letterale vanno separati: l'unita' non deve rubare corpo alla cifra,
    // che e' cio' che si legge da lontano.
    val taglio = testo.indexOfFirst { it.isLetter() && it != 'e' }
    val cifra = if (taglio > 0) testo.substring(0, taglio).trim() else testo
    val unita = if (taglio > 0) testo.substring(taglio).trim() else ""

    Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.Bottom) {
            Text(
                label.uppercase(), Modifier.weight(1f).padding(bottom=6.dp),
                color=if(attivo) Muted else Muted.copy(alpha=0.45f),
                fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.8.sp
            )
            Text(
                cifra,
                color=if(attivo) Paper else Muted.copy(alpha=0.45f),
                fontSize=40.sp, fontWeight=FontWeight.Black, letterSpacing=(-1).sp,
                // cifre a passo fisso: il numero non balla mentre si trascina
                style=LocalTextStyle.current.copy(fontFeatureSettings="tnum")
            )
            if (unita.isNotEmpty()) Text(
                unita, Modifier.padding(start=3.dp, bottom=7.dp),
                color=if(attivo) Muted else Muted.copy(alpha=0.45f),
                fontSize=15.sp, fontWeight=FontWeight.Bold
            )
        }
        Box(contentAlignment=Alignment.CenterStart, modifier=Modifier.fillMaxWidth().height(44.dp)) {
            Canvas(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=11.dp)) {
                val cy = size.height/2
                val h = 18.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                // incavo della traccia
                drawRoundRect(Color(0xFF0C0C0D), Offset(0f, cy-h/2), Size(size.width, h), r)
                drawRoundRect(Color(0xFF000000), Offset(0f, cy-h/2), Size(size.width, h), r,
                    style=Stroke(1.dp.toPx()))

                val larghezza = size.width * frazione
                if (larghezza > 1f && attivo) {
                    if (heat) {
                        // Valvola: piu' si alza il guadagno, piu' il filamento passa
                        // dall'ambra spenta all'arancio incandescente. Il colore dipende dal
                        // valore, non dalla posizione: a guadagno basso non deve sembrare caldo.
                        // Giallo a guadagno basso, rosso incandescente quando sale: e' cosi' che si
                        // vede scaldare una valvola sotto sforzo.
                        val caldo = frazione * frazione
                        val punta = androidx.compose.ui.graphics.lerp(Color(0xFFFFC21A), Color(0xFFE01500), frazione)
                        val brace = androidx.compose.ui.graphics.lerp(Color(0xFF6A4A00), Color(0xFF7A0A00), frazione)
                        drawRoundRect(Brush.horizontalGradient(listOf(brace, punta), endX=larghezza),
                            Offset(0f, cy-h/2), Size(larghezza, h), r)
                        // alone: cresce con il calore, resta sotto la soglia del fastidio
                        drawRoundRect(punta.copy(alpha=0.10f + 0.22f*caldo),
                            Offset(-4.dp.toPx(), cy-h/2-4.dp.toPx()),
                            Size(larghezza+8.dp.toPx(), h+8.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
                        // filamento
                        drawLine(punta.copy(alpha=0.35f+0.5f*caldo), Offset(2.dp.toPx(), cy), Offset(larghezza-2.dp.toPx(), cy), 2.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8E0000), Red), endX=larghezza),
                            Offset(0f, cy-h/2), Size(larghezza, h), r)
                    }
                }
                // tacche sotto la traccia, con quella centrale piu' marcata sui parametri bipolari
                val passi = 10
                for (i in 0..passi) {
                    val x = (i * size.width / passi).coerceIn(1f, size.width-1f)
                    val centrale = bipolar && i == passi/2
                    val estremo = i == 0 || i == passi
                    drawLine(
                        if (centrale) Muted.copy(alpha=0.9f) else Color(0xFF3A3A3D),
                        Offset(x, cy + h/2 + 4.dp.toPx()),
                        Offset(x, cy + h/2 + (if (centrale || estremo) 9.dp else 6.dp).toPx()),
                        (if (centrale) 2.dp else 1.5.dp).toPx(), StrokeCap.Round
                    )
                }
                if (attivo) {
                    // cappuccio da fader: si vede da lontano molto piu' di un pallino
                    val x = larghezza.coerceIn(0f, size.width)
                    val cw = 9.dp.toPx(); val ch = 30.dp.toPx()
                    drawRoundRect(Color(0xFF000000).copy(alpha=0.5f), Offset(x-cw/2+1.5f, cy-ch/2+2f), Size(cw, ch),
                        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawRoundRect(Paper, Offset(x-cw/2, cy-ch/2), Size(cw, ch),
                        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawLine(Color(0xFF151516), Offset(x, cy-ch/2+5.dp.toPx()), Offset(x, cy+ch/2-5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            // Solo trascinamento: un cursore che salta al punto toccato, su un telefono
            // appoggiato al pedaliera, manda il guadagno al massimo con una sfiorata.
            Box(Modifier.fillMaxWidth().height(44.dp)
                .semantics {
                    contentDescription = label
                    if (attivo) progressBarRangeInfo = ProgressBarRangeInfo(dragged, 0f..max.toFloat())
                }
                .pointerInput(attivo, max) {
                    if (!attivo) return@pointerInput
                    val larghezzaPx = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragEnd = { onSet(dragged.toInt()) },
                        onDragCancel = { onSet(dragged.toInt()) }
                    ) { change, delta ->
                        change.consume()
                        if (larghezzaPx > 0f)
                            dragged = (dragged + delta * max / larghezzaPx).coerceIn(0f, max.toFloat())
                    }
                })
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
