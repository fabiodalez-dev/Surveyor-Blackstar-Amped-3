package com.example.amped3controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import java.util.Locale

/** How many cabinets the list shows before asking to show more; the list is inside a scrolling column. */
private const val PAGE = 24

/**
 * The cabinets that ship with the app beyond Blackstar's 288: profiles fitted from impulse
 * responses, and a few designed from the factory library. Each says where it came from. They are
 * filtered by brand, style, microphone, pack and text, and tried or written like any custom profile.
 */
@Composable internal fun LibrarySection(s: AmpState, c: AmpedMidiController, onWriteToSlot: (CustomProfile) -> Unit) {
    val all = c.builtin
    if (all.isEmpty()) return
    Section(T("Casse aggiuntive", "Extra cabinets"))
    Text(T("${all.count { it.captured }} casse reali adattate da risposte all'impulso, più ${all.count { !it.captured }} progettate dalla libreria di fabbrica. Sono modelli sul banco di poli indicato, non i file originali; ogni voce dice da dove viene. Crediti in docs/CAB_LIBRARY.md.",
           "${all.count { it.captured }} real cabinets fitted from impulse responses, plus ${all.count { !it.captured }} designed from the factory library. They are models on the pole bank shown, not the original files; each says where it came from. Credits in docs/CAB_LIBRARY.md."),
        color = Muted, fontSize = 12.sp)
    var brand by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var mic by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(PAGE) }
    var open by remember { mutableStateOf<String?>(null) }
    val brands = remember(all) { LibraryCab.brands.filter { b -> all.any { it.brand == b } } + listOfNotNull("other".takeIf { all.any { it.brand == "other" } }) }
    val styles = remember(all) { all.map { it.style }.filter { it.isNotBlank() }.distinct().sorted() }
    val mics = remember(all) { listOf("SM57", "MD421", "R121", "e906", "U87", "C414", "i5", "M160", "blend").filter { m -> all.any { it.mic.contains(m, true) } } }
    val packs = remember(all) { all.map { it.pack }.filter { it.isNotBlank() }.distinct().sorted() }
    val q = query.trim().lowercase()
    val matching = remember(all, brand, style, mic, pack, q) {
        all.filter { (brand.isEmpty() || it.brand == brand) && (style.isEmpty() || it.style == style) && (mic.isEmpty() || it.mic.contains(mic, true)) &&
            (pack.isEmpty() || it.pack == pack) && (q.isEmpty() || q.split(" ").all { w -> it.searchText.contains(w) }) }
    }
    LaunchedEffect(brand, style, mic, pack, q) { shown = PAGE }
    OutlinedTextField(value = query, onValueChange = { query = it.take(40) }, placeholder = { Text(T("cerca per nome, cassa, speaker, microfono…", "search by name, cabinet, speaker, mic…")) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("library-search"))
    Text(T("Marca", "Brand").uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRowCompat {
        Chip(T("Tutte", "All"), brand == "") { brand = "" }
        brands.forEach { b -> Chip("$b (${all.count { it.brand == b }})", brand == b) { brand = if (brand == b) "" else b } }
    }
    Text(T("Stile", "Style").uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 4.dp))
    FlowRowCompat {
        Chip(T("Tutti", "All"), style == "") { style = "" }
        styles.forEach { st -> Chip(st, style == st) { style = if (style == st) "" else st } }
    }
    if (mics.size > 1) {
        Text(T("Microfono", "Microphone").uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 4.dp))
        FlowRowCompat {
            Chip(T("Tutti", "All"), mic == "") { mic = "" }
            mics.forEach { m -> Chip(m, mic == m) { mic = if (mic == m) "" else m } }
        }
    }
    if (packs.size > 1) {
        var packOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { packOpen = true }) { Text(if (pack.isEmpty()) T("Origine: tutte", "Source: all") else T("Origine: ", "Source: ") + pack, color = Muted, fontSize = 12.sp) }
            DropdownMenu(expanded = packOpen, onDismissRequest = { packOpen = false }) {
                DropdownMenuItem(text = { Text(T("Tutte", "All")) }, onClick = { pack = ""; packOpen = false })
                packs.forEach { p -> DropdownMenuItem(text = { Text("$p (${all.count { it.pack == p }})") }, onClick = { pack = p; packOpen = false }) }
            }
        }
    }
    Text(T("${matching.size} di ${all.size}", "${matching.size} of ${all.size}"), color = Muted, fontSize = 11.sp)
    matching.take(shown).forEach { cab ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(8.dp)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cab.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(listOf(cab.cabinet, cab.speaker, cab.mic).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp)
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
                if (cab.author.isNotBlank()) Text(T("Autore: ", "Author: ") + cab.author + (if (cab.url.isNotBlank()) " · " + cab.url else ""), color = Muted, fontSize = 10.sp)
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
    if (matching.size > shown) TextButton(onClick = { shown += PAGE }) { Text(T("Mostra altre ${minOf(PAGE, matching.size - shown)}", "Show ${minOf(PAGE, matching.size - shown)} more"), color = Red) }
    if (matching.isEmpty()) Text(T("Nessuna cassa con questi filtri", "No cabinet matches these filters"), color = Muted, fontSize = 12.sp)
}
