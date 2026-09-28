package com.example.amped3controller

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Accensione: il marchio resta dov'era nello splash di sistema e sotto si accende un filamento,
 *  come una valvola che scalda. Dura meno di un secondo e non blocca nulla: sotto l'app e' gia' viva. */
@Composable internal fun Accensione(onFine:()->Unit){
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
                painter = painterResource(R.drawable.surveyor_logo),
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
                    Image(painter = painterResource(R.drawable.surveyor_logo), contentDescription = "Blackstar", modifier = Modifier.height(40.dp), contentScale = ContentScale.Fit)
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
