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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile
import com.example.amped3controller.dsp.Fit
import com.example.amped3controller.dsp.Wav
import java.util.Locale

/**
 * Converting an impulse response into a cabinet profile, and listening to it safely.
 *
 * Loading coefficients only writes the live DSP, never the stored slots, and the fit keeps the
 * pole bank of a factory cabinet, so a converted profile is as stable as one Blackstar ships.
 * What is not guaranteed is how it sounds, which is why nothing is sent until the checks pass and
 * why the first listen happens with the cabinet level at its minimum.
 */
@Composable internal fun CustomProfileSection(s:AmpState,c:AmpedMidiController,actions:UiActions){
    val conversion by c.conversion.collectAsState()
    val saved by c.customProfiles.collectAsState()
    val options by c.irOptions.collectAsState()
    val batch by c.batchReport.collectAsState()
    var name by remember { mutableStateOf("") }
    var scrivendo by remember { mutableStateOf<String?>(null) }

    CabLabSection(s, c)

    Section(T("Profili da risposta all'impulso", "Profiles from an impulse response"))
    Text(T("Sperimentale. La conversione tiene i poli di una cassa di fabbrica, quindi la stabilita' e' quella di un profilo di fabbrica. Il suono invece non e' garantito: nulla viene inviato se i controlli non passano.",
           "Experimental. The fit keeps the poles of a factory cabinet, so stability is that of a factory profile. The sound is not guaranteed: nothing is sent unless the checks pass."),
        color=Muted,fontSize=12.sp)
    if (s.customLoaded) {
        Box(Modifier.fillMaxWidth().padding(vertical=10.dp).background(Color(0xFF3A1512),RoundedCornerShape(10.dp)).padding(14.dp)) {
            Column {
                Text(T("In prova: ${s.customName}", "Auditioning: ${s.customName}"),color=Paper,fontWeight=FontWeight.Black)
                Text(T("Snapshot di recupero disponibile. Ripristina il profilo precedente dopo la connessione USB.",
                       "Recovery snapshot available. Restore the previous profile after connecting USB."),color=Muted,fontSize=12.sp)
                Button(onClick={c.revertAudition()},enabled=s.synced&&!s.busy,modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                    Text(T("Ripristina il profilo precedente", "Restore the previous profile"))
                }
            }
        }
    }
    Text(T("Il file: WAV, qualunque frequenza di campionamento (non serve ricampionare), PCM a 8/16/24/32 bit o float a 32. Deve essere la risposta all'impulso di una cassa: se ne usano al massimo i primi 170 ms, perche' sedici filtri non possono rendere una coda di riverbero.",
           "The file: WAV, any sample rate (no resampling needed), 8/16/24/32-bit PCM or 32-bit float. It has to be a cabinet impulse response: at most the first 170 ms are used, because sixteen filters cannot render a reverb tail."),
        color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp,bottom=6.dp))

    // options read by the picker: channel, length, pole bank
    Text(T("Opzioni di conversione", "Conversion options").uppercase(), color=Muted, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.4.sp)
    Choices(T("Canale (file stereo)", "Channel (stereo files)"), options.channel, listOf(T("Primo", "First") to 0, T("Secondo", "Second") to 1, "Mix" to Wav.MIX), true) { c.irOptions.value = options.copy(channel = it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
        Text(T("Lunghezza usata", "Length used"), color=Muted, fontSize=12.sp)
        Text("${options.lengthMs} ms", color=Paper, fontSize=12.sp, fontWeight=FontWeight.Bold)
    }
    Slider(value=options.lengthMs.toFloat(), onValueChange={ c.irOptions.value = options.copy(lengthMs = it.toInt()) }, valueRange=20f..Fit.MAX_MS.toFloat(), steps=14,
        colors=SliderDefaults.colors(thumbColor=Red, activeTrackColor=Red))
    Choices(T("Banco di poli", "Pole bank"), when (options.template) { null -> 0; IrOptions.AUTO -> 1; else -> 2 },
        listOf(T("Cassa caricata", "Loaded cabinet") to 0, T("Il più vicino (3 tentativi)", "Nearest (tries 3)") to 1, T("Scelto…", "Chosen…") to 2), true) {
        c.irOptions.value = options.copy(template = when (it) { 0 -> null; 1 -> IrOptions.AUTO; else -> options.template?.takeIf { t -> t != IrOptions.AUTO } ?: "21:0:0" })
    }
    if (options.template != null && options.template != IrOptions.AUTO) CabinetPicker(T("Banco di poli", "Pole bank"), options.template!!) { c.irOptions.value = options.copy(template = it) }

    Row(Modifier.fillMaxWidth().padding(top=6.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick=actions.importIr,enabled=!s.busy,modifier=Modifier.weight(1f)) { Text(T("Converti un WAV", "Convert a WAV")) }
        OutlinedButton(onClick=actions.importIrs,enabled=!s.busy,modifier=Modifier.weight(1f)) { Text(T("Più WAV in lotto", "Several WAVs")) }
    }
    c.lastIrName?.let { last ->
        TextButton(onClick={ c.reconvertIr(options) }, enabled=!s.busy) { Text(T("Riconverti \"$last\" con queste opzioni", "Reconvert \"$last\" with these options"), fontSize=12.sp) }
    }
    if (batch.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top=6.dp).background(Panel,RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(T("Lotto", "Batch").uppercase(), color=Muted, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=1.4.sp)
        batch.forEach { Text(it, color=if (it.startsWith("✓")) Paper else Red, fontSize=11.sp) }
    }

    conversion?.let { k ->
        val metrics = k.verdict.metrics
        Column(Modifier.fillMaxWidth().padding(top=12.dp).background(Panel,RoundedCornerShape(10.dp)).padding(14.dp)) {
            Text(k.source,color=Paper,fontWeight=FontWeight.Bold,fontSize=14.sp)
            k.recipe?.let { Text(it.describe { key -> FactoryProfiles.name(key) }, color=Muted, fontSize=12.sp) }
            Text(T("Scostamento dal bersaglio: %.2f dB · bassi %.1f · medi %.1f · alti %.1f".format(Locale.US,k.errorDb,k.bands.lowDb,k.bands.midDb,k.bands.highDb),
                   "Deviation from the target: %.2f dB · lows %.1f · mids %.1f · highs %.1f".format(Locale.US,k.errorDb,k.bands.lowDb,k.bands.midDb,k.bands.highDb)),color=Muted,fontSize=12.sp)
            Text(T("Banco di poli: ${k.templateKey} (${FactoryProfiles.name(k.templateKey)})", "Pole bank: ${k.templateKey} (${FactoryProfiles.name(k.templateKey)})"),color=Muted,fontSize=12.sp)
            if (k.recipe == null) Text(
                T("%.0f Hz · %s · %d ms usati%s".format(Locale.US, k.rate,
                    if (k.channels > 1) "canale ${channelName(k.options?.channel ?: 0)} di ${k.channels}" else "mono", k.usedMs,
                    if (k.truncated) " (file troncato)" else ""),
                  "%.0f Hz · %s · %d ms used%s".format(Locale.US, k.rate,
                    if (k.channels > 1) "channel ${channelName(k.options?.channel ?: 0)} of ${k.channels}" else "mono", k.usedMs,
                    if (k.truncated) " (file truncated)" else "")),
                color=Muted,fontSize=12.sp)
            Text(T("Cassa di fabbrica più vicina: ", "Nearest factory cabinet: ") + k.nearest.joinToString(" · ") { "%s (%.1f dB)".format(Locale.US, FactoryProfiles.name(it.first), it.second) }, color=Muted, fontSize=11.sp)
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
            val fitted = remember(k.header) { CabProfile.decode(k.header, k.chunks)?.let { CabLab.curveDb(it) } }
            fitted?.let {
                ResponseGraph(listOf(Curve(it, Red, T("convertito", "fitted")), Curve(k.targetCurve, CurveColours[4], T("bersaglio", "target"), 1.5f),
                    Curve(c.factory.curve(k.templateKey), CurveColours[1], T("banco di poli", "pole bank"), 1.2f)))
            }
            OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text(T("Nome", "Name"))},
                singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={c.saveConversion(name);name=""},enabled=name.isNotBlank()&&k.verdict.passed,modifier=Modifier.weight(1f)) {
                    Text(T("Salva", "Save"))
                }
                OutlinedButton(onClick={c.discardConversion()},modifier=Modifier.weight(1f)) {
                    Text(T("Scarta", "Discard"))
                }
            }
        }
    }

    // --- the user's own profiles ---------------------------------------------------------
    Section(T("I miei profili", "My profiles"))
    var sort by remember { mutableStateOf("date") }
    var onlyFavorites by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var abA by remember { mutableStateOf<String?>(null) }
    var abB by remember { mutableStateOf<String?>(null) }
    FlowRowCompat {
        Chip(T("Recenti", "Newest"), sort == "date") { sort = "date" }
        Chip(T("Nome", "Name"), sort == "name") { sort = "name" }
        Chip(T("Errore", "Error"), sort == "error") { sort = "error" }
        Chip("★", onlyFavorites) { onlyFavorites = !onlyFavorites }
    }
    if (saved.size > 3) OutlinedTextField(value=filter,onValueChange={filter=it.take(30)},placeholder={Text(T("cerca nei profili…", "search profiles…"))},singleLine=true,modifier=Modifier.fillMaxWidth())
    val shown = saved.filter { !onlyFavorites || it.favorite }.filter { filter.isBlank() || it.name.contains(filter, true) || it.notes.contains(filter, true) }
        .let { l -> when (sort) { "name" -> l.sortedBy { it.name.lowercase() }; "error" -> l.sortedBy { if (it.errorDb.isNaN()) 99.0 else it.errorDb }; else -> l.sortedByDescending { it.created } } }
    if (saved.isEmpty()) Text(T("Nessun profilo salvato.", "No saved profiles."), color=Muted, fontSize=12.sp)

    // A/B: two profiles, one tap each, through the ordinary audition with its recovery snapshot
    if (saved.size >= 2) {
        Text(T("Confronto A/B: scegli due profili dalla lista, poi alterna.", "A/B: pick two profiles from the list, then switch."), color=Muted, fontSize=11.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val a = saved.firstOrNull { it.id == abA }; val b = saved.firstOrNull { it.id == abB }
            Button(onClick={ a?.let { c.audition(it) } }, enabled=a != null && s.synced && !s.busy, modifier=Modifier.weight(1f)) { Text("A · " + (a?.name?.take(14) ?: "—"), fontSize=12.sp) }
            Button(onClick={ b?.let { c.audition(it) } }, enabled=b != null && s.synced && !s.busy, modifier=Modifier.weight(1f)) { Text("B · " + (b?.name?.take(14) ?: "—"), fontSize=12.sp) }
        }
    }

    var confirmingCustom by remember { mutableStateOf<CustomProfile?>(null) }
    var existingSlot by remember { mutableIntStateOf(1) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    shown.forEach { profile ->
        val mine = saved.any { it.id == profile.id }
        Column(Modifier.fillMaxWidth().padding(top=10.dp)) {
            if (mine) Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={c.toggleFavoriteCustom(profile.id)}) { Text(if (profile.favorite) "★" else "☆", color=if (profile.favorite) Red else Muted, fontSize=18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(profile.name,color=Paper,fontWeight=FontWeight.Bold)
                    Text("${profile.source} · %.2f dB · ${profile.templateKey}".format(Locale.US,profile.errorDb) +
                        (if (!profile.passed) T(" · da riverificare", " · will be re-checked") else ""),
                        color=Muted,fontSize=11.sp)
                    profile.recipe?.let { Text(it.describe { k -> FactoryProfiles.name(k) }, color=Muted, fontSize=10.sp) }
                    if (profile.notes.isNotBlank()) Text(profile.notes, color=Muted, fontSize=11.sp)
                }
                TextButton(onClick={c.audition(profile)},enabled=s.synced&&!s.busy){ Text(T("Prova", "Try")) }
            }
            if (mine) FlowRowCompat {
                Chip(T("Nel banco…", "To a slot…"), scrivendo == profile.id) {
                    scrivendo = if (scrivendo == profile.id) null else profile.id
                }
                Chip(T("Modifica", "Edit"), editing == profile.id) { editing = if (editing == profile.id) null else profile.id; editName = profile.name; editNotes = profile.notes }
                Chip(T("Duplica", "Duplicate"), false) { c.duplicateCustom(profile.id) }
                Chip(T("Esporta", "Export"), false) { actions.exportProfile(profile) }
                profile.recipe?.let { r -> Chip(T("Nel lab", "In the lab"), false) { c.labRecipe.value = r } }
                if (saved.size >= 2) { Chip("A", abA == profile.id) { abA = profile.id }; Chip("B", abB == profile.id) { abB = profile.id } }
                Chip(T("Già in un banco…", "Already in a slot…"), false) { confirmingCustom = profile; existingSlot = if (s.cabSlot in 1..3) s.cabSlot else 1 }
                Chip(T("Elimina", "Delete"), false) { c.deleteCustom(profile.id) }
            }
            if (mine && editing == profile.id) Column(Modifier.fillMaxWidth().padding(top=6.dp).background(Panel,RoundedCornerShape(8.dp)).padding(10.dp)) {
                OutlinedTextField(value=editName,onValueChange={editName=it.take(60)},label={Text(T("Nome", "Name"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=editNotes,onValueChange={editNotes=it.take(500)},label={Text(T("Note", "Notes"))},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={ c.renameCustom(profile.id, editName); c.annotateCustom(profile.id, editNotes); editing = null }) { Text(T("Salva", "Save")) }
                    TextButton(onClick={ editing = null }) { Text(T("Annulla", "Cancel"), color=Muted) }
                }
            }
            if (scrivendo == profile.id) SlotWritePanel(s, c, profile, showName = !mine) { scrivendo = null }
        }
    }
    confirmingCustom?.let { p ->
        AlertDialog(onDismissRequest={confirmingCustom=null}, title={Text(p.name)},
            text={Column {
                Text(T("Associa solo se questo è esattamente il custom già salvato nel banco: la pedaliera restituisce gli indici, non i coefficienti. Verrà creato un backup locale senza scritture USB.", "Associate only if this is the exact custom already stored in the slot: the pedal returns indices, not coefficients. This creates a local backup without USB writes."))
                Choices("CAB",existingSlot,listOf("1" to 1,"2" to 2,"3" to 3),true){existingSlot=it}
            }},
            confirmButton={TextButton(onClick={c.confirmCustomSlot(p,existingSlot);confirmingCustom=null}){Text(T("Confermo identità", "Confirm identity"))}},
            dismissButton={TextButton(onClick={confirmingCustom=null}){Text(T("Annulla", "Cancel"))}})
    }
}

private fun channelName(ch: Int) = when (ch) { Wav.MIX -> "mix"; else -> (ch + 1).toString() }
