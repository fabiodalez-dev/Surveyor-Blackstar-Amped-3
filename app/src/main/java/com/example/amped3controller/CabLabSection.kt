package com.example.amped3controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import java.util.Locale

/**
 * The cabinet lab: two factory cabinets on one fader, a microphone blend, an axis in between
 * on and off, tone shaping and a bigger or smaller box. All of it is computed on the phone as
 * a target response and fitted onto a factory pole bank; the result lands in the same card as a
 * converted WAV, with the same checks, and reaches the pedal only through the same audition.
 */
@Composable internal fun CabLabSection(s: AmpState, c: AmpedMidiController) {
    val edit by c.labRecipe.collectAsState()
    val live = if (s.cab[0] in 0..23 && s.cab[1] in 0..5 && s.cab[2] in 0..1) FactoryProfiles.key(s.cab[0], s.cab[1], s.cab[2]) else "21:0:0"
    var a by remember(edit) { mutableStateOf(edit?.a ?: live) }
    var b by remember(edit) { mutableStateOf(edit?.b ?: "12:4:0") }
    var blending by remember(edit) { mutableStateOf(edit?.isBlend ?: false) }
    var ratio by remember(edit) { mutableFloatStateOf(((edit?.ratioA ?: 0.5) * 100).toFloat()) }
    var mode by remember(edit) { mutableStateOf(edit?.mode ?: CabLab.BlendMode.COMPLEX) }
    var shape by remember(edit) { mutableStateOf(edit?.shape ?: CabLab.Shape()) }
    var template by remember(edit) { mutableStateOf(edit?.template) }
    var name by remember(edit) { mutableStateOf("") }
    var preset by remember { mutableStateOf("") }

    Section(T("Laboratorio casse", "Cabinet lab"))
    Text(T("La pedaliera ha un solo banco di filtri, quindi due casse vere non esistono. Ma le 288 risposte di fabbrica sono note: un mix di due casse, di due microfoni o una cassa più grande si calcola qui e si adatta a un banco di poli di fabbrica, come un file WAV.",
           "The pedal runs one filter bank, so there is no real second cabinet. But all 288 factory responses are known: a mix of two cabinets or two microphones, or a bigger box, is computed here and fitted onto a factory pole bank, like a WAV."),
        color = Muted, fontSize = 12.sp)

    // quick starts: the classic studio moves, one tap each
    Text(T("Punti di partenza", "Starting points").uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    val (cab, mic, axis) = a.split(":").map { it.toInt() }
    FlowRowCompat {
        Chip(T("Mix di microfoni", "Mic blend"), preset == "mic") { preset = "mic"; blending = true; b = FactoryProfiles.key(cab, if (mic == 4) 0 else 4, axis); ratio = 60f; mode = CabLab.BlendMode.COMPLEX }
        Chip(T("Tra asse e fuori asse", "Between on and off axis"), preset == "axis") { preset = "axis"; blending = true; b = FactoryProfiles.key(cab, mic, 1 - axis); ratio = 50f; mode = CabLab.BlendMode.ENERGY }
        Chip(T("Due casse", "Two cabinets"), preset == "cabs") { preset = "cabs"; blending = true; if (b.startsWith("$cab:")) b = if (cab == 21) "18:0:0" else "21:0:0"; ratio = 60f; mode = CabLab.BlendMode.COMPLEX }
        Chip(T("Cassa più grande", "Bigger box"), preset == "big") { preset = "big"; blending = false; shape = shape.copy(scale = 0.9, lowShelfDb = 1.0) }
        Chip(T("Più scura", "Darker"), preset == "dark") { preset = "dark"; blending = false; shape = shape.copy(tiltDbPerOctave = -1.0) }
        Chip(T("Più aperta", "More open"), preset == "open") { preset = "open"; blending = false; shape = shape.copy(peakDb = 1.5, peakHz = 2500.0, highShelfDb = 1.0) }
    }

    CabinetPicker(T("Cassa A", "Cabinet A"), a) { a = it }
    Row(Modifier.padding(top = 6.dp)) {
        Checkbox(checked = blending, onCheckedChange = { blending = it })
        Text(T("Mescola con una seconda cassa", "Mix with a second cabinet"), modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    }
    if (blending) {
        CabinetPicker(T("Cassa B", "Cabinet B"), b) { b = it }
        Text(T("Rapporto A / B · %d%% / %d%%".format(ratio.toInt(), 100 - ratio.toInt()), "Blend A / B · %d%% / %d%%".format(ratio.toInt(), 100 - ratio.toInt())), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Slider(value = ratio, onValueChange = { ratio = it }, valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red))
        Choices(T("Modo", "Mode"), if (mode == CabLab.BlendMode.COMPLEX) 0 else 1,
            listOf(T("Somma (come un mixer)", "Sum (like a mixer)") to 0, T("Energia (mai cancella)", "Energy (never cancels)") to 1), true) {
            mode = if (it == 0) CabLab.BlendMode.COMPLEX else CabLab.BlendMode.ENERGY
        }
    }

    Text(T("Forma", "Shape").uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 12.dp))
    LabFader(T("Inclinazione", "Tilt"), shape.tiltDbPerOctave, -4.0, 4.0, { "%+.1f dB/oct".format(Locale.US, it) }) { shape = shape.copy(tiltDbPerOctave = it) }
    LabFader(T("Bassi (shelf %.0f Hz)".format(shape.lowShelfHz), "Lows (shelf %.0f Hz)".format(shape.lowShelfHz)), shape.lowShelfDb, -8.0, 8.0, { "%+.1f dB".format(Locale.US, it) }) { shape = shape.copy(lowShelfDb = it) }
    LabFader(T("Acuti (shelf %.0f Hz)".format(shape.highShelfHz), "Highs (shelf %.0f Hz)".format(shape.highShelfHz)), shape.highShelfDb, -8.0, 8.0, { "%+.1f dB".format(Locale.US, it) }) { shape = shape.copy(highShelfDb = it) }
    LabFader(T("Picco a %.0f Hz".format(shape.peakHz), "Peak at %.0f Hz".format(shape.peakHz)), shape.peakDb, -8.0, 8.0, { "%+.1f dB".format(Locale.US, it) }) { shape = shape.copy(peakDb = it) }
    LabFader(T("Frequenza del picco", "Peak frequency"), kotlin.math.ln(shape.peakHz), kotlin.math.ln(150.0), kotlin.math.ln(8000.0), { "%.0f Hz".format(Locale.US, kotlin.math.exp(it)) }) { shape = shape.copy(peakHz = kotlin.math.exp(it)) }
    LabFader(T("Taglia della cassa", "Box size"), 1 / shape.scale, 0.75, 1.35, { "×%.2f".format(Locale.US, it) }) { shape = shape.copy(scale = 1 / it) }

    val recipe = Recipe(a, if (blending) b else null, if (blending) ratio / 100.0 else 1.0, mode, shape, template)
    val preview = remember(recipe) { runCatching { IrConversion.preview(recipe, c.factory) }.getOrNull() }
    val curves = ArrayList<Curve>()
    preview?.let { curves += Curve(it, Red, T("risultato", "result")) }
    curves += Curve(c.factory.curve(a), CurveColours[1], "A")
    if (blending) curves += Curve(c.factory.curve(b), CurveColours[2], "B")
    ResponseGraph(curves, caption = T("anteprima del bersaglio prima dell'adattamento; il profilo finale si vede nella scheda sotto", "target preview before the fit; the final profile appears in the card below"))

    Text(T("Banco di poli", "Pole bank").uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 8.dp))
    Choices("", if (template == null) 0 else 1, listOf(T("Quello della cassa A", "Cabinet A's") to 0, T("Il più vicino al risultato", "Nearest to the result") to 1), true) {
        template = if (it == 0) null else IrOptions.AUTO
    }

    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(T("Nome della cassa", "Cabinet name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("lab-name"))
    Button(onClick = { c.design(recipe, name.trim()); c.labRecipe.value = null }, enabled = !s.busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) {
        Text(T("Costruisci e controlla", "Build and check"))
    }
    Text(recipe.describe { FactoryProfiles.name(it) }, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun LabFader(label: String, value: Double, min: Double, max: Double, format: (Double) -> String, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(format(value), color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(value = value.toFloat(), onValueChange = { onChange(it.toDouble()) }, valueRange = min.toFloat()..max.toFloat(),
        colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red))
}

@Composable internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red, selectedLabelColor = Color.White, labelColor = Muted, containerColor = Panel))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FlowRowCompat(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** A factory cabinet chosen as three menus, with the response of the choice drawn underneath by the caller. */
@Composable internal fun CabinetPicker(label: String, key: String, onPick: (String) -> Unit) {
    val (cab, mic, axis) = key.split(":").map { it.toInt() }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Box(Modifier.fillMaxWidth()) {
            TextButton(onClick = { open = true }) { Text(AmpedProtocol.cabinetNames.getOrNull(cab) ?: "?", color = Paper) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                AmpedProtocol.cabinetNames.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { onPick(FactoryProfiles.key(i, mic, axis)); open = false }) }
            }
        }
        FlowRowCompat {
            AmpedProtocol.micShortNames.forEachIndexed { i, n -> Chip(n, mic == i) { onPick(FactoryProfiles.key(cab, i, axis)) } }
        }
        FlowRowCompat {
            Chip("On axis", axis == 0) { onPick(FactoryProfiles.key(cab, mic, 0)) }
            Chip("Off axis", axis == 1) { onPick(FactoryProfiles.key(cab, mic, 1)) }
        }
    }
}
