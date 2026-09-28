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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Converting an impulse response into a cabinet profile, and listening to it safely.
 *
 * Loading coefficients only writes the live DSP, never the stored slots, and the fit keeps the
 * pole bank of a factory cabinet, so a converted profile is as stable as one Blackstar ships.
 * What is not guaranteed is how it sounds, which is why nothing is sent until the checks pass and
 * why the first listen happens with the cabinet level at its minimum.
 */
@Composable internal fun CustomProfileSection(s:AmpState,c:AmpedMidiController,onImportIr:()->Unit){
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
                Text(T("Snapshot di recupero disponibile. Ripristina il profilo precedente dopo la connessione USB.",
                       "Recovery snapshot available. Restore the previous profile after connecting USB."),color=Muted,fontSize=12.sp)
                Button(onClick={c.revertAudition()},enabled=s.synced&&!s.busy,modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                    Text(T("Ripristina il profilo precedente", "Restore the previous profile"))
                }
            }
        }
    }
    Text(T("Il file: WAV, qualunque frequenza di campionamento (non serve ricampionare), PCM a 8/16/24/32 bit o float a 32. Di un file a piu' canali viene usato il primo. Deve essere la risposta all'impulso di una cassa: se ne usano i primi 170 ms, perche' sedici filtri non possono rendere una coda di riverbero.",
           "The file: WAV, any sample rate (no resampling needed), 8/16/24/32-bit PCM or 32-bit float. From a multi-channel file the first channel is used. It has to be a cabinet impulse response: the first 170 ms are used, because sixteen filters cannot render a reverb tail."),
        color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp,bottom=10.dp))
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
            Text(
                T("%.0f Hz · %s · %d ms usati%s".format(Locale.US, k.rate,
                    if (k.channels > 1) "primo di ${k.channels} canali" else "mono", k.usedMs,
                    if (k.truncated) " (file troncato)" else ""),
                  "%.0f Hz · %s · %d ms used%s".format(Locale.US, k.rate,
                    if (k.channels > 1) "first of ${k.channels} channels" else "mono", k.usedMs,
                    if (k.truncated) " (file truncated)" else "")),
                color=Muted,fontSize=12.sp)
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
                OutlinedButton(onClick={c.saveConversion(name);name=""},enabled=name.isNotBlank()&&k.verdict.passed,modifier=Modifier.weight(1f)) {
                    Text(T("Salva", "Save"))
                }
                OutlinedButton(onClick={c.discardConversion()},modifier=Modifier.weight(1f)) {
                    Text(T("Scarta", "Discard"))
                }
            }
        }
    }
    var confirmingCustom by remember { mutableStateOf<CustomProfile?>(null) }
    var existingSlot by remember { mutableIntStateOf(1) }
    var scrivendo by remember { mutableStateOf<String?>(null) }
    var slot by remember { mutableIntStateOf(1) }
    var nomeBanco by remember { mutableStateOf("") }
    saved.forEach { profile ->
        Column(Modifier.fillMaxWidth().padding(top=10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name,color=Paper,fontWeight=FontWeight.Bold)
                    Text("${profile.source} · %.2f dB".format(Locale.US,profile.errorDb) +
                        (if (!profile.passed) T(" · da riverificare", " · will be re-checked") else ""),
                        color=Muted,fontSize=11.sp)
                }
                TextButton(onClick={c.audition(profile)},enabled=s.synced&&!s.busy){
                    Text(T("Prova", "Try"))
                }
                TextButton(onClick={
                    scrivendo = if (scrivendo == profile.id) null else profile.id
                    nomeBanco = profile.name.take(21)
                    slot = if (s.cabSlot in 1..3) s.cabSlot else 1
                },enabled=s.synced&&!s.busy){ Text(T("Nel banco…", "To a slot…")) }
                TextButton(onClick={c.deleteCustom(profile.id)}){Text(T("Elimina", "Delete"),color=Muted)}
            }
            TextButton(onClick={confirmingCustom=profile;existingSlot=if(s.cabSlot in 1..3) s.cabSlot else 1},enabled=s.synced&&!s.busy) {
                Text(T("Questo custom è già in un banco…", "This custom is already in a slot…"))
            }
            if (scrivendo == profile.id) {
                Column(Modifier.fillMaxWidth().padding(top=8.dp)
                    .background(Color(0xFF221A17),RoundedCornerShape(10.dp)).padding(14.dp)) {
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
                    Button(onClick={ c.writeCustomToSlot(profile, slot, nomeBanco.trim()); scrivendo = null },
                        enabled=s.synced&&!s.busy&&nomeBanco.isNotBlank(),
                        colors=ButtonDefaults.buttonColors(containerColor=Red,contentColor=Color.White),
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                        Text(T("Sovrascrivi il banco $slot", "Overwrite slot $slot"))
                    }
                    TextButton(onClick={ scrivendo = null },modifier=Modifier.fillMaxWidth()) {
                        Text(T("Annulla", "Cancel"),color=Muted)
                    }
                }
            }
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

/**
 * The converted profile in red over the cabinet it was fitted on in grey, 40 Hz to 16 kHz on a log
 * axis. It is the modelled response, from the coefficients: no sweep has ever been measured at the
 * pedal's output, so read it as what the maths says, not as what the speaker does.
 */
@Composable internal fun ResponseCurve(header:String,chunks:List<String>,baseHeader:String?,baseChunks:List<String>?){
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
