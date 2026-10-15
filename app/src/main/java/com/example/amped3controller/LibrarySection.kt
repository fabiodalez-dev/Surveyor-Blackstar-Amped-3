package com.example.amped3controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import java.util.Locale

/**
 * The cabinets that ship with the app beyond Blackstar's 288: profiles fitted from impulse
 * responses released under CC0, and a few designed from the factory library. Each says where it
 * came from. They are tried and written like any custom profile, through the same checks.
 */
@Composable internal fun LibrarySection(s: AmpState, c: AmpedMidiController, onWriteToSlot: (CustomProfile) -> Unit) {
    val all = c.builtin
    if (all.isEmpty()) return
    Section(T("Casse aggiuntive", "Extra cabinets"))
    Text(T("${all.count { it.captured }} casse reali da risposte all'impulso con licenza CC0, più ${all.count { !it.captured }} progettate dalla libreria di fabbrica. Sono adattate al banco di poli indicato; la risposta è modellata, non misurata. Crediti e licenze in docs/CAB_LIBRARY.md.",
           "${all.count { it.captured }} real cabinets from CC0-licensed impulse responses, plus ${all.count { !it.captured }} designed from the factory library. Each is fitted on the pole bank shown; the response is modelled, not measured. Credits and licences in docs/CAB_LIBRARY.md."),
        color = Muted, fontSize = 12.sp)
    var style by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val styles = all.map { it.style }.filter { it.isNotBlank() }.distinct()
    FlowRowCompat {
        Chip(T("Tutte", "All"), style == "") { style = "" }
        styles.forEach { st -> Chip(st, style == st) { style = st } }
    }
    all.filter { style.isEmpty() || it.style == style }.forEach { cab ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(8.dp)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cab.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${cab.cabinet} · ${cab.speaker} · ${cab.mic}", color = Muted, fontSize = 11.sp)
                    Text((if (cab.captured) "${cab.pack} · ${cab.license}" else T("progettata · ricetta inclusa", "designed · recipe included")) +
                        " · %.1f dB · ${cab.templateKey}".format(Locale.US, cab.errorDb), color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { open = if (open == cab.id) null else cab.id }) { Text(if (open == cab.id) "▲" else "▼", color = Muted) }
            }
            if (open == cab.id) {
                val values = remember(cab.id) { CabProfile.decode(cab.header, cab.chunks) }
                values?.let { v ->
                    val curve = remember(cab.id) { CabLab.curveDb(v) }
                    ResponseGraph(listOf(Curve(curve, Red, cab.name.take(24)), Curve(c.factory.curve(cab.templateKey), CurveColours[1], T("banco di poli", "pole bank"))))
                    Text(describe(CabLab.character(curve)), color = Muted, fontSize = 11.sp)
                }
                if (cab.captured) Text(T("Errore per banda: bassi %.1f · medi %.1f · alti %.1f dB", "Band error: lows %.1f · mids %.1f · highs %.1f dB").format(Locale.US, cab.bandLow, cab.bandMid, cab.bandHigh), color = Muted, fontSize = 11.sp)
                cab.recipe?.let { Text(it.describe { k -> FactoryProfiles.name(k) }, color = Muted, fontSize = 11.sp) }
                val profile = remember(cab.id) { cab.toCustomProfile() }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { c.audition(profile) }, enabled = s.synced && !s.busy) { Text(T("Prova", "Try")) }
                    TextButton(onClick = { onWriteToSlot(profile) }, enabled = s.synced && !s.busy) { Text(T("Nel banco…", "To a slot…")) }
                    TextButton(onClick = { c.adoptBuiltin(cab) }) { Text(T("Nei miei profili", "To my profiles"), color = Muted) }
                    cab.recipe?.let { r -> TextButton(onClick = { c.labRecipe.value = r }) { Text(T("Nel lab", "In the lab"), color = Muted) } }
                }
            }
        }
    }
}
