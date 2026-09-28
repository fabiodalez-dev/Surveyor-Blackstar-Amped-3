package com.example.amped3controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable internal fun AmpPage(s:AmpState,c:AmpedMidiController){
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
    if (s.ampSlot == 1) Choices(T("Voce Clean", "Clean Voice"),s.amp[26],listOf("Warm" to 1,"Bright" to 0),s.synced&&!s.busy){c.setParameter(false,26,it)}
    if (s.ampSlot == 2) Choices(T("Voce Crunch", "Crunch Voice"),s.amp[27],listOf("Crunch" to 1,"Super" to 0),s.synced&&!s.busy){c.setParameter(false,27,it)}
    if (s.ampSlot == 3) Choices(T("Voce Overdrive", "Overdrive Voice"),s.amp[28],listOf("OD1" to 1,"OD2" to 0),s.synced&&!s.busy){c.setParameter(false,28,it)}
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
    var saveName by remember(s.ampSlot,s.ampNames[s.ampSlot]) {mutableStateOf(s.ampNames[s.ampSlot] ?: "Mio Suono")}
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=saveName,onValueChange={saveName=it.take(60)},label={Text(T("Nome", "Name"))},singleLine=true,modifier=Modifier.weight(1f))
        Button(onClick={c.saveAmpHardware(if (s.ampSlot > 0) s.ampSlot else 1, saveName)},enabled=s.synced&&!s.busy&&saveName.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(T("Salva su Slot ", "Save to slot ") + "${if (s.ampSlot > 0) s.ampSlot else 1}",color=Color.White)}
    }
}
