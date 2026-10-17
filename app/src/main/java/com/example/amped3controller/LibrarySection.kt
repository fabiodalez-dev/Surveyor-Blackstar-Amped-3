package com.example.amped3controller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import java.util.Locale

/**
 * A shipped cabinet chosen in the browser: what it is, where it came from, how far the model is
 * from the original, and the same three ways in as any custom profile: try it live, write it to a
 * slot, or keep a copy among the user's own profiles.
 */
@Composable internal fun LibraryDetail(s: AmpState, c: AmpedMidiController, cab: LibraryCab) {
    val profile = remember(cab.id) { cab.toCustomProfile() }
    var writing by remember(cab.id) { mutableStateOf(false) }
    val f = cab.facets
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRowCompat {
            listOfNotNull(
                f?.brand?.takeIf { it.isNotBlank() },
                f?.size?.takeIf { it.isNotBlank() },
                *(f?.speakers.orEmpty().toTypedArray()),
                *(f?.mics.orEmpty().toTypedArray()),
                CabCatalog.styleLabel(CabCatalog.styleGroup(cab.style, f?.bass == true)).takeIf { it.isNotBlank() },
            ).forEach { Fact(it) }
        }
        Fact(T("Cassa: ", "Cabinet: ") + cab.cabinet, wide = true)
        if (cab.captured) Fact(T("Fonte: ", "Source: ") + listOf(cab.pack, cab.license).filter { it.isNotBlank() }.joinToString(" · "), wide = true)
        if (cab.author.isNotBlank()) Fact(T("Autore: ", "Author: ") + cab.author + (if (cab.url.isNotBlank()) " · " + cab.url.substringAfter("://").substringBefore('/').removePrefix("www.") else ""), wide = true)
        cab.recipe?.let { Fact(it.describe { k -> FactoryProfiles.name(k) }, wide = true) }

        val curve = remember(cab.id) { CabProfile.decode(cab.header, cab.chunks)?.let { CabLab.curveDb(it) } }
        curve?.let {
            ResponseGraph(listOf(Curve(it, Red, cab.name.take(24)), Curve(c.factory.curve(cab.templateKey), CurveColours[1], T("banco di poli", "pole bank"))))
            Text(describe(CabLab.character(it)), color = Muted, fontSize = 11.sp)
        }
        Text((if (cab.captured) T("Modello della risposta originale, scarto %.1f dB", "Model of the original response, %.1f dB off") else T("Progettata dalla libreria di fabbrica", "Designed from the factory library"))
            .format(Locale.US, cab.errorDb) + " · " + T("banco di poli ", "pole bank ") + FactoryProfiles.name(cab.templateKey), color = Muted, fontSize = 11.sp)
        if (cab.captured && !cab.bandLow.isNaN()) Text(T("Scarto per banda: bassi %.1f · medi %.1f · alti %.1f dB", "Error by band: lows %.1f · mids %.1f · highs %.1f dB")
            .format(Locale.US, cab.bandLow, cab.bandMid, cab.bandHigh), color = Muted, fontSize = 11.sp)

        Button(onClick = { c.audition(profile) }, enabled = s.synced && !s.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("library-try")) {
            Text(T("Prova sulla pedaliera", "Try it on the pedal"))
        }
        Text(T("La prova parte con il livello cassa al minimo e dura finché la pedaliera resta accesa.",
            "The trial starts with the cabinet level at its minimum and lasts while the pedal stays on."), color = Muted, fontSize = 11.sp)
        if (!s.synced) Text(T("Collega AMPED 3 via USB OTG e premi Connetti per provarla.", "Connect the AMPED 3 over USB OTG and tap Connect to try it."), color = Muted, fontSize = 12.sp)
        FlowRowCompat {
            Chip(T("Nel banco…", "To a slot…"), writing) { writing = !writing }
            Chip(T("Nei miei profili", "To my profiles"), false) { c.adoptBuiltin(cab) }
            cab.recipe?.let { r -> Chip(T("Nel lab", "In the lab"), false) { c.labRecipe.value = r } }
        }
        if (writing) SlotWritePanel(s, c, profile, showName = false) { writing = false }
    }
}

@Composable private fun Fact(text: String, wide: Boolean = false) {
    if (wide) Text(text, color = Muted, fontSize = 11.sp)
    else Text(text, color = Paper, fontSize = 12.sp, modifier = Modifier.background(Panel, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/**
 * Writing a custom profile into one of the pedal's three slots. Permanent, so it says what it
 * overwrites and how the old content is kept, and asks for the slot and the name first.
 */
@Composable internal fun SlotWritePanel(s: AmpState, c: AmpedMidiController, profile: CustomProfile, showName: Boolean, onClose: () -> Unit) {
    var slot by remember(profile.id) { mutableIntStateOf(if (s.cabSlot in 1..3) s.cabSlot else 1) }
    var nomeBanco by remember(profile.id) { mutableStateOf(profile.name.take(21)) }
    Column(Modifier.fillMaxWidth().padding(top=8.dp)
        .background(Color(0xFF221A17),RoundedCornerShape(10.dp)).padding(14.dp)) {
        if (showName) Text(profile.name, color=Paper, fontWeight=FontWeight.Bold)
        Text(T("Scrivere nella memoria della pedaliera", "Writing into the pedal's memory"),
            color=Paper,fontWeight=FontWeight.Black,fontSize=13.sp,letterSpacing=1.sp)
        Text(T("La prova tiene il profilo solo finche' la pedaliera resta accesa: allo spegnimento torna la cassa memorizzata. Scrivere nel banco lo rende permanente, ma sovrascrive la cassa che c'e' adesso in quel banco.",
               "Trying a profile keeps it only while the pedal stays on: switching off brings the stored cabinet back. Writing to a slot makes it permanent, but it overwrites the cabinet that slot holds today."),
            color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
        Text(T("Prima di toccare la pedaliera il contenuto del banco viene letto e salvato nel telefono, e dopo la scrittura il banco viene riletto e confrontato. La risposta di un profilo convertito resta comunque modellata, non misurata.",
               "Before the pedal is touched the slot is read and saved to the phone, and after the write the slot is read back and compared. The response of a converted profile is still modelled, not measured."),
            color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
        Text(T("Banco di destinazione", "Destination slot").uppercase(),color=Muted,fontSize=11.sp,
            fontWeight=FontWeight.Bold,letterSpacing=1.4.sp,modifier=Modifier.padding(top=12.dp,bottom=6.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { i ->
                val sel = slot == i
                Box(Modifier.weight(1f).background(if(sel) Red else Panel,RoundedCornerShape(8.dp))
                    .clickable{ slot = i }.padding(vertical=10.dp),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("CAB $i",color=if(sel) Color.White else Muted,fontWeight=FontWeight.Black,fontSize=13.sp)
                        Text(s.cabNames[i] ?: "—",color=if(sel) Color.White else Muted,fontSize=10.sp,maxLines=1)
                    }
                }
            }
        }
        OutlinedTextField(value=nomeBanco,onValueChange={nomeBanco=it.take(21)},
            label={Text(T("Nome nel banco (max 21)", "Name in the slot (max 21)"))},
            singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        Button(onClick={ c.writeCustomToSlot(profile, slot, nomeBanco.trim()); onClose() },
            enabled=s.synced&&!s.busy&&nomeBanco.isNotBlank(),
            colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White),
            modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
            Text(T("Sovrascrivi il banco $slot", "Overwrite slot $slot"))
        }
        TextButton(onClick=onClose,modifier=Modifier.fillMaxWidth()) {
            Text(T("Annulla", "Cancel"),color=Muted)
        }
    }
}
