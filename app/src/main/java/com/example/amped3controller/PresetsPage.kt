package com.example.amped3controller

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun PresetsPage(s:AmpState,c:AmpedMidiController,presets:List<LocalPreset>,onExport:()->Unit,onImport:()->Unit){
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
    },enabled=s.synced&&!s.busy&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White)){Text(if (saveDest==0) T("Salva sul telefono", "Save to the phone") else T("Scrivi nella memoria della pedaliera", "Write to the pedal memory"),color=Color.White)}
    
    if(presets.isEmpty())Text(T("La libreria locale è vuota.", "Your library is empty."),Modifier.padding(vertical=20.dp),color=Muted)
    presets.forEach {p->
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold);Text(AmpedProtocol.cabinetNames.getOrNull(p.cab[0])?:"CabRig",fontSize=12.sp,color=Muted)};OutlinedButton(onClick={c.applyLocal(p)},enabled=s.synced&&!s.busy){Text(T("Carica", "Load"))}}
    }
    Section(T("Memorie della pedaliera", "Pedal memories"))
    for(i in 1..3)Text("AMP $i · ${s.ampNames[i] ?: T("da leggere", "not read yet")}",color=Muted)
    for(i in 1..3)Text("CAB $i · ${s.cabNames[i] ?: T("da leggere", "not read yet")}",color=Muted)
    
    val recoveries by c.recoveries.collectAsState()
    if (recoveries.isNotEmpty()) {
        Section(T("Recuperi CabRig disponibili", "Available CabRig recoveries"))
        Text(T("Carica il suono in memoria attiva, senza sovrascrivere banchi. Master e potenza restano invariati.", "Load the sound into live memory without overwriting slots. Master and power stay unchanged."),color=Muted,fontSize=12.sp)
        recoveries.forEach { p ->
            Text(p.name,color=Muted,fontSize=12.sp)
            OutlinedButton(onClick={c.applyLocal(p)},enabled=s.synced&&!s.busy){Text(T("Carica recupero live", "Load live recovery"))}
        }
    }
    Section(T("Backup dei banchi CabRig", "CabRig slot backups"))
    Text(T("Per un banco con una cassa originale Blackstar, conferma qui sotto: salveremo parametri e profilo DSP. Non usarlo per una IR custom: gli indici da soli non la identificano.",
        "For a slot containing a stock Blackstar cabinet, confirm below to back up parameters and DSP. Do not use this for custom IRs: selector indices cannot identify them."),color=Muted,fontSize=12.sp)
    var confirming by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        (1..3).forEach { slot -> OutlinedButton(onClick={confirming=slot},enabled=s.synced&&!s.busy,modifier=Modifier.weight(1f)){Text("CAB $slot")} }
    }
    if(confirming != 0) AlertDialog(onDismissRequest={confirming=0},
        title={Text("CAB $confirming · ${s.cabNames[confirming] ?: ""}")},
        text={Text(T("Confermi che questo banco contiene una cassa originale e non coefficienti custom? Leggeremo il banco e salveremo la copia in locale, senza sovrascriverlo.", "Does this slot contain a stock cabinet, not custom coefficients? This reads the slot and saves a local backup without overwriting it."))},
        confirmButton={TextButton(onClick={c.confirmFactorySlot(confirming);confirming=0}){Text(T("Confermo cassa originale", "Confirm stock cabinet"))}},
        dismissButton={TextButton(onClick={confirming=0}){Text(T("Annulla", "Cancel"))}})
    Section(T("Backup e ripristino", "Backup and restore"))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onClick=onExport,modifier=Modifier.weight(1f)){Text(T("Esporta", "Export"))};OutlinedButton(onClick=onImport,modifier=Modifier.weight(1f)){Text(T("Importa", "Import"))}}
}
