package com.example.amped3controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable internal fun CabPage(s:AmpState,c:AmpedMidiController,actions:UiActions=UiActions()){
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
    var mic by remember(s.cab[1]){mutableIntStateOf(if(s.cab[1] in 0..5) s.cab[1] else 0)}
    var axis by remember(s.cab[2]){mutableIntStateOf(if(s.cab[2]==1) 1 else 0)}
    val prefs by c.cabinetPrefs.collectAsState()
    val selectedKey = FactoryProfiles.key(cabinet, mic, axis)
    fun pick(key: String) { val (a, b, d) = key.split(":").map { it.toInt() }; cabinet = a; mic = b; axis = d }

    // one catalogue for Blackstar's cabinets and the shipped ones; a shipped one, when chosen, replaces the factory controls
    val catalog = remember(c.builtin) { CabCatalog.build(c.builtin) }
    var libId by remember(s.cab[0]) { mutableStateOf<String?>(null) }
    val lib = libId?.let { id -> c.builtin.firstOrNull { it.id == id } }
    val current = catalog.firstOrNull { it.id == (lib?.let { l -> "lib:${l.id}" } ?: "f:$cabinet") } ?: catalog.first()
    val thumbnails = remember { HashMap<String, DoubleArray?>() }
    fun curveOf(cab: CatalogCab): DoubleArray? = thumbnails.getOrPut(cab.id) {
        runCatching { cab.factory?.let { c.factory.curve(FactoryProfiles.key(it, 0, 0)) } ?: cab.lib?.let { libraryCurve(it) } }.getOrNull()
    }
    var filter by remember { mutableStateOf(CatalogFilter()) }
    var browsing by remember { mutableStateOf(false) }
    fun choose(cab: CatalogCab) {
        if (cab.factory != null) {
            libId = null; cabinet = cab.factory
            // a microphone chosen as a filter is the one the factory cabinet opens with
            CabCatalog.factoryMics.indexOf(filter.mic).takeIf { it >= 0 }?.let { mic = it }
        } else libId = cab.lib?.id
    }

    val favorite = if (lib == null) selectedKey in prefs.favorites else "lib:${lib.id}" in prefs.favorites
    CabSelector(current, curveOf(current), favorite, catalog.size, onBrowse = { browsing = true },
        onFavorite = { c.toggleFavoriteCabinet(if (lib == null) selectedKey else "lib:${lib.id}") }) {
        if (lib == null) CabinetDrawing(AmpedProtocol.cabinetNames.getOrNull(cabinet) ?: name,
            applied = cabinet == s.cab[0] && mic == s.cab[1] && axis == s.cab[2])
        else CabinetDrawing("${current.size} ${lib.cabinet}".trim(), applied = s.customLoaded && s.customName == lib.name)
    }
    if (browsing) CabBrowserSheet(catalog, filter, { filter = it }, prefs.favorites, ::curveOf, current.id,
        onPick = { choose(it); browsing = false }, onDismiss = { browsing = false })

    // favourites and the last cabinets loaded, one tap away
    if (prefs.favorites.isNotEmpty() || prefs.recents.isNotEmpty()) {
        FlowRowCompat {
            prefs.favorites.forEach { k ->
                if (k.startsWith("lib:")) catalog.firstOrNull { it.id == k }?.let { cab -> Chip("★ " + cab.title.take(28), cab.id == current.id) { choose(cab) } }
                else Chip("★ " + FactoryProfiles.name(k), lib == null && k == selectedKey) { libId = null; pick(k) }
            }
            prefs.recents.filter { it !in prefs.favorites }.forEach { k -> Chip("↺ " + FactoryProfiles.name(k), lib == null && k == selectedKey) { libId = null; pick(k) } }
        }
    }

    if (lib != null) LibraryDetail(s, c, lib) else {
    Choices(T("Microfono", "Microphone"),mic,AmpedProtocol.micNames.mapIndexed { i, n -> n to i },!s.busy){mic=it}
    Choices(T("Asse", "Axis"),axis,listOf("On Axis" to 0,"Off Axis" to 1),!s.busy){axis=it}
    Text(T("Selezionato: ", "Selected: ") + FactoryProfiles.name(selectedKey),color=Muted,fontSize=12.sp)
    Text(T("Caricato: ", "Loaded: ") + "$name · ${AmpedProtocol.micShortNames.getOrNull(s.cab[1])?:"—"} · ${if(s.cab[2]==0)"On Axis" else if(s.cab[2]==1)"Off Axis" else "—"}",color=Muted,fontSize=12.sp)
    // what the choice sounds like, on paper: its curve, against whatever is worth comparing it with
    var compare by remember { mutableStateOf("") }   // "", "B", "mics", "axis"
    var keyB by remember { mutableStateOf("18:0:0") }
    FlowRowCompat {
        Chip(T("Solo questa", "This one"), compare == "") { compare = "" }
        Chip(T("Contro un'altra", "Against another"), compare == "B") { compare = "B" }
        Chip(T("I 6 microfoni", "All 6 mics"), compare == "mics") { compare = "mics" }
        Chip(T("Asse on / off", "On / off axis"), compare == "axis") { compare = "axis" }
    }
    if (compare == "B") CabinetPicker("B", keyB) { keyB = it }
    val curves = ArrayList<Curve>()
    curves += Curve(c.factory.curve(selectedKey), Red, FactoryProfiles.name(selectedKey).take(28))
    when (compare) {
        "B" -> curves += Curve(c.factory.curve(keyB), CurveColours[2], FactoryProfiles.name(keyB).take(28))
        "mics" -> (0..5).filter { it != mic }.forEachIndexed { n, m -> curves += Curve(c.factory.curve(FactoryProfiles.key(cabinet, m, axis)), CurveColours[(n + 1) % CurveColours.size], AmpedProtocol.micShortNames[m], 1.2f) }
        "axis" -> curves += Curve(c.factory.curve(FactoryProfiles.key(cabinet, mic, 1 - axis)), CurveColours[2], if (axis == 0) "Off axis" else "On axis")
    }
    ResponseGraph(curves)
    val character = remember(selectedKey) { com.example.amped3controller.dsp.CabLab.character(c.factory.curve(selectedKey)) }
    Text(describe(character), color=Muted, fontSize=11.sp)
    if (compare == "B") Text(T("Distanza fra le due: %.1f dB", "Distance between the two: %.1f dB").format(Locale.US, com.example.amped3controller.dsp.CabLab.distanceDb(c.factory.curve(selectedKey), c.factory.curve(keyB))), color=Muted, fontSize=11.sp)
    val similar = remember(selectedKey) { c.factory.nearest(c.factory.curve(selectedKey), 4).drop(1) }
    Text(T("Simili: ", "Similar: ") + similar.joinToString(" · ") { "%s (%.1f dB)".format(Locale.US, FactoryProfiles.name(it.first), it.second) }, color=Muted, fontSize=11.sp)
    FlowRowCompat { similar.forEach { (k, _) -> Chip(FactoryProfiles.name(k), false) { pick(k) } } }

    // levelling: the cabinet level goes down for the louder cabinets, never up
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = prefs.levelMatch, onCheckedChange = { c.setLevelMatch(it) }, modifier = Modifier.testTag("level-match"))
        Text(T("Livella il volume tra le casse", "Level the volume across cabinets"), modifier = Modifier.padding(start = 4.dp))
    }
    val correction = c.factory.correctionDb(selectedKey)
    Text(if (prefs.levelMatch) T("Livello cassa per questa scelta: %+.1f dB rispetto al tuo. Le casse più forti vengono abbassate, nessuna viene alzata; vale dal prossimo caricamento.",
            "Cabinet level for this choice: %+.1f dB from yours. Louder cabinets are turned down, none is turned up; applies from the next load.").format(Locale.US, LevelMatch.steps(correction) * 24.0 / 127.0)
        else T("Spento: le casse brillanti suonano fino a circa 6 dB più forti di quelle scure.", "Off: bright cabinets sound up to about 6 dB louder than dark ones."),
        color = Muted, fontSize = 11.sp)
    Button(onClick={c.chooseCab(cabinet,mic,axis)},enabled=s.synced&&!s.busy,modifier=Modifier.fillMaxWidth().testTag("apply-cabinet")){Text(T("Applica profilo DSP", "Load DSP profile"))}
    if (!s.synced) Text(T("Collega AMPED 3 via USB OTG e premi Connetti per applicare il profilo scelto.", "Connect the AMPED 3 over USB OTG and tap Connect to load the chosen profile."), color=Muted, fontSize=12.sp)
    }
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
    var saveName by remember(s.cabSlot,s.cabNames[s.cabSlot]) {mutableStateOf(s.cabNames[if (s.cabSlot > 0) s.cabSlot else 1] ?: "Mio CabRig")}
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text(T("Nome", "Name"))},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveCabHardware(if (s.cabSlot > 0) s.cabSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(T("Salva su Cab ", "Save to cab ") + "${if (s.cabSlot > 0) s.cabSlot else 1}",color=Color.White)}
    }

    Text(T("Le modifiche sono immediate.", "Changes take effect immediately."),color=Muted,fontSize=12.sp)
    CustomProfileSection(s,c,actions)
}
/** Silhouette della cassa scelta. Coni e pollici si leggono dal nome ("4x12 Classic UK"),
 *  cosi' una 2x12 non viene disegnata come una 4x12. La voce DI non ha cassa: si disegna la presa. */
@Composable internal fun CabinetDrawing(name:String, applied:Boolean){
    val misure = Regex("^(\\d+)x(\\d+)").find(name)
    // a shipped cabinet whose source never gave its size is drawn as one cone and labelled IR
    val ignota = misure == null && name != "DI"
    val coni = misure?.groupValues?.get(1)?.toIntOrNull() ?: if (ignota) 1 else 0
    val pollici = misure?.groupValues?.get(2)?.toIntOrNull() ?: 12
    val combo = name.contains("Combo", true)
    val telaio = if (applied) Color(0xff9a918c) else Color(0xff57524f)
    val cono = if (applied) Color(0xff6a6462) else Color(0xff413d3b)
    Box(Modifier.fillMaxWidth().height(150.dp).background(Panel, RoundedCornerShape(12.dp))) {
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
            // una 2x15 sta in piedi, una 8x10 e' una colonna di quattro file
            val colonne = if (coni >= 2 && !(coni == 2 && pollici >= 15)) 2 else 1
            val righe = (coni + colonne - 1) / colonne
            // un 10 pollici e' visibilmente piu' piccolo di un 12: la differenza si deve vedere
            val raggio = (if (righe >= 4) 15.dp else if (righe >= 2) 22.dp else 27.dp).toPx() * (if (pollici <= 10) 0.84f else 1f)
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
            if (coni == 0) "DI" else if (ignota) "IR" else "${coni}x${pollici}",
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
